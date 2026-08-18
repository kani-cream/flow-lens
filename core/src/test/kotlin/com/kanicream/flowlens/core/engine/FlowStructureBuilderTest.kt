package com.kanicream.flowlens.core.engine

import com.kanicream.flowlens.core.model.BranchKind
import com.kanicream.flowlens.core.model.DispatchConfidence
import com.kanicream.flowlens.core.model.FlowLimits
import com.kanicream.flowlens.core.model.FlowNode
import com.kanicream.flowlens.core.model.FlowNodeKind
import com.kanicream.flowlens.core.model.FlowResultStatus
import com.kanicream.flowlens.core.model.FlowSymbol
import com.kanicream.flowlens.core.model.ResolutionStatus
import com.kanicream.flowlens.core.model.RunId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Structural events (`V0.2_SPEC.md` §3): a structure is one event in its
 * parent's sequence that owns labelled sections of its own.
 */
class FlowStructureBuilderTest {

    private fun symbol(name: String) = FlowSymbol("java", "$name()", "Owner", "java:Owner#$name")

    private fun call(name: String) = FlowEventSpec(
        kind = FlowNodeKind.CALL,
        callSiteLocation = null,
        targetSymbol = symbol(name),
        resolutionStatus = ResolutionStatus.PROJECT_LOCAL,
        dispatchConfidence = DispatchConfidence.EXACT,
    )

    private fun builder(maxNodes: Int = 100) =
        FlowModelBuilder(RunId(1), FlowLimits(maxNodes = maxNodes), sourceRevision = 0)

    private fun names(events: List<FlowNode>) = events.map {
        it.targetSymbol?.displayName ?: it.kind.name
    }

    @Test
    fun `a condition is one event owning its branches`() {
        val b = builder()
        val root = b.openRootFrame(symbol("run"), null)
        b.addEvent(root, call("check"))
        val condition = b.openStructure(
            root,
            StructureSpec(FlowNodeKind.CONDITION, null, summary = "flag"),
        )!!
        b.openBranch(condition, BranchKind.THEN, null)
        b.addEvent(root, call("charge"))
        b.openBranch(condition, BranchKind.ELSE, null)
        b.addEvent(root, call("skip"))
        b.closeStructure(condition)
        b.addEvent(root, call("save"))

        val events = b.snapshot(FlowResultStatus.COMPLETED).rootFrame!!.events
        assertEquals(listOf("check()", "CONDITION", "save()"), names(events))

        val structure = events[1]
        assertTrue(structure.isStructure)
        assertEquals("flag", structure.sourceSummary)
        assertEquals(listOf(BranchKind.THEN, BranchKind.ELSE), structure.branches.map { it.kind })
        assertEquals(listOf("charge()"), names(structure.branches[0].events))
        assertEquals(listOf("skip()"), names(structure.branches[1].events))
    }

    @Test
    fun `an empty branch is kept so a case that does nothing is visible`() {
        val b = builder()
        val root = b.openRootFrame(symbol("run"), null)
        val switch = b.openStructure(root, StructureSpec(FlowNodeKind.SWITCH, null, "subject"))!!
        b.openBranch(switch, BranchKind.CASE, "1")
        b.openBranch(switch, BranchKind.DEFAULT, null)
        b.addEvent(root, call("fallback"))
        b.closeStructure(switch)

        val structure = b.snapshot(FlowResultStatus.COMPLETED).rootFrame!!.events.single()
        assertEquals(2, structure.branches.size)
        assertTrue(structure.branches[0].isEmpty)
        assertEquals("1", structure.branches[0].label)
        assertFalse(structure.branches[1].isEmpty)
    }

    @Test
    fun `structures nest`() {
        val b = builder()
        val root = b.openRootFrame(symbol("run"), null)
        val loop = b.openStructure(root, StructureSpec(FlowNodeKind.LOOP, null, "for each order"))!!
        b.openBranch(loop, BranchKind.BODY, null)
        val condition = b.openStructure(root, StructureSpec(FlowNodeKind.CONDITION, null, "valid"))!!
        b.openBranch(condition, BranchKind.THEN, null)
        b.addEvent(root, call("process"))
        b.closeStructure(condition)
        b.addEvent(root, call("audit"))
        b.closeStructure(loop)

        val outer = b.snapshot(FlowResultStatus.COMPLETED).rootFrame!!.events.single()
        assertEquals(FlowNodeKind.LOOP, outer.kind)
        val body = outer.branches.single()
        assertEquals(listOf("CONDITION", "audit()"), names(body.events))
        assertEquals(listOf("process()"), names(body.events[0].branches.single().events))
    }

    @Test
    fun `a call inside a branch can still own an analyzed body`() {
        val b = builder()
        val root = b.openRootFrame(symbol("run"), null)
        val condition = b.openStructure(root, StructureSpec(FlowNodeKind.CONDITION, null, "flag"))!!
        b.openBranch(condition, BranchKind.THEN, null)
        val call = b.addEvent(root, call("charge"))!!
        val child = b.openChildFrame(root, call, symbol("charge"), null)
        b.addEvent(child, call("gateway"))
        b.closeStructure(condition)

        val result = b.snapshot(FlowResultStatus.COMPLETED)
        val branchCall = result.rootFrame!!.events.single().branches.single().events.single()
        assertEquals(child, branchCall.targetFrameId)
        assertEquals(listOf("gateway()"), names(result.frame(child)!!.events))
    }

    @Test
    fun `a sealed structure can still receive a child frame`() {
        val b = builder()
        val root = b.openRootFrame(symbol("run"), null)
        val condition = b.openStructure(root, StructureSpec(FlowNodeKind.CONDITION, null, "flag"))!!
        b.openBranch(condition, BranchKind.THEN, null)
        val call = b.addEvent(root, call("charge"))!!
        b.closeStructure(condition)

        // The traversal may reach the body after the structure was sealed.
        val child = b.openChildFrame(root, call, symbol("charge"), null)
        val branchCall = b.snapshot(FlowResultStatus.COMPLETED)
            .rootFrame!!.events.single().branches.single().events.single()
        assertEquals(child, branchCall.targetFrameId)
    }

    @Test
    fun `a structure counts against the node budget`() {
        val b = builder(maxNodes = 3)
        val root = b.openRootFrame(symbol("run"), null)
        val condition = b.openStructure(root, StructureSpec(FlowNodeKind.CONDITION, null, "flag"))!!
        b.openBranch(condition, BranchKind.THEN, null)
        assertNotNull(b.addEvent(root, call("charge")))
        assertNull(b.addEvent(root, call("more")), "the structure itself consumed a slot")
        b.closeStructure(condition)
        assertEquals(2, b.snapshot(FlowResultStatus.TRUNCATED).nodeCount)
    }

    @Test
    fun `stopping inside a branch still keeps what was found there`() {
        val b = builder()
        val root = b.openRootFrame(symbol("run"), null)
        val condition = b.openStructure(root, StructureSpec(FlowNodeKind.CONDITION, null, "flag"))!!
        b.openBranch(condition, BranchKind.THEN, null)
        b.addEvent(root, call("charge"))
        // Traversal stops here: cancelled, or out of budget.
        b.closeOpenStructures()

        val events = b.snapshot(FlowResultStatus.CANCELLED).rootFrame!!.events
        assertEquals(listOf("CONDITION"), names(events))
        assertEquals(listOf("charge()"), names(events.single().branches.single().events))
    }

    @Test
    fun `try and finally sections are not conditional while the others are`() {
        val b = builder()
        val root = b.openRootFrame(symbol("run"), null)
        val tryNode = b.openStructure(root, StructureSpec(FlowNodeKind.TRY, null, null))!!
        b.openBranch(tryNode, BranchKind.TRY, null)
        b.openBranch(tryNode, BranchKind.CATCH, "IOException")
        b.openBranch(tryNode, BranchKind.FINALLY, null)
        b.closeStructure(tryNode)

        val branches = b.snapshot(FlowResultStatus.COMPLETED).rootFrame!!.events.single().branches
        assertFalse(branches[0].isConditional)
        assertTrue(branches[1].isConditional)
        assertFalse(branches[2].isConditional)
    }
}
