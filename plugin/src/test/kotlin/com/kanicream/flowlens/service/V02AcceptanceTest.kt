package com.kanicream.flowlens.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.kanicream.flowlens.core.model.BranchKind
import com.kanicream.flowlens.core.model.FlowAnalysisResult
import com.kanicream.flowlens.core.model.FlowLimits
import com.kanicream.flowlens.core.model.FlowNode
import com.kanicream.flowlens.core.model.FlowNodeKind
import com.kanicream.flowlens.core.model.FlowResultStatus
import com.kanicream.flowlens.FlowLensBundle
import com.kanicream.flowlens.testutil.RealJdkProjectDescriptor
import com.kanicream.flowlens.ui.canvas.CanvasViewModelBuilder
import com.kanicream.flowlens.ui.details.FlowDetailsModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * End-to-end coverage of the `V0.2_SPEC.md` §8 acceptance cases through the real
 * analysis service: structure survives extraction, the run engine, the model, and
 * the canvas view model as one piece.
 */
class V02AcceptanceTest : LightJavaCodeInsightFixtureTestCase() {

    override fun getProjectDescriptor(): LightProjectDescriptor = RealJdkProjectDescriptor.INSTANCE

    override fun runInDispatchThread(): Boolean = false

    private val service: FlowAnalysisService get() = FlowAnalysisService.getInstance(project)

    private fun analyze(
        fileName: String,
        text: String,
        entrySignature: String,
        limits: FlowLimits = FlowLimits(),
    ): FlowAnalysisResult {
        ApplicationManager.getApplication().invokeAndWait {
            myFixture.configureByText(fileName, text)
        }
        val offset = myFixture.file.text.indexOf(entrySignature) + entrySignature.length - 2
        service.startAnalysis(myFixture.file.virtualFile, offset, limits)
        return runBlocking {
            withTimeout(60_000) { service.results.first { it != null && it.isTerminal }!! }
        }
    }

    private fun names(events: List<FlowNode>) =
        events.map { it.targetSymbol?.displayName ?: it.kind.name }

    private fun rootEvents(result: FlowAnalysisResult) = result.rootFrame!!.events

    private fun allNodes(result: FlowAnalysisResult): List<FlowNode> {
        val out = mutableListOf<FlowNode>()
        fun visit(nodes: List<FlowNode>) {
            for (node in nodes) {
                out += node
                node.branches.forEach { visit(it.events) }
            }
        }
        result.frames.values.forEach { visit(it.events) }
        return out
    }

    fun `test A a branch becomes a structure and the sequence resumes after it`() {
        val result = analyze(
            "A.java",
            """
            public class A {
                void run(boolean flag) {
                    if (check()) { charge(); } else { skip(); }
                    save();
                }
                boolean check() { return true; }
                void charge() { } void skip() { } void save() { }
            }
            """.trimIndent(),
            "void run(boolean flag)",
        )
        val events = rootEvents(result)
        assertEquals(listOf("check()", "CONDITION", "save()"), names(events))

        val condition = events[1]
        assertEquals(listOf(BranchKind.THEN, BranchKind.ELSE), condition.branches.map { it.kind })
        assertEquals(listOf("charge()"), names(condition.branches[0].events))
        assertEquals(listOf("skip()"), names(condition.branches[1].events))
    }

    fun `test a call inside a branch is still analyzed to its own body`() {
        val result = analyze(
            "Body.java",
            """
            public class Body {
                void run(boolean flag) { if (flag) { charge(); } }
                void charge() { gateway(); }
                void gateway() { }
            }
            """.trimIndent(),
            "void run(boolean flag)",
        )
        val charge = rootEvents(result).single().branches.single().events.single()
        val body = result.frame(charge.targetFrameId!!)!!
        assertEquals(
            "structure does not stop the traversal from entering the callee",
            listOf("gateway()"),
            names(body.events),
        )
    }

    fun `test E a repeated call appears once inside the loop container`() {
        val result = analyze(
            "E.java",
            """
            public class E {
                void run() { for (int i = 0; i < size(); i++) { visit(); } done(); }
                int size() { return 1; }
                void visit() { } void done() { }
            }
            """.trimIndent(),
            "void run()",
        )
        val events = rootEvents(result)
        assertEquals(listOf("LOOP", "done()"), names(events))
        assertEquals(listOf("size()", "visit()"), names(events[0].branches.single().events))
    }

    fun `test G a do-while carries its at-least-once marker all the way to the card`() {
        val result = analyze(
            "G.java",
            """
            public class G {
                void run() { do { a(); } while (again()); }
                void a() { }
                boolean again() { return false; }
            }
            """.trimIndent(),
            "void run()",
        )
        val loop = rootEvents(result).single()
        assertEquals(FlowNodeKind.LOOP, loop.kind)
        assertEquals(
            "the model must carry the marker, not just the extractor",
            "true",
            loop.metadata[FlowMetadata.LOOP_RUNS_AT_LEAST_ONCE],
        )
        val card = CanvasViewModelBuilder.build(result, emptySet())!!.cards.single()
        assertTrue(
            "the card says the body runs at least once, not just \"loop\": ${card.title}",
            card.title.startsWith(FlowLensBundle.message("card.kind.LOOP_ONCE")),
        )
    }

    fun `test H a try keeps its sections and only the conditional ones are conditional`() {
        val result = analyze(
            "H.java",
            """
            public class H {
                void run() {
                    try { a(); } catch (RuntimeException e) { b(); } finally { c(); }
                }
                void a() { } void b() { } void c() { }
            }
            """.trimIndent(),
            "void run()",
        )
        val branches = rootEvents(result).single().branches
        assertEquals(
            listOf(BranchKind.TRY, BranchKind.CATCH, BranchKind.FINALLY),
            branches.map { it.kind },
        )
        assertEquals("RuntimeException", branches[1].label)
        assertEquals(listOf(false, true, false), branches.map { it.isConditional })
    }

    fun `test N a call inside a branch carries no conditional marker`() {
        val result = analyze(
            "N.java",
            """
            public class N {
                void run(boolean flag) { if (flag) { a(); } }
                void a() { }
            }
            """.trimIndent(),
            "void run(boolean flag)",
        )
        val call = rootEvents(result).single().branches.single().events.single()
        assertNull(
            "the section already says the call may be skipped",
            call.metadata[FlowMetadata.CONDITIONAL],
        )
    }

    fun `test I a return says what it hands back`() {
        val result = analyze(
            "I.java",
            """
            public class I {
                void run(boolean flag) {
                    if (flag) { return; }
                    record(total());
                }
                int total() { return 1; }
                void record(int v) { }
            }
            """.trimIndent(),
            "void run(boolean flag)",
        )
        val cards = CanvasViewModelBuilder.build(result, emptySet())!!.cards
        val bare = cards.first { it.isStructure }.sections.single().cards.single()
        val kind = FlowLensBundle.message("card.kind.RETURN")
        assertEquals("nothing is handed back, so the card says only that", kind, bare.title)
    }

    fun `test a valued return is distinguishable from a bare one`() {
        val result = analyze(
            "Valued.java",
            """
            public class Valued {
                int run() { return total(); }
                int total() { return 1; }
            }
            """.trimIndent(),
            "int run()",
        )
        val card = CanvasViewModelBuilder.build(result, emptySet())!!.cards.last()
        val kind = FlowLensBundle.message("card.kind.RETURN")
        assertEquals("$kind total()", card.title)
        assertNotSame("a valued return must not read like a bare one", kind, card.title)
        assertEquals(
            "the details panel agrees with the card",
            "${FlowLensBundle.message("enum.kind.RETURN")} total()",
            FlowDetailsModel.stateOf(card.node).title,
        )
    }

    fun `test P a body whose control flow is represented drops the disclosure`() {
        val result = analyze(
            "P.java",
            """
            public class P {
                void run(boolean flag) {
                    if (flag) { a(); } else { b(); }
                    for (int i = 0; i < 3; i++) { c(); }
                }
                void a() { } void b() { } void c() { }
            }
            """.trimIndent(),
            "void run(boolean flag)",
        )
        assertFalse(
            "an if and a loop are represented now, so nothing is simplified",
            result.controlFlowIncomplete,
        )
    }

    fun `test R running out of budget inside a branch truncates and keeps what was found`() {
        val result = analyze(
            "R.java",
            """
            public class R {
                void run(boolean flag) {
                    if (flag) { a(); a(); a(); a(); a(); a(); }
                }
                void a() { }
            }
            """.trimIndent(),
            "void run(boolean flag)",
            FlowLimits(maxNodes = 4),
        )
        assertEquals(FlowResultStatus.TRUNCATED, result.status)
        assertEquals(4, result.nodeCount)

        val condition = rootEvents(result).single()
        assertEquals(FlowNodeKind.CONDITION, condition.kind)
        val branch = condition.branches.single()
        assertEquals(
            "the branch keeps the events found before the budget ran out",
            listOf("a()", "a()", FlowNodeKind.LIMIT.name),
            names(branch.events),
        )
        assertEquals(
            "the marker is the only LIMIT in the result, so the map stops once",
            1,
            allNodes(result).count { it.kind == FlowNodeKind.LIMIT },
        )
    }

    fun `test the canvas renders a truncated structure without losing the marker`() {
        val result = analyze(
            "Canvas.java",
            """
            public class Canvas {
                void run(boolean flag) {
                    if (flag) { a(); } else { b(); }
                    save();
                }
                void a() { } void b() { } void save() { }
            }
            """.trimIndent(),
            "void run(boolean flag)",
        )
        val root = CanvasViewModelBuilder.build(result, emptySet())!!
        val structure = root.cards.first { it.isStructure }
        assertEquals(2, structure.sections.size)
        assertTrue(
            "the sequence resumes below the container",
            root.cards.last().bounds.y > structure.occupiedBottom,
        )
        assertEquals(
            "every event stays reachable by keyboard",
            listOf("CONDITION", "a()", "b()", "save()"),
            CanvasViewModelBuilder.visibleCards(root)
                .map { it.node.targetSymbol?.displayName ?: it.node.kind.name },
        )
    }
}
