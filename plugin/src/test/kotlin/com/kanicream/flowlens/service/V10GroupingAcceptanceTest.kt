package com.kanicream.flowlens.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.kanicream.flowlens.core.model.FlowAnalysisResult
import com.kanicream.flowlens.core.model.FlowLimits
import com.kanicream.flowlens.core.model.FlowNode
import com.kanicream.flowlens.core.model.FlowNodeKind
import com.kanicream.flowlens.core.model.ResolutionStatus
import com.kanicream.flowlens.testutil.RealJdkProjectDescriptor
import com.kanicream.flowlens.ui.canvas.CanvasViewModelBuilder
import com.kanicream.flowlens.ui.status.FlowStatusModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * End-to-end coverage of `V1.0_GROUPING_SPEC.md` §7 through the real analysis
 * service.
 *
 * The case that produced the rule was a Go route table: ninety of a hundred
 * nodes spent on calls that were never entered, and none of the reader's own
 * code on the map. These fixtures are Java, but the shape is the same — a run
 * of library calls long enough to starve the budget.
 */
class V10GroupingAcceptanceTest : LightJavaCodeInsightFixtureTestCase() {

    override fun getProjectDescriptor(): LightProjectDescriptor = RealJdkProjectDescriptor.INSTANCE

    override fun runInDispatchThread(): Boolean = false

    private val service: FlowAnalysisService get() = FlowAnalysisService.getInstance(project)

    private fun analyze(body: String, limits: FlowLimits = FlowLimits()): FlowAnalysisResult {
        val text = """
            import java.util.List;
            import java.util.ArrayList;

            public class Sample {
                List<String> items = new ArrayList<>();
                StringBuilder text = new StringBuilder();
                void run() { $body }
                void mine() { }
                void alsoMine() { }
            }
        """.trimIndent()
        ApplicationManager.getApplication().invokeAndWait {
            myFixture.configureByText("Sample.java", text)
        }
        val signature = "void run()"
        val offset = myFixture.file.text.indexOf(signature) + signature.length - 1
        service.startAnalysis(myFixture.file.virtualFile, offset, limits)
        return runBlocking {
            withTimeout(60_000) { service.results.first { it != null && it.isTerminal }!! }
        }
    }

    private fun rootEvents(result: FlowAnalysisResult) = result.rootFrame!!.events

    private fun shape(events: List<FlowNode>) = events.map {
        when (it.kind) {
            FlowNodeKind.EXTERNAL_GROUP ->
                "group:${it.targetSymbol!!.displayName}×${it.branches.single().events.size}"
            else -> it.targetSymbol?.displayName ?: it.kind.name
        }
    }

    private fun groups(result: FlowAnalysisResult) =
        rootEvents(result).filter { it.kind == FlowNodeKind.EXTERNAL_GROUP }

    // ---- A, B, D: what collapses ----

    fun `test A three consecutive library calls become one group`() {
        val result = analyze("text.append(1); text.append(2); text.append(3); mine();")
        assertEquals(listOf("group:StringBuilder×3", "mine()"), shape(rootEvents(result)))
    }

    fun `test B a run of two is left alone`() {
        val result = analyze("text.append(1); text.append(2); mine();")
        assertEquals(
            "two cards are not yet noise",
            listOf("append()", "append()", "mine()"),
            shape(rootEvents(result)),
        )
    }

    fun `test D the reader's own code separates two groups`() {
        val result = analyze(
            "text.append(1); text.append(2); text.append(3);" +
                " mine();" +
                " text.append(4); text.append(5); text.append(6);",
        )
        assertEquals(
            listOf("group:StringBuilder×3", "mine()", "group:StringBuilder×3"),
            shape(rootEvents(result)),
        )
    }

    fun `test a group carries the boundary its members cross`() {
        val group = groups(analyze("text.append(1); text.append(2); text.append(3);")).single()
        assertEquals(ResolutionStatus.EXTERNAL, group.resolutionStatus)
        assertNull("a group has no single target, so it claims no dispatch", group.dispatchConfidence)
        assertFalse("its members are a run of steps, not alternatives", group.isStructure)
        assertTrue(group.isGroup)
        assertFalse("every member runs", group.branches.single().isConditional)
    }

    // ---- F, G: adjacency stops at a boundary ----

    fun `test F a run does not span a branch boundary`() {
        val result = analyze(
            "text.append(1); text.append(2);" +
                " if (items.isEmpty()) { text.append(3); text.append(4); text.append(5); }" +
                " text.append(6);",
        )
        val condition = rootEvents(result).first { it.kind == FlowNodeKind.CONDITION }
        assertEquals(
            "G: grouped inside the branch it belongs to",
            listOf("group:StringBuilder×3"),
            shape(condition.branches.first().events),
        )
        assertTrue(
            "and the calls outside it were never joined across the boundary",
            rootEvents(result).none { it.kind == FlowNodeKind.EXTERNAL_GROUP },
        )
    }

    // ---- H, I, J: the budget, which is half the reason the rule exists ----

    fun `test H a group of many claims one node, not many`() {
        val many = (1..40).joinToString(" ") { "text.append($it);" }
        val result = analyze("$many mine();", limits = FlowLimits(maxNodes = 10))
        assertEquals(
            "forty unenterable calls used to exhaust the budget before anything else",
            listOf("group:StringBuilder×40", "mine()"),
            shape(rootEvents(result)),
        )
        assertTrue(
            "and the run finished rather than truncating",
            rootEvents(result).none { it.kind == FlowNodeKind.LIMIT },
        )
    }

    fun `test I the reader's own code is still reached after a long library run`() {
        // The defect this rule exists for: the map ran out of budget on library
        // calls and never showed the code the reader came for.
        val many = (1..40).joinToString(" ") { "text.append($it);" }
        val result = analyze("$many mine(); alsoMine();", limits = FlowLimits(maxNodes = 12))
        assertEquals(
            listOf("group:StringBuilder×40", "mine()", "alsoMine()"),
            shape(rootEvents(result)),
        )
    }

    fun `test J the not-followed count still counts every member`() {
        val result = analyze("text.append(1); text.append(2); text.append(3);")
        val reason = FlowStatusModel.stateOf(null, result).stopReasons
            .single { it.text.contains("3") || it.count == 3 }
        assertEquals(
            "three calls left the project whether or not they were drawn on three cards",
            3,
            reason.count,
        )
    }

    // ---- M: what the reader can open ----

    fun `test M a group is collapsed until asked, and says how many it holds`() {
        val result = analyze("text.append(1); text.append(2); text.append(3);")
        val group = groups(result).single()

        val collapsed = CanvasViewModelBuilder.build(result, expandedNodes = emptySet())!!.cards[0]
        assertTrue("it opens like a body does", collapsed.expandable)
        assertFalse(collapsed.expanded)
        assertEquals(3, collapsed.callsInside)
        assertTrue(
            "the count is on the card, because it is what a collapsed run would hide",
            collapsed.title.contains("3"),
        )
        assertTrue("and it names the library", collapsed.title.contains("StringBuilder"))
        assertTrue("nothing is drawn until it is opened", collapsed.sections.isEmpty())

        val opened = CanvasViewModelBuilder.build(result, expandedNodes = setOf(group.id))!!.cards[0]
        assertEquals(1, opened.sections.size)
        assertEquals(3, opened.sections.single().cards.size)
    }
}
