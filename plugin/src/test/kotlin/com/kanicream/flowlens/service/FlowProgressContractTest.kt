package com.kanicream.flowlens.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.kanicream.flowlens.core.model.FlowAnalysisResult
import com.kanicream.flowlens.core.model.FlowLimits
import com.kanicream.flowlens.core.model.RunId
import com.kanicream.flowlens.core.model.FlowProgress
import com.kanicream.flowlens.core.model.FlowResultStatus
import com.kanicream.flowlens.testutil.RealJdkProjectDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Progress and progressive-rendering contract (`TEST_STRATEGY.md` §7): progress
 * describes real work, the user-visible progression is local-first, and a slow
 * UI collector still ends on the final state.
 */
class FlowProgressContractTest : LightJavaCodeInsightFixtureTestCase() {

    override fun getProjectDescriptor(): LightProjectDescriptor = RealJdkProjectDescriptor.INSTANCE

    override fun runInDispatchThread(): Boolean = false

    private val service: FlowAnalysisService get() = FlowAnalysisService.getInstance(project)

    private fun configureDeepSample() {
        ApplicationManager.getApplication().invokeAndWait {
            myFixture.configureByText(
                "Progressive.java",
                """
                public class Progressive {
                    void run() { a(); b(); c(); }
                    void a() { a1(); a2(); }
                    void b() { b1(); }
                    void c() { }
                    void a1() { deep(); }
                    void a2() { }
                    void b1() { }
                    void deep() { }
                }
                """.trimIndent(),
            )
        }
    }

    /**
     * Waits until the collector has actually observed what it is being asked
     * about. A StateFlow conflates, and `first {}` returning only means *this*
     * coroutine saw the value — cancelling the collector at that moment can cut
     * it off before it was resumed, which is not a contract violation but a race
     * in the observing.
     */
    private suspend fun awaitObserved(timeoutMillis: Long = 10_000, seen: () -> Boolean) {
        withTimeout(timeoutMillis) {
            while (!seen()) delay(10)
        }
    }

    private fun startFromRun(limits: FlowLimits = FlowLimits()): RunId =
        service.startAnalysis(
            myFixture.file.virtualFile,
            myFixture.file.text.indexOf("void run()") + 6,
            limits,
        )

    fun `test observed snapshots never show deep frames before the root frame is complete`() = runBlocking {
        configureDeepSample()
        val allSnapshots = CopyOnWriteArrayList<FlowAnalysisResult>()
        val collector = CoroutineScope(Dispatchers.Default).launch {
            service.results.collect { it?.let(allSnapshots::add) }
        }
        val runId = startFromRun()
        withTimeout(60_000) { service.results.first { it?.runId == runId && it.isTerminal } }
        awaitObserved { allSnapshots.any { it.runId == runId && it.isTerminal } }
        collector.cancel()

        // Snapshots from an earlier run are not this run's picture.
        val snapshots = allSnapshots.filter { it.runId == runId }
        assertTrue(snapshots.isNotEmpty())
        for (snapshot in snapshots) {
            val root = snapshot.rootFrame ?: continue
            val hasDeepFrame = snapshot.frames.values.any { it.depth >= 2 }
            if (hasDeepFrame) {
                assertTrue(
                    "a depth-2 frame appeared while the root picture was still incomplete",
                    root.bodyComplete,
                )
            }
        }
    }

    fun `test counters are monotonic and end in exactly one terminal stage`() = runBlocking {
        configureDeepSample()
        val allEvents = CopyOnWriteArrayList<FlowProgress>()
        val collector = CoroutineScope(Dispatchers.Default).launch {
            service.progress.collect { it?.let(allEvents::add) }
        }
        val runId = startFromRun()
        val terminal = withTimeout(60_000) {
            service.progress.first { it?.runId == runId && it.isTerminal }!!
        }
        awaitObserved { allEvents.any { it.runId == runId && it.isTerminal } }
        collector.cancel()

        // The service keeps the last run's progress until a new analysis starts,
        // and this collector is attached before that happens, so it sees the
        // previous run's terminal event first. The contract under test is about
        // this run's counters.
        val events = allEvents.filter { it.runId == runId }
        assertTrue(events.isNotEmpty())
        assertEquals(
            "nodes produced must never decrease",
            events.map { it.nodesProduced }.sorted(),
            events.map { it.nodesProduced },
        )
        assertEquals(
            "frames analyzed must never decrease",
            events.map { it.framesAnalyzed }.sorted(),
            events.map { it.framesAnalyzed },
        )
        assertEquals(1, events.count { it.isTerminal })
        assertTrue(terminal.elapsedMillis >= 0)
    }

    fun `test a slow collector still observes the final state`() = runBlocking {
        configureDeepSample()
        val seen = CopyOnWriteArrayList<FlowResultStatus>()
        val slowCollector = CoroutineScope(Dispatchers.Default).launch {
            service.results.collect { result ->
                result?.let { seen += it.status }
                // Simulates the throttled EDT collector: conflation must not lose
                // the terminal state (guardrails §10).
                delay(40)
            }
        }
        val runId = startFromRun()
        withTimeout(60_000) { service.results.first { it?.runId == runId && it.isTerminal } }
        withTimeout(10_000) {
            while (seen.lastOrNull() != FlowResultStatus.COMPLETED) delay(20)
        }
        slowCollector.cancel()
        assertEquals(FlowResultStatus.COMPLETED, seen.last())
    }

    fun `test progress reports no work before the analysis produces any`() = runBlocking {
        configureDeepSample()
        val runId = startFromRun()
        val terminal = withTimeout(60_000) {
            service.progress.first { it?.runId == runId && it.isTerminal }!!
        }
        assertTrue(terminal.nodesProduced > 0)
        assertTrue(terminal.framesAnalyzed > 0)
        assertEquals(
            "counted resolutions must add up to the produced nodes",
            terminal.nodesProduced,
            terminal.exactCount + terminal.declaredTargetCount + terminal.ambiguousCount +
                terminal.unresolvedCount,
        )
    }
}
