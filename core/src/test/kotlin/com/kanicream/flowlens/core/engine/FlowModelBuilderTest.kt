package com.kanicream.flowlens.core.engine

import com.kanicream.flowlens.core.model.DispatchConfidence
import com.kanicream.flowlens.core.model.FlowDiagnostic
import com.kanicream.flowlens.core.model.FlowDiagnosticSeverity
import com.kanicream.flowlens.core.model.FlowLimits
import com.kanicream.flowlens.core.model.FlowNodeKind
import com.kanicream.flowlens.core.model.FlowResultStatus
import com.kanicream.flowlens.core.model.FlowSymbol
import com.kanicream.flowlens.core.model.ResolutionStatus
import com.kanicream.flowlens.core.model.RunId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FlowModelBuilderTest {

    private fun symbol(name: String) =
        FlowSymbol("java", "$name()", "Owner", "java:Owner#$name")

    private fun callSpec(name: String) = FlowEventSpec(
        kind = FlowNodeKind.CALL,
        callSiteLocation = null,
        targetSymbol = symbol(name),
        resolutionStatus = ResolutionStatus.PROJECT_LOCAL,
        dispatchConfidence = DispatchConfidence.EXACT,
    )

    private fun builder(maxNodes: Int = 100) =
        FlowModelBuilder(RunId(1), FlowLimits(maxNodes = maxNodes), sourceRevision = 7)

    @Test
    fun `events keep insertion order inside their frame`() {
        val b = builder()
        val root = b.openRootFrame(symbol("root"), null)
        b.addEvent(root, callSpec("a"))
        b.addEvent(root, callSpec("b"))
        b.addEvent(root, callSpec("c"))
        val frame = b.snapshot(FlowResultStatus.RUNNING).rootFrame!!
        assertEquals(listOf("a()", "b()", "c()"), frame.events.map { it.targetSymbol!!.displayName })
    }

    @Test
    fun `two calls to the same target are distinct nodes`() {
        val b = builder()
        val root = b.openRootFrame(symbol("root"), null)
        val first = b.addEvent(root, callSpec("validate"))
        val second = b.addEvent(root, callSpec("validate"))
        assertNotNull(first)
        assertNotNull(second)
        assertNotEquals(first, second)
        val events = b.snapshot(FlowResultStatus.RUNNING).rootFrame!!.events
        assertEquals(events[0].targetSymbol, events[1].targetSymbol)
    }

    @Test
    fun `child frame is linked to its owning call node with incremented depth`() {
        val b = builder()
        val root = b.openRootFrame(symbol("root"), null)
        val node = b.addEvent(root, callSpec("child"))!!
        val childFrame = b.openChildFrame(root, node, symbol("child"), null)
        val result = b.snapshot(FlowResultStatus.RUNNING)
        val call = result.rootFrame!!.events.single()
        assertEquals(childFrame, call.targetFrameId)
        assertEquals(1, result.frame(childFrame)!!.depth)
        assertEquals(0, result.rootFrame!!.depth)
    }

    @Test
    fun `node budget refuses ordinary events and accepts one limit marker`() {
        val b = builder(maxNodes = 3)
        val root = b.openRootFrame(symbol("root"), null)
        assertNotNull(b.addEvent(root, callSpec("a")))
        assertNotNull(b.addEvent(root, callSpec("b")))
        assertNull(b.addEvent(root, callSpec("c")))
        assertFalse(b.wasTruncated)
        assertNotNull(b.addLimitEvent(root))
        assertTrue(b.wasTruncated)
        assertNull(b.addLimitEvent(root))
        val result = b.snapshot(FlowResultStatus.TRUNCATED)
        assertEquals(3, result.nodeCount)
        assertEquals(FlowNodeKind.LIMIT, result.rootFrame!!.events.last().kind)
    }

    @Test
    fun `snapshots are immutable against later mutation`() {
        val b = builder()
        val root = b.openRootFrame(symbol("root"), null)
        b.addEvent(root, callSpec("a"))
        val early = b.snapshot(FlowResultStatus.RUNNING)
        b.addEvent(root, callSpec("b"))
        b.markFrameComplete(root)
        assertEquals(1, early.rootFrame!!.events.size)
        assertFalse(early.rootFrame!!.bodyComplete)
        val late = b.snapshot(FlowResultStatus.COMPLETED)
        assertEquals(2, late.rootFrame!!.events.size)
        assertTrue(late.rootFrame!!.bodyComplete)
    }

    @Test
    fun `snapshot carries run identity revision and diagnostics`() {
        val b = builder()
        b.openRootFrame(symbol("root"), null)
        b.addDiagnostic(FlowDiagnostic(FlowDiagnosticSeverity.WARNING, "flow.error.frame.failed"))
        val result = b.snapshot(FlowResultStatus.RUNNING)
        assertEquals(1L, result.runId.value)
        assertEquals(7L, result.sourceRevision)
        assertEquals(1, result.diagnostics.size)
        assertFalse(result.isTerminal)
        assertTrue(b.snapshot(FlowResultStatus.CANCELLED).isTerminal)
    }

    @Test
    fun `a simplified body is recorded on the frame and on the run`() {
        // The run-wide flag follows from the frames rather than being set beside
        // them, so a warning can always name a body to start from.
        val b = builder()
        val root = b.openRootFrame(symbol("root"), null)
        assertFalse(b.snapshot(FlowResultStatus.RUNNING).controlFlowIncomplete)

        b.markControlFlowSimplified(root)
        val snapshot = b.snapshot(FlowResultStatus.RUNNING)
        assertTrue(snapshot.controlFlowIncomplete)
        assertTrue(snapshot.frames.getValue(root).controlFlowSimplified)
    }
}
