package com.kanicream.flowlens.analysis

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Capability catalog drift guard (guardrails §3.2): what Flow Lens tells the user
 * it supports must match the analyzers actually registered, in both directions.
 */
class FlowAnalyzerCapabilitiesTest : BasePlatformTestCase() {

    fun `test every registered analyzer appears in the capability catalog`() {
        val registered = FlowAnalyzerRegistry.availableLanguageIds()
        val missing = registered - FlowAnalyzerCapabilities.catalogedLanguageIds.toSet()
        assertTrue("analyzers missing from the capability catalog: $missing", missing.isEmpty())
    }

    fun `test every cataloged capability is either available or explained`() {
        for (capability in FlowAnalyzerCapabilities.current()) {
            assertTrue("display name for ${capability.languageId}", capability.displayName.isNotBlank())
            if (capability.available) {
                assertTrue(capability.requirement.isEmpty())
            } else {
                assertTrue(
                    "unavailable ${capability.languageId} must explain why",
                    capability.requirement.isNotBlank(),
                )
            }
        }
    }

    fun `test the product languages are the ones the plan documents`() {
        assertEquals(listOf("java", "kotlin", "go"), FlowAnalyzerCapabilities.catalogedLanguageIds)
    }

    fun `test capabilities reflect the analyzers loaded in this environment`() {
        val available = FlowAnalyzerCapabilities.current().filter { it.available }.map { it.languageId }
        assertEquals(FlowAnalyzerRegistry.availableLanguageIds().sorted(), available.sorted())
        assertTrue("Java is a mandatory dependency", available.contains("java"))
    }
}
