package com.kanicream.flowlens.service

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.kanicream.flowlens.core.model.FlowLocation
import com.kanicream.flowlens.core.model.LocationId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Per-run mapping from neutral [LocationId] handles to smart PSI pointers. Core
 * never sees PSI; navigation resolves handles back through this table. Discarded
 * with its run, so stale runs cannot leak pointers into a newer canvas.
 */
class RunHandles(private val project: Project) {
    private val pointers = ConcurrentHashMap<Int, SmartPsiElementPointer<PsiElement>>()
    private val counter = AtomicInteger()

    /** Registers [element] and returns its neutral location. Call inside a read action. */
    fun locationOf(element: PsiElement): FlowLocation {
        val id = counter.incrementAndGet()
        pointers[id] = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(element)
        return FlowLocation(
            handle = LocationId(id),
            presentablePath = element.containingFile?.virtualFile?.name ?: "",
            line = lineOf(element),
        )
    }

    fun pointer(id: LocationId): SmartPsiElementPointer<PsiElement>? = pointers[id.value]

    private fun lineOf(element: PsiElement): Int {
        val file = element.containingFile ?: return 0
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return 0
        val offset = element.textRange?.startOffset ?: return 0
        if (offset > document.textLength) return 0
        return document.getLineNumber(offset) + 1
    }
}
