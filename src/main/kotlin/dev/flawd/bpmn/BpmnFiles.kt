package dev.flawd.bpmn

import com.intellij.ide.FileIconProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.Icon

object BpmnIcons {
    @JvmField
    val FILE: Icon = IconLoader.getIcon("/icons/bpmn.svg", BpmnIcons::class.java)
}

object BpmnFiles {
    private val extensions = setOf("bpmn", "bpmn2")

    /** `.bpmn`/`.bpmn2` files plus the `*.bpmn20.xml` convention used by Flowable/Activiti. */
    fun isBpmn(file: VirtualFile): Boolean {
        if (file.isDirectory) return false
        val extension = file.extension?.lowercase()
        return extension in extensions || file.name.lowercase().endsWith(".bpmn20.xml")
    }
}

class BpmnFileIconProvider : FileIconProvider {
    override fun getIcon(file: VirtualFile, flags: Int, project: Project?): Icon? =
        if (BpmnFiles.isBpmn(file)) BpmnIcons.FILE else null
}
