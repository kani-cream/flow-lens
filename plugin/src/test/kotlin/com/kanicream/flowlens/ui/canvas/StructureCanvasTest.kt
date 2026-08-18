package com.kanicream.flowlens.ui.canvas

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.kanicream.flowlens.core.engine.FlowEventSpec
import com.kanicream.flowlens.core.engine.FlowModelBuilder
import com.kanicream.flowlens.core.engine.StructureSpec
import com.kanicream.flowlens.core.model.BranchKind
import com.kanicream.flowlens.core.model.DispatchConfidence
import com.kanicream.flowlens.core.model.FlowLimits
import com.kanicream.flowlens.core.model.FlowNodeKind
import com.kanicream.flowlens.core.model.FlowResultStatus
import com.kanicream.flowlens.core.model.FlowSymbol
import com.kanicream.flowlens.core.model.ResolutionStatus
import com.kanicream.flowlens.core.model.RunId

/**
 * Rendering of control structures (`V0.2_SPEC.md` §7): a structure is one
 * container whose labelled sections stack inside it, and the sequence resumes
 * after the container.
 */
class StructureCanvasTest : BasePlatformTestCase() {

    private fun symbol(name: String) = FlowSymbol("java", "$name()", "Owner", "java:Owner#$name")

    private fun call(name: String) = FlowEventSpec(
        kind = FlowNodeKind.CALL,
        callSiteLocation = null,
        targetSymbol = symbol(name),
        resolutionStatus = ResolutionStatus.PROJECT_LOCAL,
        dispatchConfidence = DispatchConfidence.EXACT,
    )

    /** `check(); if (flag) charge() else skip(); save();` */
    private fun conditionResult(): com.kanicream.flowlens.core.model.FlowAnalysisResult {
        val b = FlowModelBuilder(RunId(1), FlowLimits(), 0)
        val root = b.openRootFrame(symbol("run"), null)
        b.addEvent(root, call("check"))
        val condition = b.openStructure(
            root,
            StructureSpec(FlowNodeKind.CONDITION, null, "flag"),
        )!!
        b.openBranch(condition, BranchKind.THEN, null)
        b.addEvent(root, call("charge"))
        b.openBranch(condition, BranchKind.ELSE, null)
        b.addEvent(root, call("skip"))
        b.closeStructure(condition)
        b.addEvent(root, call("save"))
        return b.snapshot(FlowResultStatus.COMPLETED)
    }

    fun `test a structure is one card with stacked labelled sections`() {
        val cards = CanvasViewModelBuilder.build(conditionResult(), emptySet())!!.cards
        assertEquals(listOf("check()", null, "save()"), cards.map { it.node.targetSymbol?.displayName })

        val structure = cards[1]
        assertTrue(structure.isStructure)
        assertEquals(CardStyle.STRUCTURE, structure.style)
        assertTrue("the condition is named on the card", structure.title.contains("flag"))
        assertEquals(2, structure.sections.size)
        assertTrue(structure.sections[0].title.contains("THEN"))
        assertTrue(structure.sections[1].title.contains("ELSE"))
    }

    fun `test sections stack instead of forking, so a branch costs no extra width`() {
        val vm = CanvasViewModelBuilder.build(conditionResult(), emptySet())!!
        val structure = vm.cards[1]
        val then = structure.sections[0]
        val elseSection = structure.sections[1]

        assertTrue("the else section sits below the then section", elseSection.bounds.y > then.bounds.y)
        assertEquals(
            "both sections share one column",
            then.bounds.x,
            elseSection.bounds.x,
        )
        assertTrue(
            "a branch does not widen the map beyond one card plus its inset",
            vm.bounds.width <= CanvasMetrics.CARD_WIDTH + 6 * CanvasMetrics.CHILD_INDENT,
        )
    }

    fun `test the sequence resumes after the container`() {
        val cards = CanvasViewModelBuilder.build(conditionResult(), emptySet())!!.cards
        val structure = cards[1]
        val after = cards[2]
        val lastBranchCard = structure.sections.last().cards.single()

        assertTrue(structure.containerBounds.contains(lastBranchCard.bounds))
        assertTrue(
            "reconvergence: the next event starts below the whole structure",
            after.bounds.y > structure.occupiedBottom,
        )
    }

    fun `test an empty section is still drawn`() {
        val b = FlowModelBuilder(RunId(1), FlowLimits(), 0)
        val root = b.openRootFrame(symbol("run"), null)
        val switch = b.openStructure(root, StructureSpec(FlowNodeKind.SWITCH, null, "kind"))!!
        b.openBranch(switch, BranchKind.CASE, "1")
        b.openBranch(switch, BranchKind.DEFAULT, null)
        b.addEvent(root, call("fallback"))
        b.closeStructure(switch)

        val structure = CanvasViewModelBuilder
            .build(b.snapshot(FlowResultStatus.COMPLETED), emptySet())!!.cards.single()
        val empty = structure.sections[0]
        assertTrue(empty.cards.isEmpty())
        assertTrue("an empty case still occupies a row", empty.bounds.height > 0)
        assertTrue("its label carries the case value", empty.title.contains("1"))
    }

    fun `test structures nest inside sections`() {
        val b = FlowModelBuilder(RunId(1), FlowLimits(), 0)
        val root = b.openRootFrame(symbol("run"), null)
        val loop = b.openStructure(root, StructureSpec(FlowNodeKind.LOOP, null, "order in orders"))!!
        b.openBranch(loop, BranchKind.BODY, null)
        val condition = b.openStructure(root, StructureSpec(FlowNodeKind.CONDITION, null, "valid"))!!
        b.openBranch(condition, BranchKind.THEN, null)
        b.addEvent(root, call("process"))
        b.closeStructure(condition)
        b.closeStructure(loop)

        val outer = CanvasViewModelBuilder
            .build(b.snapshot(FlowResultStatus.COMPLETED), emptySet())!!.cards.single()
        val inner = outer.sections.single().cards.single()
        assertTrue(inner.isStructure)
        assertTrue(
            "a nested structure is laid out inside its section",
            outer.containerBounds.contains(inner.containerBounds),
        )
    }

    fun `test each structure kind gets its own glyph and terminators are distinct`() {
        val b = FlowModelBuilder(RunId(1), FlowLimits(), 0)
        val root = b.openRootFrame(symbol("run"), null)
        for (kind in listOf(
            FlowNodeKind.CONDITION,
            FlowNodeKind.SWITCH,
            FlowNodeKind.LOOP,
            FlowNodeKind.TRY,
        )) {
            val handle = b.openStructure(root, StructureSpec(kind, null, null))!!
            b.openBranch(handle, BranchKind.BODY, null)
            b.closeStructure(handle)
        }
        b.addEvent(
            root,
            call("x").copy(kind = FlowNodeKind.RETURN, targetSymbol = null, dispatchConfidence = null),
        )
        val cards = CanvasViewModelBuilder
            .build(b.snapshot(FlowResultStatus.COMPLETED), emptySet())!!.cards
        val glyphs = cards.map { it.stateGlyph }
        assertEquals("every structure kind is distinguishable", glyphs.size, glyphs.distinct().size)
        assertTrue(glyphs.all { it != null })
        assertEquals(CardStyle.TERMINATOR, cards.last().style)
    }

    fun `test keyboard navigation reaches cards inside sections`() {
        val vm = CanvasViewModelBuilder.build(conditionResult(), emptySet())
        val visible = CanvasViewModelBuilder.visibleCards(vm)
        assertEquals(
            listOf("check()", null, "charge()", "skip()", "save()"),
            visible.map { it.node.targetSymbol?.displayName },
        )
        assertEquals(
            "selection order follows the layout top to bottom",
            visible.map { it.bounds.y }.sorted(),
            visible.map { it.bounds.y },
        )
    }
}
