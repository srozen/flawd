package dev.flawd.bpmn

import com.intellij.ide.actions.CreateFileFromTemplateAction
import com.intellij.ide.actions.CreateFileFromTemplateDialog
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDirectory

class NewBpmnFileAction : CreateFileFromTemplateAction(), DumbAware {
    override fun buildDialog(project: Project, directory: PsiDirectory, builder: CreateFileFromTemplateDialog.Builder) {
        builder.setTitle("New BPMN Diagram")
            .addKind("BPMN diagram", BpmnIcons.FILE, TEMPLATE_NAME)
    }

    override fun getActionName(directory: PsiDirectory?, newName: String, templateName: String?): String =
        "Create BPMN Diagram $newName"

    companion object {
        const val TEMPLATE_NAME = "BPMN Diagram"
    }
}
