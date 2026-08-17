package com.kanicream.flowlens.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FlowProgressTest {

    private fun progress(stage: FlowProgressStage) = FlowProgress(
        runId = RunId(1),
        stage = stage,
        nodesProduced = 0,
        framesAnalyzed = 0,
        exactCount = 0,
        declaredTargetCount = 0,
        ambiguousCount = 0,
        externalCount = 0,
        unresolvedCount = 0,
        elapsedMillis = 0,
    )

    @Test
    fun `exactly the terminal stages are terminal`() {
        val terminal = setOf(
            FlowProgressStage.COMPLETED,
            FlowProgressStage.TRUNCATED,
            FlowProgressStage.CANCELLED,
            FlowProgressStage.STALE,
            FlowProgressStage.FAILED,
        )
        for (stage in FlowProgressStage.entries) {
            assertEquals(stage in terminal, progress(stage).isTerminal, "stage $stage")
        }
    }

    @Test
    fun `result terminal states match lifecycle contract`() {
        assertFalse(
            FlowAnalysisResult(
                RunId(1), FlowResultStatus.RUNNING, null, emptyMap(), 0, false, 0, emptyList(),
            ).isTerminal,
        )
        for (status in FlowResultStatus.entries.filter { it != FlowResultStatus.RUNNING }) {
            assertTrue(
                FlowAnalysisResult(RunId(1), status, null, emptyMap(), 0, false, 0, emptyList()).isTerminal,
                "status $status",
            )
        }
    }
}
