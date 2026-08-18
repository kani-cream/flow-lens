package com.kanicream.flowlens.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.kanicream.flowlens.core.model.FlowLimits
import com.kanicream.flowlens.core.model.FlowResultStatus
import com.kanicream.flowlens.testutil.RealJdkProjectDescriptor
import com.kanicream.flowlens.ui.canvas.CanvasMetrics
import com.kanicream.flowlens.ui.canvas.CanvasViewModelBuilder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger

/**
 * Performance budget measurements (guardrails §17). The thresholds are
 * deliberately loose — they exist to catch gross regressions, not to assert a
 * machine speed — while the measured values are printed so a milestone can
 * record real numbers instead of calling the plugin "responsive".
 */
class FlowAnalysisBenchmarkTest : LightJavaCodeInsightFixtureTestCase() {

    override fun getProjectDescriptor(): LightProjectDescriptor = RealJdkProjectDescriptor.INSTANCE

    override fun runInDispatchThread(): Boolean = false

    private val service: FlowAnalysisService get() = FlowAnalysisService.getInstance(project)

    /** A branching call tree wide and deep enough to reach the node budget. */
    private fun generateProject(classes: Int = 12, methodsPerClass: Int = 8) {
        ApplicationManager.getApplication().invokeAndWait {
            for (c in 0 until classes) {
                val body = buildString {
                    append("public class Gen$c {\n")
                    for (m in 0 until methodsPerClass) {
                        val nextClass = (c + 1) % classes
                        append("    void m$m() { ")
                        if (m + 1 < methodsPerClass) append("m${m + 1}(); ")
                        append("new Gen$nextClass().m0(); ")
                        append("}\n")
                    }
                    append("}\n")
                }
                myFixture.addFileToProject("gen/Gen$c.java", body)
            }
            myFixture.configureByText(
                "Root.java",
                """
                public class Root {
                    void run() {
                        new Gen0().m0();
                        new Gen1().m0();
                        new Gen2().m0();
                    }
                }
                """.trimIndent(),
            )
        }
    }

    override fun tearDown() {
        try {
            FlowRunHooks.reset()
            service.cancelActive()
        } finally {
            super.tearDown()
        }
    }

    fun `test bounded analysis meets the v0_1 performance budget`() = runBlocking {
        generateProject()
        val operations = AtomicInteger()
        FlowRunHooks.beforeFrameOperation = { operations.incrementAndGet() }

        val start = System.nanoTime()
        service.startAnalysis(
            myFixture.file.virtualFile,
            myFixture.file.text.indexOf("void run()") + 6,
            FlowLimits(),
        )
        val firstRoot = withTimeout(60_000) { service.results.first { it?.rootFrame != null }!! }
        val timeToRootMs = (System.nanoTime() - start) / 1_000_000
        val terminal = withTimeout(120_000) { service.results.first { it != null && it.isTerminal }!! }
        val totalMs = (System.nanoTime() - start) / 1_000_000

        val layoutStart = System.nanoTime()
        val expandAll = terminal.frames.values
            .flatMap { it.events }
            .mapNotNull { event -> event.targetFrameId?.let { event.id } }
            .toSet()
        val vm = CanvasViewModelBuilder.build(terminal, expandAll)
        val layoutMs = (System.nanoTime() - layoutStart) / 1_000_000
        val cards = CanvasViewModelBuilder.visibleCards(vm)

        println(
            "BENCHMARK timeToRootFrameMs=$timeToRootMs totalMs=$totalMs " +
                "nodes=${terminal.nodeCount} frames=${terminal.frames.size} " +
                "readOperations=${operations.get()} layoutMs=$layoutMs visibleCards=${cards.size} " +
                "status=${terminal.status}",
        )

        assertNotNull(firstRoot.rootFrame)
        assertTrue("node budget must be respected", terminal.nodeCount <= FlowLimits().maxNodes)
        assertTrue("time to root frame $timeToRootMs ms", timeToRootMs < 15_000)
        assertTrue("total analysis $totalMs ms", totalMs < 60_000)
        assertTrue("layout of ${cards.size} cards took $layoutMs ms", layoutMs < 1_000)
        assertTrue(
            "bounded operations should scale with frames, not with nodes",
            operations.get() <= terminal.frames.size + 2,
        )
    }

    /**
     * A structure-heavy body: nested loops and conditions rather than a wide call
     * tree. Layout recurses through sections here, which the call-tree corpus does
     * not exercise (`V0.2_RESULTS.md` §8).
     */
    private fun generateNestedStructures(depth: Int, callsPerLevel: Int) {
        val body = buildString {
            append("public class Nested {\n    void run(java.util.List<String> items, boolean flag) {\n")
            for (level in 0 until depth) {
                val indent = "        " + "    ".repeat(level)
                append("$indent for (String s$level : items) {\n")
                append("$indent     if (flag) {\n")
                repeat(callsPerLevel) { append("$indent         work(); \n") }
                append("$indent     } else {\n")
                repeat(callsPerLevel) { append("$indent         other(); \n") }
                append("$indent     }\n")
            }
            repeat(depth) { level -> append("        " + "    ".repeat(depth - level - 1) + " }\n") }
            append("    }\n    void work() { }\n    void other() { }\n}\n")
        }
        ApplicationManager.getApplication().invokeAndWait {
            myFixture.configureByText("Nested.java", body)
        }
    }

    fun `test a structure-heavy body lays out without a cost blowup`() = runBlocking {
        generateNestedStructures(depth = 6, callsPerLevel = 4)

        val start = System.nanoTime()
        service.startAnalysis(
            myFixture.file.virtualFile,
            myFixture.file.text.indexOf("void run(") + 6,
            FlowLimits(),
        )
        val terminal = withTimeout(120_000) { service.results.first { it != null && it.isTerminal }!! }
        val analysisMs = (System.nanoTime() - start) / 1_000_000

        val layoutStart = System.nanoTime()
        val vm = CanvasViewModelBuilder.build(terminal, emptySet())
        val layoutMs = (System.nanoTime() - layoutStart) / 1_000_000
        val cards = CanvasViewModelBuilder.visibleCards(vm)

        println(
            "BENCHMARK structure analysisMs=$analysisMs layoutMs=$layoutMs " +
                "nodes=${terminal.nodeCount} visibleCards=${cards.size} " +
                "canvasHeight=${vm!!.bounds.height} status=${terminal.status}",
        )

        assertTrue("nesting must not widen the canvas", vm.bounds.width < 4 * CanvasMetrics.CARD_WIDTH)
        assertTrue("analysis of a structure-heavy body took $analysisMs ms", analysisMs < 60_000)
        assertTrue("layout of ${cards.size} cards took $layoutMs ms", layoutMs < 1_000)
        assertTrue("every card must be reachable", cards.size >= terminal.nodeCount / 2)
    }

    fun `test cancellation latency stays within one bounded operation`() = runBlocking {
        generateProject()
        val cancelNanos = AtomicInteger()
        var cancelAt = 0L
        FlowRunHooks.beforeFrameOperation = { operation ->
            if (operation.index == 3) {
                FlowRunHooks.reset()
                cancelAt = System.nanoTime()
                service.cancelActive()
            }
        }
        service.startAnalysis(
            myFixture.file.virtualFile,
            myFixture.file.text.indexOf("void run()") + 6,
        )
        val terminal = withTimeout(60_000) { service.results.first { it != null && it.isTerminal }!! }
        val latencyMs = (System.nanoTime() - cancelAt) / 1_000_000
        cancelNanos.set(latencyMs.toInt())

        println("BENCHMARK cancellationLatencyMs=$latencyMs status=${terminal.status}")
        assertEquals(FlowResultStatus.CANCELLED, terminal.status)
        assertTrue("cancellation latency $latencyMs ms", latencyMs < 5_000)
    }

    fun `test replacing a flow releases the previous result`() = runBlocking {
        generateProject()
        val offset = myFixture.file.text.indexOf("void run()") + 6
        service.startAnalysis(myFixture.file.virtualFile, offset)
        val first = withTimeout(120_000) { service.results.first { it != null && it.isTerminal }!! }

        service.startAnalysis(myFixture.file.virtualFile, offset)
        val second = withTimeout(120_000) { service.results.first { it != null && it.isTerminal }!! }

        assertFalse("a new run must not reuse the previous run identity", first.runId == second.runId)
        assertNull(
            "handles of a replaced run must no longer be served",
            service.navigationPointer(first.runId, first.rootFrame!!.entryLocation!!.handle),
        )
    }
}
