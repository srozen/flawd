package dev.flawd.bpmn

import com.intellij.ide.FileIconProvider
import com.intellij.javaee.ResourceRegistrar
import com.intellij.javaee.StandardResourceProvider
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

/**
 * Marks the BPMN 2.0 namespaces as known, so the XML view doesn't flag every BPMN file with
 * "URI is not registered" errors. The plugin ships no XSDs, so the XML isn't schema-validated.
 */
class BpmnNamespaceProvider : StandardResourceProvider {
    override fun registerResources(registrar: ResourceRegistrar) {
        NAMESPACES.forEach(registrar::addIgnoredResource)
    }

    companion object {
        val NAMESPACES = listOf(
            "http://www.omg.org/spec/BPMN/20100524/MODEL",
            "http://www.omg.org/spec/BPMN/20100524/DI",
            "http://www.omg.org/spec/DD/20100524/DC",
            "http://www.omg.org/spec/DD/20100524/DI",
            "http://www.omg.org/spec/BPMN/non-normative/color/1.0",
            "http://bpmn.io/schema/bpmn/biocolor/1.0",
        )
    }
}
