package dev.flawd.bpmn

import com.intellij.ide.fileTemplates.FileTemplateManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.ex.FileEditorProviderManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

class BpmnEditorTest : BasePlatformTestCase() {

    private val sample: String
        get() = File("src/test/testData/order-fulfillment.bpmn").readText()

    fun testBpmnExtensionsAreXml() {
        for (name in listOf("order.bpmn", "order.bpmn2", "order.bpmn20.xml")) {
            val file = myFixture.configureByText(name, sample).virtualFile
            assertEquals(name, "XML", file.fileType.name)
            assertTrue(name, BpmnFiles.isBpmn(file))
        }
        assertFalse(BpmnFiles.isBpmn(myFixture.configureByText("pom.xml", "<project/>").virtualFile))
    }

    fun testEditorProviderIsRegisteredAndCreatesSplitEditor() {
        val file = myFixture.configureByText("order.bpmn", sample).virtualFile
        val provider = FileEditorProviderManager.getInstance().getProviderList(project, file)
            .filterIsInstance<BpmnEditorProvider>().single()

        // Headless tests have no JCEF, so this exercises the fallback path and JCEF class loading
        val editor = provider.createEditor(project, file)
        try {
            assertInstanceOf(editor, BpmnSplitEditor::class.java)
            assertEquals(sample, (editor as BpmnSplitEditor).editor.document.text)
        } finally {
            Disposer.dispose(editor)
        }
    }

    fun testWebEditorIsBundled() {
        val html = BpmnDiagramEditor::class.java.getResource("/bpmn-editor/index.html")?.readText()
        assertNotNull("bpmn-editor/index.html is not on the classpath", html)
        assertTrue(html!!.contains("bpmnEditor"))
    }

    fun testNewDiagramTemplate() {
        val template = FileTemplateManager.getInstance(project).getInternalTemplate(NewBpmnFileAction.TEMPLATE_NAME)
        assertEquals("bpmn", template.extension)
        assertTrue(template.text.contains("<bpmn:startEvent"))
    }

    fun testReplaceChangedRangeOnlyTouchesTheDifference() {
        val file = myFixture.configureByText("order.bpmn", sample)
        val document = myFixture.getDocument(file)
        val updated = sample.replace("name=\"Ship order\"", "name=\"Ship via courier\"")

        var changedRange: IntRange? = null
        document.addDocumentListener(object : com.intellij.openapi.editor.event.DocumentListener {
            override fun documentChanged(event: com.intellij.openapi.editor.event.DocumentEvent) {
                changedRange = event.offset until event.offset + event.oldLength
            }
        }, testRootDisposable)

        WriteCommandAction.runWriteCommandAction(project) { BpmnDiagramEditor.replaceChangedRange(document, updated) }

        assertEquals(updated, document.text)
        val shipOrder = sample.indexOf("Ship order")
        assertTrue("change should stay inside the task name, was $changedRange", changedRange!!.first >= shipOrder)
        assertTrue("change should stay inside the task name, was $changedRange", changedRange.last < shipOrder + "Ship order".length)
    }

    fun testJsStringEscapesEverythingThatBreaksAScriptLiteral() {
        assertEquals("\"a\\\"b\\\\c\\nd\\r\\te\\u2028\\u0001\"", BpmnDiagramEditor.jsString("a\"b\\c\nd\r\te \u0001"))
    }
}
