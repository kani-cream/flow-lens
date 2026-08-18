package com.kanicream.flowlens.analysis

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.GeneratedSourcesFilter
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiCompiledElement
import com.intellij.psi.PsiElement
import com.kanicream.flowlens.core.model.ResolutionStatus
import com.kanicream.flowlens.core.model.SourceOrigin

/**
 * Language-independent classification of resolved declarations: project membership
 * and source provenance. Language analyzers refine provenance for language-specific
 * synthetic shapes; this is the conservative shared baseline.
 */
object PsiClassification {

    /** PROJECT_LOCAL / EXTERNAL classification by project content roots. */
    fun resolutionStatusOf(project: Project, declaration: PsiElement): ResolutionStatus {
        val file = declaration.containingFile?.virtualFile ?: return ResolutionStatus.EXTERNAL
        val index = ProjectFileIndex.getInstance(project)
        return when {
            index.isInLibrary(file) -> ResolutionStatus.EXTERNAL
            index.isInContent(file) -> ResolutionStatus.PROJECT_LOCAL
            else -> ResolutionStatus.EXTERNAL
        }
    }

    fun isInTestSource(project: Project, declaration: PsiElement): Boolean {
        val file = declaration.containingFile?.virtualFile ?: return false
        return ProjectFileIndex.getInstance(project).isInTestSourceContent(file)
    }

    /**
     * Conservative provenance baseline. A resolvable PSI callable is not
     * automatically authored code (REPO_LENS_LESSONS.md 3.1): non-physical and
     * compiled elements are never treated as physical source.
     */
    fun sourceOriginOf(project: Project, declaration: PsiElement): SourceOrigin {
        if (declaration is PsiCompiledElement || declaration.containingFile is PsiCompiledElement) {
            return SourceOrigin.LIBRARY
        }
        if (!declaration.isPhysical) {
            return SourceOrigin.SYNTHETIC
        }
        val file = declaration.containingFile?.virtualFile ?: return SourceOrigin.UNKNOWN
        val index = ProjectFileIndex.getInstance(project)
        return when {
            index.isInLibrary(file) -> SourceOrigin.LIBRARY
            // A generated source is a physical file inside the project, so it
            // would otherwise be indistinguishable from code someone wrote — and
            // TraversalPolicy would recurse into it, against the contract that
            // generated declarations are not followed as authored code.
            GeneratedSourcesFilter.isGeneratedSourceByAnyFilter(file, project) ->
                SourceOrigin.GENERATED
            index.isInContent(file) -> SourceOrigin.PHYSICAL_SOURCE
            else -> SourceOrigin.UNKNOWN
        }
    }
}
