package com.kanicream.flowlens.ui.details

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.kanicream.flowlens.core.model.DispatchConfidence
import com.kanicream.flowlens.core.model.ExecutionMode
import com.kanicream.flowlens.core.model.FlowLocation
import com.kanicream.flowlens.core.model.FlowNode
import com.kanicream.flowlens.core.model.FlowNodeKind
import com.kanicream.flowlens.core.model.FlowSymbol
import com.kanicream.flowlens.core.model.LocationId
import com.kanicream.flowlens.core.model.NodeId
import com.kanicream.flowlens.core.model.OrderingStatus
import com.kanicream.flowlens.core.model.ResolutionStatus
import com.kanicream.flowlens.service.FlowMetadata

class FlowDetailsModelTest : BasePlatformTestCase() {

    private fun node(
        kind: FlowNodeKind = FlowNodeKind.CALL,
        dispatch: DispatchConfidence? = DispatchConfidence.EXACT,
        execution: ExecutionMode = ExecutionMode.SYNC,
        metadata: Map<String, String> = emptyMap(),
        targetLocation: FlowLocation? = FlowLocation(LocationId(2), "Target.java", 10),
        callSiteLocation: FlowLocation? = FlowLocation(LocationId(1), "Sample.java", 4),
    ) = FlowNode(
        id = NodeId(1),
        kind = kind,
        callSiteLocation = callSiteLocation,
        targetSymbol = FlowSymbol("java", "charge()", "PaymentService", "java:PaymentService#charge"),
        targetLocation = targetLocation,
        targetFrameId = null,
        depth = 1,
        resolutionStatus = ResolutionStatus.PROJECT_LOCAL,
        dispatchConfidence = dispatch,
        executionMode = execution,
        orderingStatus = OrderingStatus.DETERMINISTIC,
        metadata = metadata,
    )

    private fun valuesOf(state: FlowDetailsViewState) = state.rows.joinToString(" | ") { "${it.label}=${it.value}" }

    fun `test empty selection disables both navigation actions`() {
        val state = FlowDetailsModel.stateOf(null)
        assertFalse(state.openTargetEnabled)
        assertFalse(state.openCallSiteEnabled)
        assertTrue(state.rows.isEmpty())
    }

    fun `test selection shows symbol container and core semantic facts`() {
        val state = FlowDetailsModel.stateOf(node())
        assertEquals("charge()", state.title)
        assertEquals("PaymentService", state.subtitle)
        assertTrue(state.openTargetEnabled)
        assertTrue(state.openCallSiteEnabled)
        val text = valuesOf(state)
        assertTrue(text, text.contains("java"))
        assertTrue(text, text.contains("Sample.java:4"))
    }

    fun `test declared target explains that runtime override may differ`() {
        val state = FlowDetailsModel.stateOf(node(dispatch = DispatchConfidence.DECLARED_TARGET))
        val dispatchRow = state.rows.first { it.value.contains("—") }
        assertTrue(dispatchRow.value, dispatchRow.value.contains("override", ignoreCase = true) ||
            dispatchRow.value.contains("オーバーライド"))
    }

    fun `test conditional calls explain that they may not execute`() {
        val state = FlowDetailsModel.stateOf(
            node(metadata = mapOf(FlowMetadata.CONDITIONAL to "true")),
        )
        val ordering = state.rows.first { it.value.contains("—") }
        assertTrue(ordering.value, ordering.value.length > 3)
    }

    fun `test depth limited calls expose why they were not entered`() {
        val state = FlowDetailsModel.stateOf(
            node(metadata = mapOf(FlowMetadata.LIMIT to FlowMetadata.LIMIT_DEPTH)),
        )
        assertTrue(valuesOf(state), state.rows.any { it.value.contains("depth", ignoreCase = true) ||
            it.value.contains("深さ") })
    }

    fun `test goroutine and deferred execution modes are visible`() {
        for (mode in listOf(ExecutionMode.GOROUTINE, ExecutionMode.DEFERRED)) {
            val state = FlowDetailsModel.stateOf(node(execution = mode))
            assertTrue("mode $mode", state.rows.any { it.value.isNotBlank() })
        }
        val goroutine = FlowDetailsModel.stateOf(node(execution = ExecutionMode.GOROUTINE))
        val deferred = FlowDetailsModel.stateOf(node(execution = ExecutionMode.DEFERRED))
        assertFalse(valuesOf(goroutine) == valuesOf(deferred))
    }

    fun `test unresolved node without target disables only the target action`() {
        val state = FlowDetailsModel.stateOf(node(targetLocation = null))
        assertFalse(state.openTargetEnabled)
        assertTrue(state.openCallSiteEnabled)
    }

    fun `test cycle node explains the back reference`() {
        val state = FlowDetailsModel.stateOf(node(kind = FlowNodeKind.CYCLE))
        assertTrue(valuesOf(state), state.rows.size >= 2)
    }

    fun `test no row value leaks a raw bundle key`() {
        val state = FlowDetailsModel.stateOf(
            node(
                metadata = mapOf(
                    FlowMetadata.ORIGIN to "PHYSICAL_SOURCE",
                    FlowMetadata.CONDITIONAL to "true",
                    FlowMetadata.TEST_SOURCE to "true",
                ),
            ),
        )
        assertFalse(
            valuesOf(state),
            state.rows.any { it.label.startsWith("details.") || it.value.startsWith("enum.") },
        )
    }
}
