package com.kanicream.flowlens.ui.status

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.kanicream.flowlens.core.model.FlowAnalysisResult
import com.kanicream.flowlens.core.model.FlowDiagnostic
import com.kanicream.flowlens.core.model.FlowDiagnosticSeverity
import com.kanicream.flowlens.core.model.FlowProgress
import com.kanicream.flowlens.core.model.FlowProgressStage
import com.kanicream.flowlens.core.model.FlowResultStatus
import com.kanicream.flowlens.core.model.RunId

/**
 * Status mapping (`VISUAL_DESIGN.md` §16): indexing, cancelled, and stale states
 * must be distinguishable from unresolved code, and re-analysis must be offered
 * exactly when the result cannot be trusted as complete.
 */
class FlowStatusModelTest : BasePlatformTestCase() {

    private fun progress(stage: FlowProgressStage, nodes: Int = 0) = FlowProgress(
        runId = RunId(1),
        stage = stage,
        nodesProduced = nodes,
        framesAnalyzed = 0,
        exactCount = 0,
        declaredTargetCount = 0,
        ambiguousCount = 0,
        externalCount = 0,
        unresolvedCount = 0,
        elapsedMillis = 0,
    )

    private fun result(
        status: FlowResultStatus,
        controlFlowIncomplete: Boolean = false,
        diagnostics: List<FlowDiagnostic> = emptyList(),
    ) = FlowAnalysisResult(
        runId = RunId(1),
        status = status,
        rootFrameId = null,
        frames = emptyMap(),
        nodeCount = 0,
        controlFlowIncomplete = controlFlowIncomplete,
        sourceRevision = 0,
        diagnostics = diagnostics,
    )

    fun `test idle state before any analysis`() {
        val state = FlowStatusModel.stateOf(null, null)
        assertEquals(StatusTone.IDLE, state.tone)
        assertNull(state.counters)
        assertFalse(state.stopEnabled)
        assertFalse(state.reanalyzeEnabled)
    }

    fun `test running state enables stop and hides reanalyze`() {
        val state = FlowStatusModel.stateOf(
            progress(FlowProgressStage.ANALYZING_CHILD_FRAMES, nodes = 7),
            result(FlowResultStatus.RUNNING),
        )
        assertEquals(StatusTone.RUNNING, state.tone)
        assertTrue(state.stopEnabled)
        assertFalse(state.reanalyzeEnabled)
        assertTrue(state.counters!!.contains("7"))
    }

    fun `test indexing is a running state distinct from unresolved output`() {
        val state = FlowStatusModel.stateOf(progress(FlowProgressStage.WAITING_FOR_INDEXES), null)
        assertEquals(StatusTone.RUNNING, state.tone)
        assertTrue(state.stopEnabled)
        assertFalse(state.reanalyzeEnabled)
        assertFalse(state.headline.isBlank())
    }

    fun `test completed run is neither warning nor re-analyzable`() {
        val state = FlowStatusModel.stateOf(
            progress(FlowProgressStage.COMPLETED),
            result(FlowResultStatus.COMPLETED),
        )
        assertEquals(StatusTone.DONE, state.tone)
        assertFalse(state.stopEnabled)
        assertFalse(state.reanalyzeEnabled)
    }

    fun `test stale cancelled and truncated offer re-analysis as warnings`() {
        val cases = mapOf(
            FlowProgressStage.STALE to FlowResultStatus.STALE,
            FlowProgressStage.CANCELLED to FlowResultStatus.CANCELLED,
            FlowProgressStage.TRUNCATED to FlowResultStatus.TRUNCATED,
        )
        for ((stage, status) in cases) {
            val state = FlowStatusModel.stateOf(progress(stage), result(status))
            assertEquals("tone for $stage", StatusTone.WARNING, state.tone)
            assertTrue("reanalyze for $stage", state.reanalyzeEnabled)
            assertFalse("stop for $stage", state.stopEnabled)
        }
    }

    fun `test failed run is an error offering re-analysis`() {
        val state = FlowStatusModel.stateOf(
            progress(FlowProgressStage.FAILED),
            result(FlowResultStatus.FAILED),
        )
        assertEquals(StatusTone.ERROR, state.tone)
        assertTrue(state.reanalyzeEnabled)
    }

    fun `test simplified control flow is disclosed from the result`() {
        val state = FlowStatusModel.stateOf(
            progress(FlowProgressStage.COMPLETED),
            result(FlowResultStatus.COMPLETED, controlFlowIncomplete = true),
        )
        assertTrue(state.simplifiedControlFlow)
    }

    fun `test diagnostics are localized and de-duplicated`() {
        val state = FlowStatusModel.stateOf(
            progress(FlowProgressStage.COMPLETED),
            result(
                FlowResultStatus.COMPLETED,
                diagnostics = listOf(
                    FlowDiagnostic(FlowDiagnosticSeverity.WARNING, "flow.error.frame.failed"),
                    FlowDiagnostic(FlowDiagnosticSeverity.WARNING, "flow.error.frame.failed"),
                ),
            ),
        )
        assertEquals(1, state.diagnostics.size)
        assertFalse("must not leak the raw bundle key", state.diagnostics.single().startsWith("flow.error"))
    }

    fun `test terminal result without progress still renders its state`() {
        val state = FlowStatusModel.stateOf(null, result(FlowResultStatus.STALE))
        assertEquals(StatusTone.WARNING, state.tone)
        assertTrue(state.reanalyzeEnabled)
    }
}
