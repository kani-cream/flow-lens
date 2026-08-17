package com.kanicream.flowlens.analysis

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * Dispatches per resolved callable, not once per root, so mixed Java/Kotlin flows
 * switch analyzers mid-traversal (V0.1_SPEC.md section 3). Analyzers register via
 * the extension point; optional language descriptors contribute theirs only when
 * the language plugin is present, so a missing language is a normal absent entry.
 */
object FlowAnalyzerRegistry {

    fun analyzers(): List<FlowLanguageAnalyzer> = FlowLanguageAnalyzer.EP_NAME.extensionList

    /** Analyzer able to analyze [declaration], chosen by the declaration's language. */
    fun forDeclaration(declaration: PsiElement): FlowLanguageAnalyzer? =
        analyzers().firstOrNull { it.supportsDeclaration(declaration) }

    /** Analyzer and entry point for the caret position, or null when unsupported. */
    fun findEntryPoint(file: PsiFile, offset: Int): Pair<FlowLanguageAnalyzer, PsiElement>? {
        for (analyzer in analyzers()) {
            val entry = analyzer.findEntryPoint(file, offset)
            if (entry != null) return analyzer to entry
        }
        return null
    }

    /** Available language capability ids, for status display and drift tests. */
    fun availableLanguageIds(): List<String> = analyzers().map { it.languageId }
}
