package com.kanicream.flowlens.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.kanicream.flowlens.core.model.DispatchConfidence
import com.kanicream.flowlens.core.model.ExecutionMode
import com.kanicream.flowlens.core.model.FlowAnalysisResult
import com.kanicream.flowlens.core.model.FlowLimits
import com.kanicream.flowlens.core.model.FlowNodeKind
import com.kanicream.flowlens.core.model.FlowResultStatus
import com.kanicream.flowlens.core.model.ResolutionStatus
import com.kanicream.flowlens.testutil.RealJdkProjectDescriptor
import com.kanicream.flowlens.ui.canvas.CanvasViewModelBuilder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * End-to-end coverage of the `V0.1_SPEC.md` §21 acceptance cases through the real
 * analysis service, so the spec's observable behavior is verified as a whole
 * rather than only per analyzer.
 *
 * Cases O (indexing) and P (source edit) live in the dedicated indexing and
 * concurrency suites, which can control those conditions deterministically.
 */
class V01AcceptanceTest : LightJavaCodeInsightFixtureTestCase() {

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

    private fun rootTargets(result: FlowAnalysisResult): List<String> =
        result.rootFrame!!.events.map { it.targetSymbol?.displayName ?: "?" }

    fun `test A linear calls keep source order`() {
        val result = analyze(
            "A.java",
            """
            public class A {
                void run() { a(); b(); c(); }
                void a() { } void b() { } void c() { }
            }
            """.trimIndent(),
            "void run()",
        )
        assertEquals(listOf("a()", "b()", "c()"), rootTargets(result))
    }

    fun `test B nested calls are evaluated inside out`() {
        val result = analyze(
            "B.java",
            """
            public class B {
                void run() { save(convert(load())); }
                String load() { return ""; }
                String convert(String s) { return s; }
                void save(String s) { }
            }
            """.trimIndent(),
            "void run()",
        )
        assertEquals(listOf("load()", "convert()", "save()"), rootTargets(result))
    }

    fun `test C chained calls follow the receiver chain`() {
        val result = analyze(
            "C.java",
            """
            public class C {
                void run() { source().transform().save(); }
                C source() { return this; }
                C transform() { return this; }
                void save() { }
            }
            """.trimIndent(),
            "void run()",
        )
        assertEquals(listOf("source()", "transform()", "save()"), rootTargets(result))
    }

    fun `test D duplicate target produces two call sites sharing one symbol`() {
        val result = analyze(
            "D.java",
            """
            public class D {
                void run() { validate(1); validate(2); }
                void validate(int v) { }
            }
            """.trimIndent(),
            "void run()",
        )
        val events = result.rootFrame!!.events
        assertEquals(2, events.size)
        assertEquals(events[0].targetSymbol!!.key, events[1].targetSymbol!!.key)
        assertFalse(events[0].id == events[1].id)
        assertFalse(
            "each call site keeps its own analyzed frame",
            events[0].targetFrameId == events[1].targetFrameId,
        )
    }

    fun `test F exact dispatch may be analyzed recursively`() {
        val result = analyze(
            "F.java",
            """
            public class F {
                void run() { helper(); }
                private void helper() { inner(); }
                private void inner() { }
            }
            """.trimIndent(),
            "void run()",
        )
        val call = result.rootFrame!!.events.single()
        assertEquals(DispatchConfidence.EXACT, call.dispatchConfidence)
        assertNotNull(call.targetFrameId)
    }

    fun `test G declared target is analyzed but flagged`() {
        val result = analyze(
            "G.java",
            """
            public class G {
                void run(Service s) { s.work(); }
            }
            class Service { public void work() { } }
            """.trimIndent(),
            "void run(Service s)",
        )
        val call = result.rootFrame!!.events.single()
        assertEquals(DispatchConfidence.DECLARED_TARGET, call.dispatchConfidence)
        assertNotNull("the declared body may still be analyzed", call.targetFrameId)
    }

    fun `test H ambiguous dispatch stops traversal`() {
        val result = analyze(
            "H.java",
            """
            public class H {
                void run(Gateway g) { g.charge(); }
            }
            interface Gateway { void charge(); }
            class Stripe implements Gateway { public void charge() { } }
            """.trimIndent(),
            "void run(Gateway g)",
        )
        val call = result.rootFrame!!.events.single()
        assertEquals(DispatchConfidence.AMBIGUOUS, call.dispatchConfidence)
        assertNull("v0.1 must not pick one implementation", call.targetFrameId)
    }

    fun `test I unresolved call does not abort its siblings`() {
        val result = analyze(
            "I.java",
            """
            public class I {
                void run() { missing(); after(); }
                void after() { }
            }
            """.trimIndent(),
            "void run()",
        )
        assertEquals(FlowResultStatus.COMPLETED, result.status)
        val events = result.rootFrame!!.events
        assertEquals(ResolutionStatus.UNRESOLVED, events[0].resolutionStatus)
        assertEquals(ResolutionStatus.PROJECT_LOCAL, events[1].resolutionStatus)
        assertNotNull("an unresolved call still navigates to its call site", events[0].callSiteLocation)
    }

    fun `test J external call is terminal and crosses a project boundary`() {
        val result = analyze(
            "J.java",
            """
            public class J {
                void run(String s) { s.trim(); }
            }
            """.trimIndent(),
            "void run(String s)",
        )
        val call = result.rootFrame!!.events.single()
        assertEquals(ResolutionStatus.EXTERNAL, call.resolutionStatus)
        assertNull(call.targetFrameId)
        val card = CanvasViewModelBuilder.build(result, emptySet())!!.cards.single()
        assertTrue("the boundary is drawn locally on the call edge", card.boundaryBeforeCard)
    }

    fun `test K cycles terminate with a back reference`() {
        val result = analyze(
            "K.java",
            """
            public class K {
                void run() { work(); }
                void work() { run(); }
            }
            """.trimIndent(),
            "void run()",
        )
        val workFrame = result.frame(result.rootFrame!!.events.single().targetFrameId!!)!!
        assertEquals(FlowNodeKind.CYCLE, workFrame.events.single().kind)
    }

    fun `test L depth limit marks the blocked call`() {
        val result = analyze(
            "L.java",
            """
            public class L {
                void run() { d1(); }
                void d1() { d2(); }
                void d2() { d3(); }
                void d3() { }
            }
            """.trimIndent(),
            "void run()",
            FlowLimits(maxDepth = 2),
        )
        val d1 = result.frame(result.rootFrame!!.events.single().targetFrameId!!)!!
        val d2Node = d1.events.single()
        val d2Frame = result.frame(d2Node.targetFrameId!!)!!
        val blocked = d2Frame.events.single()
        assertNull(blocked.targetFrameId)
        assertEquals(FlowMetadata.LIMIT_DEPTH, blocked.metadata[FlowMetadata.LIMIT])
        val card = CanvasViewModelBuilder.visibleCards(
            CanvasViewModelBuilder.build(result, result.frames.values.flatMap { it.events }
                .mapNotNull { e -> e.targetFrameId?.let { e.id } }.toSet()),
        ).first { it.nodeId == blocked.id }
        assertTrue("the blocked call shows a continuation marker", card.depthLimited)
    }

    fun `test M node limit truncates with a visible marker`() {
        val result = analyze(
            "M.java",
            """
            public class M {
                void run() { a(); a(); a(); a(); a(); a(); }
                void a() { }
            }
            """.trimIndent(),
            "void run()",
            FlowLimits(maxNodes = 4),
        )
        assertEquals(FlowResultStatus.TRUNCATED, result.status)
        assertEquals(4, result.nodeCount)
        assertEquals(FlowNodeKind.LIMIT, result.rootFrame!!.events.last().kind)
    }

    fun `test S control flow is disclosed as simplified`() {
        val result = analyze(
            "S.java",
            """
            public class S {
                void run(boolean flag) { if (flag) { a(); } else { b(); } }
                void a() { } void b() { }
            }
            """.trimIndent(),
            "void run(boolean flag)",
        )
        assertTrue(result.controlFlowIncomplete)
        val cards = CanvasViewModelBuilder.build(result, emptySet())!!.cards
        assertTrue(
            "conditional calls must not use the certain connector",
            cards.all { it.dashedIncomingConnector },
        )
    }

    fun `test T initial canvas expands the root and collapses child frames`() {
        val result = analyze(
            "T.java",
            """
            public class T {
                void run() { helper(); }
                void helper() { inner(); }
                void inner() { }
            }
            """.trimIndent(),
            "void run()",
        )
        val root = CanvasViewModelBuilder.build(result, emptySet())!!
        assertTrue(root.isRoot)
        val card = root.cards.single()
        assertTrue("the analyzed child frame is available", card.expandable)
        assertFalse("but collapsed initially", card.expanded)
        assertNull(card.childFrame)
    }

    fun `test constructor calls appear as constructor events`() {
        val result = analyze(
            "N.java",
            """
            public class N {
                void run() { new Helper(); }
                static class Helper { Helper() { stamp(); } void stamp() { } }
            }
            """.trimIndent(),
            "void run()",
        )
        val call = result.rootFrame!!.events.single()
        assertEquals(FlowNodeKind.CONSTRUCTOR, call.kind)
        assertNotNull("an explicit constructor body is analyzable", call.targetFrameId)
    }

    fun `test execution mode is synchronous for ordinary jvm calls`() {
        val result = analyze(
            "E.java",
            """
            public class E {
                void run() { a(); }
                void a() { }
            }
            """.trimIndent(),
            "void run()",
        )
        assertEquals(ExecutionMode.SYNC, result.rootFrame!!.events.single().executionMode)
    }
}
