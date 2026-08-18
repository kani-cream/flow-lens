package com.kanicream.flowlens.analysis

import com.kanicream.flowlens.FlowLensBundle

/**
 * One language integration and whether it is usable right now. A missing
 * integration is a normal state with an explanation, not a failure
 * (REPO_LENS_LESSONS.md 2.3, guardrails §3.2).
 */
data class AnalyzerCapability(
    val languageId: String,
    val displayName: String,
    val available: Boolean,
    /** Why it is unavailable; empty when it is available. */
    val requirement: String,
)

/**
 * Catalog of the language integrations Flow Lens claims to support, joined with
 * the analyzers actually registered through the extension point. The catalog is
 * kept in sync by a drift test so a shipped analyzer can never be missing from
 * the capability list, or a listed capability silently unimplemented.
 */
object FlowAnalyzerCapabilities {

    private val CATALOG = listOf(
        CatalogEntry("java", "language.java", "capability.requirement.java"),
        CatalogEntry("kotlin", "language.kotlin", "capability.requirement.kotlin"),
        CatalogEntry("go", "language.go", "capability.requirement.go"),
    )

    /** Language ids Flow Lens documents as product languages. */
    val catalogedLanguageIds: List<String> = CATALOG.map { it.languageId }

    fun current(): List<AnalyzerCapability> {
        val registered = FlowAnalyzerRegistry.availableLanguageIds().toSet()
        return CATALOG.map { entry ->
            val available = entry.languageId in registered
            AnalyzerCapability(
                languageId = entry.languageId,
                displayName = FlowLensBundle.message(entry.displayNameKey),
                available = available,
                requirement = if (available) "" else FlowLensBundle.message(entry.requirementKey),
            )
        }
    }

    private data class CatalogEntry(
        val languageId: String,
        val displayNameKey: String,
        val requirementKey: String,
    )
}
