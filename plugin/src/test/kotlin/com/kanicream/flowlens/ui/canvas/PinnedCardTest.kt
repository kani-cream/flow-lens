package com.kanicream.flowlens.ui.canvas

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.kanicream.flowlens.core.engine.FlowEventSpec
import com.kanicream.flowlens.core.engine.FlowModelBuilder
import com.kanicream.flowlens.core.engine.StructureSpec
import com.kanicream.flowlens.core.model.BranchKind
import com.kanicream.flowlens.core.model.DispatchConfidence
import com.kanicream.flowlens.core.model.FlowAnalysisResult
import com.kanicream.flowlens.core.model.FlowLimits
import com.kanicream.flowlens.core.model.FlowNodeKind
import com.kanicream.flowlens.core.model.FlowResultStatus
import com.kanicream.flowlens.core.model.FlowSymbol
import com.kanicream.flowlens.core.model.ResolutionStatus
import com.kanicream.flowlens.core.model.RunId

/**
 * A pin marks a callable, so it appears wherever that callable does
 * (`V0.3_SPEC.md` §4, acceptance A–D).
 */
class PinnedCardTest : BasePlatformTestCase() {

    private fun symbol(name: String) =
        FlowSymbol("java", "$name()", "Owner", "java:Owner#$name()")

    private fun call(name: String) = FlowEventSpec(
        kind = FlowNodeKind.CALL,
        callSiteLocation = null,
        targetSymbol = symbol(name),
        resolutionStatus = ResolutionStatus.PROJECT_LOCAL,
        dispatchConfidence = DispatchConfidence.EXACT,
    )

    /** `run()` calls charge() twice, and once more inside a branch. */
    private fun result(): FlowAnalysisResult {
        val b = FlowModelBuilder(RunId(1), FlowLimits(), 0)
        val root = b.openRootFrame(symbol("run"), null)
        b.addEvent(root, call("charge"))
        b.addEvent(root, call("audit"))
        b.addEvent(root, call("charge"))
        val condition = b.openStructure(root, StructureSpec(FlowNodeKind.CONDITION, null, "flag"))!!
        b.openBranch(condition, BranchKind.THEN, null)
        b.addEvent(root, call("charge"))
        b.closeStructure(condition)
        return b.snapshot(FlowResultStatus.COMPLETED)
    }

    private val chargeKey = "java:Owner#charge()"

    fun `test A a pinned callable is marked wherever it appears`() {
        val root = CanvasViewModelBuilder.build(result(), emptySet(), setOf(chargeKey))!!
        val marked = CanvasViewModelBuilder.visibleCards(root)
            .filter { it.pinned }
            .map { it.node.targetSymbol?.displayName }
        assertEquals(listOf("charge()", "charge()", "charge()"), marked)
    }

    fun `test B two calls to one pinned callable are both marked`() {
        val cards = CanvasViewModelBuilder.build(result(), emptySet(), setOf(chargeKey))!!.cards
        assertTrue(cards[0].pinned)
        assertFalse("an unpinned neighbour stays unmarked", cards[1].pinned)
        assertTrue(cards[2].pinned)
    }

    fun `test C a pinned callable inside a branch section is marked`() {
        val root = CanvasViewModelBuilder.build(result(), emptySet(), setOf(chargeKey))!!
        val inBranch = root.cards.first { it.isStructure }.sections.single().cards.single()
        assertTrue("a section is not a blind spot", inBranch.pinned)
    }

    fun `test D a pinned entry is marked`() {
        val root = CanvasViewModelBuilder.build(result(), emptySet(), setOf("java:Owner#run()"))!!
        assertTrue(root.pinned)
        assertTrue("only the entry is pinned here", root.cards.none { it.pinned })
    }

    fun `test pinning nothing marks nothing`() {
        val root = CanvasViewModelBuilder.build(result(), emptySet())!!
        assertFalse(root.pinned)
        assertTrue(CanvasViewModelBuilder.visibleCards(root).none { it.pinned })
    }

    fun `test a pin does not change what is analyzed`() {
        val plain = CanvasViewModelBuilder.build(result(), emptySet())!!
        val pinned = CanvasViewModelBuilder.build(result(), emptySet(), setOf(chargeKey))!!
        assertEquals(
            "a mark is a reading aid, not a hidden setting",
            CanvasViewModelBuilder.visibleCards(plain).map { it.title },
            CanvasViewModelBuilder.visibleCards(pinned).map { it.title },
        )
    }
}
