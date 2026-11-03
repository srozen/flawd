package dev.flawd.bpmn

import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.beans.PropertyChangeListener
import java.nio.file.Files
import javax.swing.JComponent
import javax.swing.SwingConstants

private val LOG = logger<BpmnDiagramEditor>()

/**
 * Renders the file's XML with bpmn-js inside JCEF and writes diagram edits back into the document.
 *
 * Sync rules: the document is the source of truth. Diagram edits are applied to it as undoable
 * commands (only the changed range is replaced); document edits made elsewhere (text editor, VCS,
 * external changes) are pushed to the diagram after a short debounce.
 */
class BpmnDiagramEditor(private val project: Project, private val file: VirtualFile) : UserDataHolderBase(), FileEditor {

    var onUndoRedo: ((redo: Boolean) -> Unit)? = null
    var onElementSelected: ((id: String) -> Unit)? = null

    private val document: Document = FileDocumentManager.getInstance().getDocument(file)
        ?: error("No document for ${file.path}")

    private val browser: JBCefBrowser? = if (JBCefApp.isSupported()) JBCefBrowser() else null

    // Must be created before the page loads
    private val bridge: JBCefJSQuery? = browser?.let { JBCefJSQuery.create(it as JBCefBrowserBase) }

    private val pushAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val component: JComponent
    private var pageLoaded = false
    private var applyingDiagramChange = false
    private var disposed = false

    init {
        if (browser == null || bridge == null) {
            component = JBLabel(
                "The BPMN diagram needs JCEF, which isn't available in this IDE runtime. Use the Text view to edit the XML.",
                SwingConstants.CENTER,
            ).apply { border = JBUI.Borders.empty(16) }
        } else {
            component = browser.component
            Disposer.register(this, browser)
            Disposer.register(this, bridge)

            bridge.addHandler { message ->
                handleMessage(message)
                null
            }
            browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
                override fun onLoadEnd(cefBrowser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                    if (frame.isMain) onEdt { onPageLoaded() }
                }
            }, browser.cefBrowser)
            browser.loadHTML(editorHtml)

            document.addDocumentListener(object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    if (!applyingDiagramChange) schedulePush()
                }
            }, this)

            val connection = ApplicationManager.getApplication().messageBus.connect(this)
            connection.subscribe(LafManagerListener.TOPIC, LafManagerListener { pushTheme() })
            connection.subscribe(EditorColorsManager.TOPIC, EditorColorsListener { pushTheme() })
        }
    }

    // --- IDE -> diagram ---------------------------------------------------------------------------

    private fun onPageLoaded() {
        val bridge = bridge ?: return
        pageLoaded = true
        lastTheme = themeJson()
        execute(
            """
            window.__ide = { send: function(message) { ${bridge.inject("message")} } };
            window.bpmnEditor.init({
              xml: ${jsString(document.text)},
              theme: $lastTheme,
              propertiesVisible: ${BpmnSettings.propertiesVisible},
              propertiesWidth: ${BpmnSettings.propertiesWidth}
            });
            """.trimIndent()
        )
    }

    private fun schedulePush() {
        pushAlarm.cancelAllRequests()
        pushAlarm.addRequest({ pushDocument() }, 250)
    }

    private fun pushDocument() {
        if (pageLoaded) execute("window.bpmnEditor.importXml(${jsString(document.text)});")
    }

    private var lastTheme: String? = null

    private fun pushTheme() {
        val theme = themeJson()
        if (!pageLoaded || theme == lastTheme) return
        lastTheme = theme
        execute("window.bpmnEditor.setTheme($theme);")
    }

    private fun themeJson(): String {
        val scheme = EditorColorsManager.getInstance().globalScheme
        return """{"dark": ${!JBColor.isBright()}, "background": "${hex(scheme.defaultBackground)}", """ +
            """"foreground": "${hex(UIUtil.getLabelForeground())}", "border": "${hex(JBColor.border())}"}"""
    }

    fun fit() = execute("window.bpmnEditor.fit();")

    fun setPropertiesVisible(visible: Boolean) = execute("window.bpmnEditor.setPropertiesVisible($visible);")

    fun exportSvg() = execute("window.bpmnEditor.exportSvg();")

    private fun execute(script: String) {
        val browser = browser ?: return
        if (!pageLoaded || disposed) return
        browser.cefBrowser.executeJavaScript(script, browser.cefBrowser.url, 0)
    }

    // --- diagram -> IDE ---------------------------------------------------------------------------

    /** Called on a CEF thread with `<type>\n<payload>`. */
    private fun handleMessage(message: String) {
        val type = message.substringBefore('\n')
        val payload = message.substringAfter('\n', "")
        when (type) {
            "changed" -> onEdt { applyDiagramXml(payload) }
            "select" -> onEdt { onElementSelected?.invoke(payload) }
            "undo", "redo" -> onEdt { onUndoRedo?.invoke(type == "redo") }
            "svg" -> onEdt { saveSvg(payload) }
            "propertiesWidth" -> payload.toIntOrNull()?.let { BpmnSettings.propertiesWidth = it }
            "log" -> LOG.warn("BPMN editor (${file.name}): $payload")
            else -> LOG.warn("Unknown message from BPMN editor: $type")
        }
    }

    private fun applyDiagramXml(xml: String) {
        if (!file.isValid || StringUtil.equals(document.charsSequence, xml)) return
        if (!FileDocumentManager.getInstance().requestWriting(document, project)) {
            // Read-only file: put the diagram back in line with the document
            execute("window.bpmnEditor.importXml(${jsString(document.text)});")
            return
        }
        WriteCommandAction.writeCommandAction(project).withName("Edit BPMN Diagram").run<RuntimeException> {
            applyingDiagramChange = true
            try {
                replaceChangedRange(document, xml)
            } finally {
                applyingDiagramChange = false
            }
        }
    }

    private fun saveSvg(svg: String) {
        val descriptor = FileSaverDescriptor("Export Diagram as SVG", "Save the BPMN diagram as an SVG image", "svg")
        val target = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
            .save(file.parent, file.name.substringBefore('.') + ".svg") ?: return
        val path = target.file.toPath()
        Files.writeString(path, svg)
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
    }

    private fun onEdt(action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater { if (!disposed) action() }
    }

    // --- FileEditor -------------------------------------------------------------------------------

    override fun getComponent(): JComponent = component

    override fun getPreferredFocusedComponent(): JComponent = component

    override fun getName(): String = "Diagram"

    override fun getFile(): VirtualFile = file

    override fun setState(state: FileEditorState) = Unit

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = file.isValid

    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun dispose() {
        disposed = true
    }

    companion object {
        private val editorHtml: String by lazy {
            BpmnDiagramEditor::class.java.getResource("/bpmn-editor/index.html")?.readText()
                ?: error("bpmn-editor/index.html missing from the plugin; was the web editor built?")
        }

        private fun hex(color: java.awt.Color): String = "#" + ColorUtil.toHex(color)

        /** Replaces only the differing middle section so undo, caret and VCS diffs stay local. */
        internal fun replaceChangedRange(document: Document, newText: String) {
            val old = document.charsSequence
            val common = minOf(old.length, newText.length)
            var start = 0
            while (start < common && old[start] == newText[start]) start++
            var oldEnd = old.length
            var newEnd = newText.length
            while (oldEnd > start && newEnd > start && old[oldEnd - 1] == newText[newEnd - 1]) {
                oldEnd--
                newEnd--
            }
            document.replaceString(start, oldEnd, newText.subSequence(start, newEnd))
        }

        internal fun jsString(value: String): String = buildString(value.length + 16) {
            append('"')
            for (c in value) {
                when (c) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    ' ' -> append("\\u2028")
                    ' ' -> append("\\u2029")
                    else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
                }
            }
            append('"')
        }
    }
}
