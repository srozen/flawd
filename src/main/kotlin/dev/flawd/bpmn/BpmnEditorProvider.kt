package dev.flawd.bpmn

import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.fileEditor.TextEditorWithPreviewProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Opens BPMN files in the split editor. The base provider also saves each file's Diagram/Split/Text choice
 * (and both editors' state) in the workspace, so a file reopens the way it was left.
 */
class BpmnEditorProvider : TextEditorWithPreviewProvider(BpmnDiagramEditorProvider()) {
    override fun accept(project: Project, file: VirtualFile): Boolean = BpmnFiles.isBpmn(file)

    override fun createSplitEditor(firstEditor: TextEditor, secondEditor: FileEditor): FileEditor {
        val diagram = secondEditor as BpmnDiagramEditor
        return BpmnSplitEditor(diagram.project, firstEditor, diagram)
    }
}

/** The diagram half of the split editor; only used through [BpmnEditorProvider], so it isn't registered itself. */
class BpmnDiagramEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean = BpmnFiles.isBpmn(file)

    override fun acceptRequiresReadAction(): Boolean = false

    override fun createEditor(project: Project, file: VirtualFile): FileEditor = BpmnDiagramEditor(project, file)

    override fun getEditorTypeId(): String = "bpmn-diagram"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

/**
 * Diagram / Split / Text modes for a BPMN file, like the Markdown editor.
 * The XML document is the source of truth; the diagram renders it and writes edits back into it.
 */
class BpmnSplitEditor(
    project: Project,
    textEditor: TextEditor,
    private val diagram: BpmnDiagramEditor,
) : TextEditorWithPreview(textEditor, diagram, "BPMN Editor", Layout.SHOW_PREVIEW) {

    init {
        diagram.onUndoRedo = { redo ->
            val undoManager = UndoManager.getInstance(project)
            if (redo) {
                if (undoManager.isRedoAvailable(this)) undoManager.redo(this)
            } else {
                if (undoManager.isUndoAvailable(this)) undoManager.undo(this)
            }
        }
        diagram.onElementSelected = { id -> if (editor.component.isShowing) revealInText(id) }
    }

    /** Moves the text caret to the element's definition so split mode shows the XML behind a selection (no-op in diagram-only mode). */
    private fun revealInText(id: String) {
        val pattern = Regex("""\bid\s*=\s*["']${Regex.escape(id)}["']""")
        val match = pattern.find(editor.document.charsSequence) ?: return
        val tagStart = editor.document.charsSequence.lastIndexOf('<', match.range.first).takeIf { it >= 0 }
        editor.caretModel.moveToOffset(tagStart ?: match.range.first)
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
    }

    override fun createLeftToolbarActionGroup(): ActionGroup = DefaultActionGroup(
        object : DumbAwareAction("Fit Diagram", "Zoom the diagram to fit the editor", AllIcons.General.FitContent) {
            override fun actionPerformed(e: AnActionEvent) = diagram.fit()
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
        },
        object : DumbAwareToggleAction("Properties Panel", "Show or hide the element properties panel", AllIcons.Actions.PreviewDetails) {
            override fun isSelected(e: AnActionEvent) = BpmnSettings.propertiesVisible
            override fun setSelected(e: AnActionEvent, state: Boolean) {
                BpmnSettings.propertiesVisible = state
                diagram.setPropertiesVisible(state)
            }
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
        },
        object : DumbAwareAction("Export as SVG…", "Save the diagram as an SVG image", AllIcons.ToolbarDecorator.Export) {
            override fun actionPerformed(e: AnActionEvent) = diagram.exportSvg()
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
        },
    )
}

object BpmnSettings {
    private const val PROPERTIES_VISIBLE = "dev.flawd.bpmn.propertiesVisible"
    private const val PROPERTIES_WIDTH = "dev.flawd.bpmn.propertiesWidth"

    var propertiesVisible: Boolean
        get() = PropertiesComponent.getInstance().getBoolean(PROPERTIES_VISIBLE, true)
        set(value) = PropertiesComponent.getInstance().setValue(PROPERTIES_VISIBLE, value, true)

    var propertiesWidth: Int
        get() = PropertiesComponent.getInstance().getInt(PROPERTIES_WIDTH, 320)
        set(value) = PropertiesComponent.getInstance().setValue(PROPERTIES_WIDTH, value, 320)
}
