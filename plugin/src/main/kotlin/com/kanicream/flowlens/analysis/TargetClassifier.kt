package com.kanicream.flowlens.analysis

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.kanicream.flowlens.core.model.DispatchConfidence
import com.kanicream.flowlens.core.model.FlowSymbol
import com.kanicream.flowlens.core.model.ResolutionStatus
import com.kanicream.flowlens.core.model.SourceOrigin

/**
 * Shared classification of a raw resolve result into a [ResolvedCallTarget].
 *
 * Cross-language rule: a resolve may return a light/synthetic wrapper (for example a
 * Kotlin light method seen from Java). The wrapper's navigation element is unwrapped
 * first; if some registered analyzer supports the unwrapped declaration and it is
 * physical project source, the target is authored code in that language. If no
 * analyzer supports it while the raw element was non-physical, it is a
 * compiler-generated member and classified SYNTHETIC (REPO_LENS_LESSONS.md 3.1).
 */
object TargetClassifier {

    fun classify(
        project: Project,
        rawResolved: PsiElement,
        dispatchConfidence: DispatchConfidence,
        isConstructor: Boolean = false,
        forceNoBody: Boolean = false,
    ): ResolvedCallTarget {
        val unwrapped = rawResolved.navigationElement ?: rawResolved
        val forUnwrapped = FlowAnalyzerRegistry.forDeclaration(unwrapped)
        val analyzer = forUnwrapped ?: FlowAnalyzerRegistry.forDeclaration(rawResolved)
        val declaration = if (forUnwrapped != null) unwrapped else rawResolved

        val origin = when {
            analyzer == null && !rawResolved.isPhysical ->
                // Resolvable but not an authored callable in any supported language:
                // a generated/synthetic member such as data-class copy()/componentN().
                SourceOrigin.SYNTHETIC
            else -> PsiClassification.sourceOriginOf(project, declaration)
        }
        val resolution = when (origin) {
            SourceOrigin.LIBRARY -> ResolutionStatus.EXTERNAL
            else -> PsiClassification.resolutionStatusOf(project, declaration)
        }
        val hasBody = !forceNoBody &&
            origin == SourceOrigin.PHYSICAL_SOURCE &&
            analyzer?.hasAnalyzableBody(declaration) == true

        return ResolvedCallTarget(
            declaration = declaration,
            symbol = analyzer?.describeCallable(declaration) ?: fallbackSymbolFor(rawResolved),
            resolutionStatus = resolution,
            dispatchConfidence = dispatchConfidence,
            sourceOrigin = origin,
            hasAnalyzableBody = hasBody,
            isConstructor = isConstructor,
            inTestSource = PsiClassification.isInTestSource(project, declaration),
        )
    }

    private fun fallbackSymbolFor(element: PsiElement): FlowSymbol? {
        val name = (element as? PsiNamedElement)?.name ?: return null
        val language = element.language.id.lowercase()
        return FlowSymbol(
            languageId = language,
            displayName = "$name()",
            containerName = null,
            key = "$language:?#$name",
        )
    }
}
