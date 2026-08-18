package com.kanicream.flowlens.settings

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.kanicream.flowlens.core.model.FlowLimits

class FlowLensSettingsTest : BasePlatformTestCase() {

    private val settings: FlowLensSettings get() = FlowLensSettings.getInstance(project)

    override fun setUp() {
        super.setUp()
        // The light fixture reuses one project across tests; settings are a
        // project service, so each test starts from persisted defaults.
        settings.loadState(FlowLensSettings.State())
    }

    fun `test defaults match the documented v0_1 defaults`() {
        val snapshot = settings.snapshot()
        assertEquals(FlowLimits.DEFAULT_MAX_DEPTH, snapshot.maxDepth)
        assertEquals(FlowLimits.DEFAULT_MAX_NODES, snapshot.maxNodes)
        assertFalse(snapshot.includeTests)
        assertFalse(snapshot.includeLibraries)
    }

    fun `test persisted state round trips`() {
        val loaded = FlowLensSettings.State().apply {
            maxDepth = 5
            maxNodes = 250
            includeTests = true
            includeLibraries = true
        }
        settings.loadState(loaded)
        val snapshot = settings.snapshot()
        assertEquals(5, snapshot.maxDepth)
        assertEquals(250, snapshot.maxNodes)
        assertTrue(snapshot.includeTests)
        assertTrue(snapshot.includeLibraries)
    }

    fun `test out of range persisted values are clamped instead of breaking analysis`() {
        settings.loadState(FlowLensSettings.State().apply { maxDepth = 0; maxNodes = 1 })
        val low = settings.snapshot()
        assertEquals(FlowLensSettings.MIN_DEPTH, low.maxDepth)
        assertEquals(FlowLensSettings.MIN_NODES, low.maxNodes)

        settings.loadState(FlowLensSettings.State().apply { maxDepth = 9999; maxNodes = 999_999 })
        val high = settings.snapshot()
        assertEquals(FlowLensSettings.MAX_DEPTH, high.maxDepth)
        assertEquals(FlowLensSettings.MAX_NODES, high.maxNodes)
    }

    fun `test snapshot is detached from later settings changes`() {
        val before = settings.snapshot()
        settings.state.maxDepth = FlowLensSettings.MAX_DEPTH
        assertEquals(
            "a run keeps the configuration it started with",
            FlowLimits.DEFAULT_MAX_DEPTH,
            before.maxDepth,
        )
        assertEquals(FlowLensSettings.MAX_DEPTH, settings.snapshot().maxDepth)
    }
}
