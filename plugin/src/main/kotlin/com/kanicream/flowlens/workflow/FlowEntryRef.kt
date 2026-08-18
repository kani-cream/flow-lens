package com.kanicream.flowlens.workflow

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.kanicream.flowlens.analysis.FlowAnalyzerRegistry
import com.kanicream.flowlens.core.model.FlowSymbol

/**
 * A callable, stored so it can be found again in a later session
 * (`V0.3_SPEC.md` §3).
 *
 * Identity is [key] — language, qualifier, name, and parameter types, which the
 * analyzers already build — and [path] says which file to look in. A source
 * offset is deliberately absent: `REPO_LENS_LESSONS.md` §3.8 records that
 * offsets move under any edit above the declaration, which would make every
 * stored entry break for reasons that have nothing to do with the symbol.
 *
 * The display fields are copies, so a list can be shown without opening files.
 * They are what the symbol was called when it was stored; [key] is what decides
 * whether it is still the same callable.
 */
data class FlowEntryRef(
    val key: String,
    val languageId: String,
    val displayName: String,
    val containerName: String?,
    /** Project-relative, so the stored file can be shared or committed. */
    val path: String,
) {
    /**
     * What makes two stored entries the same entry (`V0.3_SPEC.md` §3): the key
     * *and* the file it was found in. A key alone is not enough — an analyzer
     * can only promise uniqueness within a file — and treating two entries with
     * one key as the same would pin, replace, or merge the wrong one.
     */
    val id: String get() = "$path::$key"

    companion object {

        fun of(symbol: FlowSymbol, project: Project, file: VirtualFile): FlowEntryRef =
            FlowEntryRef(
                key = symbol.key,
                languageId = symbol.languageId,
                displayName = symbol.displayName,
                containerName = symbol.containerName,
                path = relativePathOf(project, file),
            )

        /**
         * Whether [symbol] can be stored at all. A key that no analyzer can
         * produce from a declaration — the placeholder for an unresolved call, or
         * a compiler-generated member — would be stored and then never resolve,
         * and one built from a file name rather than a qualified type is not
         * unique within that file. Storing either would break §8's promise that a
         * mark points at what it says it points at.
         */
        fun isStorable(symbol: FlowSymbol): Boolean =
            !symbol.key.contains(":?#") && !symbol.key.endsWith("(generated)")

        /**
         * An absolute path would leak the machine layout into a file that is
         * often committed (guardrails §13). Outside the project — a library
         * source, a scratch — there is nothing to be relative to, so the URL is
         * kept and simply will not resolve on another machine.
         */
        fun relativePathOf(project: Project, file: VirtualFile): String {
            val base = project.guessProjectDir() ?: return file.url
            return VfsUtilCore.getRelativePath(file, base) ?: file.url
        }

        /** True when the file sits inside the project, so its path is relative. */
        fun isInsideProject(project: Project, file: VirtualFile): Boolean {
            val base = project.guessProjectDir() ?: return false
            return VfsUtilCore.getRelativePath(file, base) != null
        }
    }
}

/** What happened when a stored entry was looked up. */
sealed interface EntryResolution {

    /** The declaration is still there and can be analyzed. */
    data class Found(val file: PsiFile, val declaration: PsiElement) : EntryResolution

    /**
     * The file or the declaration is gone. `V0.3_SPEC.md` §8: this is reported,
     * never repaired by matching something with a similar name. A pin that
     * quietly moved to another function would make every mark untrustworthy.
     */
    data object NotFound : EntryResolution
}

/**
 * Finds the declaration a stored entry points at. Call inside a read action.
 */
object FlowEntryResolver {

    fun resolve(project: Project, ref: FlowEntryRef): EntryResolution {
        val file = fileOf(project, ref) ?: return EntryResolution.NotFound
        val psiFile = PsiManager.getInstance(project).findFile(file) ?: return EntryResolution.NotFound
        val declaration = findByKey(psiFile, ref.key) ?: return EntryResolution.NotFound
        return EntryResolution.Found(psiFile, declaration)
    }

    /**
     * Whether the file a stored entry names still exists. A VFS lookup only, so
     * it is cheap enough to run for a whole list; whether the declaration inside
     * it survived is a separate, expensive question.
     */
    fun fileExists(project: Project, ref: FlowEntryRef): Boolean =
        fileOf(project, ref)?.isValid == true

    private fun fileOf(project: Project, ref: FlowEntryRef): VirtualFile? {
        val base = project.guessProjectDir()
        return base?.findFileByRelativePath(ref.path)
            ?: com.intellij.openapi.vfs.VirtualFileManager.getInstance().findFileByUrl(ref.path)
    }

    /**
     * The callable in [file] whose symbol key matches, or null. Walking the file
     * rather than consulting an index keeps this language-neutral: every
     * analyzer already knows which elements it can describe.
     */
    private fun findByKey(file: PsiFile, key: String): PsiElement? {
        val analyzers = FlowAnalyzerRegistry.analyzers()
        if (analyzers.isEmpty()) return null
        var found: PsiElement? = null
        file.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                if (found != null) return
                val analyzer = analyzers.firstOrNull { it.supportsDeclaration(element) }
                if (analyzer != null &&
                    analyzer.hasAnalyzableBody(element) &&
                    analyzer.describeCallable(element).key == key
                ) {
                    found = element
                    stopWalking()
                    return
                }
                super.visitElement(element)
            }
        })
        return found
    }
}
