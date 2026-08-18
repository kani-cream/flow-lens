package com.kanicream.flowlens.service

import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.kanicream.flowlens.core.engine.FlowModelBuilder
import com.kanicream.flowlens.core.model.FlowAnalysisResult
import com.kanicream.flowlens.core.model.FlowLimits
import com.kanicream.flowlens.core.model.FlowNodeKind
import com.kanicream.flowlens.core.model.FlowResultStatus
import com.kanicream.flowlens.core.model.RunId
import com.kanicream.flowlens.testutil.RealJdkProjectDescriptor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Service/lifecycle integration (TEST_STRATEGY.md Layer C): end-to-end progressive
 * runs, run replacement, stale-event rejection, limits, and cycles on real PSI.
 */
class FlowAnalysisServiceLifecycleTest : LightJavaCodeInsightFixtureTestCase() {

    override fun getProjectDescriptor(): LightProjectDescriptor = RealJdkProjectDescriptor.INSTANCE

    private val service: FlowAnalysisService get() = FlowAnalysisService.getInstance(project)

    private fun awaitTerminal(): FlowAnalysisResult = runBlocking {
        withTimeout(60_000) { service.results.first { it != null && it.isTerminal }!! }
    }

    private fun startFromCaret(limits: FlowLimits = FlowLimits()): RunId {
        val file = myFixture.file.virtualFile
        return service.startAnalysis(file, myFixture.caretOffset, limits)
    }

    fun `test a stop reaches the run being launched, not the one that just ended`() = runBlocking {
        // The previous run's job stays in the field until the new one is adopted,
        // so a Stop landing in that window used to cancel a run that had already
        // finished while the new one carried on. CI found this twice.
        myFixture.configureByText(
            "Sequential.java",
            """
            public class Sequential {
                void ru<caret>n() { first(); }
                void first() { second(); }
                void second() { third(); }
                void third() { }
            }
            """.trimIndent(),
        )
        startFromCaret()
        assertEquals(FlowResultStatus.COMPLETED, awaitTerminal().status)

        FlowRunHooks.beforeFrameOperation = {
            FlowRunHooks.reset()
            service.cancelActive()
        }
        val runId = startFromCaret()
        val result = withTimeoutOrNull(20_000) {
            service.results.first { it?.runId == runId && it.isTerminal }
        }
        assertTrue(
            "the second run must not complete: ${result?.status}",
            result == null || result.status != FlowResultStatus.COMPLETED,
        )
    }

    fun `test a stop pressed before the run is launched still cancels it`() = runBlocking {
        // startAnalysis publishes the run id before it has a job to cancel, so a
        // Stop that lands in that window used to be dropped and the run carried
        // on. CI found this through a test that cancelled at the first frame.
        myFixture.configureByText(
            "Stoppable.java",
            """
            public class Stoppable {
                void ru<caret>n() { first(); }
                void first() { second(); }
                void second() { third(); }
                void third() { fourth(); }
                void fourth() { }
            }
            """.trimIndent(),
        )
        FlowRunHooks.beforeFrameOperation = {
            FlowRunHooks.reset()
            service.cancelActive()
        }
        startFromCaret()
        val result = withTimeoutOrNull(20_000) {
            service.results.first { it != null && it.isTerminal }
        }
        assertTrue(
            "the run must not report itself completed: ${result?.status}",
            result == null || result.status != FlowResultStatus.COMPLETED,
        )
    }

    fun `test full run produces ordered root frame and analyzable child frames`() {
        myFixture.configureByText(
            "Sample.java",
            """
            public class Sample {
                void ru<caret>n() { save(convert(load())); }
                String load() { return ""; }
                String convert(String s) { return s; }
                void save(String s) { helper(); }
                void helper() { }
            }
            """.trimIndent(),
        )
        val runId = startFromCaret()
        val result = awaitTerminal()
        assertEquals(FlowResultStatus.COMPLETED, result.status)
        assertEquals(runId, result.runId)
        val root = result.rootFrame!!
        assertEquals(
            listOf("load()", "convert()", "save()"),
            root.events.map { it.targetSymbol!!.displayName },
        )
        // Depth-1 child frames were analyzed: save() owns a frame containing helper().
        val saveNode = root.events.last()
        assertNotNull(saveNode.targetFrameId)
        val saveFrame = result.frame(saveNode.targetFrameId!!)!!
        assertEquals(1, saveFrame.depth)
        assertEquals(listOf("helper()"), saveFrame.events.map { it.targetSymbol!!.displayName })
        assertTrue(saveFrame.bodyComplete)
    }

    fun `test recursion produces cycle node instead of infinite traversal`() {
        myFixture.configureByText(
            "Cycle.java",
            """
            public class Cycle {
                void ru<caret>n() { work(); }
                void work() { run(); }
            }
            """.trimIndent(),
        )
        startFromCaret()
        val result = awaitTerminal()
        assertEquals(FlowResultStatus.COMPLETED, result.status)
        val workFrame = result.frame(result.rootFrame!!.events.single().targetFrameId!!)!!
        val cycleNode = workFrame.events.single()
        assertEquals(FlowNodeKind.CYCLE, cycleNode.kind)
        assertNull(cycleNode.targetFrameId)
    }

    fun `test depth limit stops expansion with visible marker metadata`() {
        myFixture.configureByText(
            "Deep.java",
            """
            public class Deep {
                void ru<caret>n() { d1(); }
                void d1() { d2(); }
                void d2() { d3(); }
                void d3() { d4(); }
                void d4() { }
            }
            """.trimIndent(),
        )
        startFromCaret(FlowLimits(maxDepth = 2))
        val result = awaitTerminal()
        assertEquals(FlowResultStatus.COMPLETED, result.status)
        val d1Frame = result.frame(result.rootFrame!!.events.single().targetFrameId!!)!!
        val d2Node = d1Frame.events.single()
        // d2 is entered (depth 2 == limit); its call to d3 must not be.
        val d2Frame = result.frame(d2Node.targetFrameId!!)!!
        assertEquals(2, d2Frame.depth)
        val d3Node = d2Frame.events.single()
        assertNull("beyond-limit target must not be entered", d3Node.targetFrameId)
        assertEquals(FlowMetadata.LIMIT_DEPTH, d3Node.metadata[FlowMetadata.LIMIT])
    }

    fun `test node limit truncates with limit marker and truncated status`() {
        myFixture.configureByText(
            "Wide.java",
            """
            public class Wide {
                void ru<caret>n() { a(); a(); a(); a(); a(); a(); }
                void a() { }
            }
            """.trimIndent(),
        )
        startFromCaret(FlowLimits(maxNodes = 4))
        val result = awaitTerminal()
        assertEquals(FlowResultStatus.TRUNCATED, result.status)
        assertEquals(4, result.nodeCount)
        val kinds = result.rootFrame!!.events.map { it.kind }
        assertEquals(FlowNodeKind.LIMIT, kinds.last())
        assertEquals(3, kinds.count { it == FlowNodeKind.CALL })
    }

    fun `test starting a new run replaces the previous one`() {
        myFixture.configureByText(
            "Two.java",
            """
            public class Two {
                void fir<caret>st() { a(); }
                void second() { a(); a(); }
                void a() { }
            }
            """.trimIndent(),
        )
        startFromCaret()
        awaitTerminal()
        val secondOffset = myFixture.file.text.indexOf("second()") + 2
        val runId2 = service.startAnalysis(myFixture.file.virtualFile, secondOffset)
        val result = awaitTerminal()
        assertEquals(runId2, result.runId)
        assertEquals("second()", result.rootFrame!!.symbol.displayName)
        assertEquals(2, result.rootFrame!!.events.size)
    }

    fun `test late events from an old run are rejected`() {
        myFixture.configureByText(
            "Old.java",
            """
            public class Old {
                void ru<caret>n() { a(); }
                void a() { }
            }
            """.trimIndent(),
        )
        val current = startFromCaret()
        val result = awaitTerminal()
        assertEquals(current, result.runId)

        // Forge a late event from a stale run and push it through the same gate the
        // real pipeline uses; it must not replace the current result.
        val staleRunId = RunId(current.value - 1)
        val staleBuilder = FlowModelBuilder(staleRunId, FlowLimits(), 0)
        staleBuilder.openRootFrame(
            com.kanicream.flowlens.core.model.FlowSymbol("java", "stale()", null, "java:stale"),
            null,
        )
        service.acceptResult(
            FlowAnalysisResultEvent(staleRunId, staleBuilder.snapshot(FlowResultStatus.COMPLETED)),
        )
        assertEquals(current, service.results.value!!.runId)
        assertEquals("run()", service.results.value!!.rootFrame!!.symbol.displayName)
    }

    fun `test cancellation of an idle service is a no-op`() {
        service.cancelActive()
    }

    fun `test failed entry produces failed result with diagnostic`() {
        myFixture.configureByText(
            "NoEntry.java",
            """
            public class NoEntry {
                int fie<caret>ld;
            }
            """.trimIndent(),
        )
        startFromCaret()
        val result = awaitTerminal()
        assertEquals(FlowResultStatus.FAILED, result.status)
        assertEquals("flow.error.no.entry.point", result.diagnostics.single().messageKey)
    }

    fun `test navigation pointers are only served for the current run`() {
        myFixture.configureByText(
            "Nav.java",
            """
            public class Nav {
                void ru<caret>n() { a(); }
                void a() { }
            }
            """.trimIndent(),
        )
        val runId = startFromCaret()
        val result = awaitTerminal()
        val callSite = result.rootFrame!!.events.single().callSiteLocation!!
        assertNotNull(service.navigationPointer(runId, callSite.handle))
        assertNull(service.navigationPointer(RunId(runId.value - 1), callSite.handle))
    }
}
