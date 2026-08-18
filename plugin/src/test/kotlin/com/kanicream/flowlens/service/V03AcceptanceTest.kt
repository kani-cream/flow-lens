package com.kanicream.flowlens.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.kanicream.flowlens.core.model.FlowAnalysisResult
import com.kanicream.flowlens.core.model.FlowLimits
import com.kanicream.flowlens.core.model.FlowResultStatus
import com.kanicream.flowlens.testutil.RealJdkProjectDescriptor
import com.kanicream.flowlens.workflow.FlowEntryLauncher
import com.kanicream.flowlens.workflow.FlowEntryRef
import com.kanicream.flowlens.workflow.FlowLensFlows
import com.kanicream.flowlens.workflow.FlowLensRecents
import com.kanicream.flowlens.workflow.LaunchOutcome
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * End-to-end coverage of `V0.3_SPEC.md` §10 through the real analysis service:
 * a stored entry survives the round trip from analysis to storage and back.
 */
class V03AcceptanceTest : LightJavaCodeInsightFixtureTestCase() {

    override fun getProjectDescriptor(): LightProjectDescriptor = RealJdkProjectDescriptor.INSTANCE

    override fun runInDispatchThread(): Boolean = false

    private val service: FlowAnalysisService get() = FlowAnalysisService.getInstance(project)
    private val flows get() = FlowLensFlows.getInstance(project)
    private val recents get() = FlowLensRecents.getInstance(project)

    private val sample = """
        public class Orders {
            void purchase() { save(); helper.help(); "x".trim(); }
            void refund() { first(); }
            void save() { }
            void first() { second(); }
            void second() { third(); }
            void third() { fourth(); }
            void fourth() { }
            Helper helper = new Helper();
        }

        interface Helper { void help(); }
    """.trimIndent()

    override fun setUp() {
        super.setUp()
        // The light project is shared across test classes, so every analysis any
        // other suite ran is already in this list. Start from a known state.
        flows.loadState(FlowLensFlows.State())
        recents.clear()
    }

    private fun analyze(entry: String, limits: FlowLimits = FlowLimits()): FlowAnalysisResult {
        ApplicationManager.getApplication().invokeAndWait {
            if (myFixture.file?.name != "Orders.java") myFixture.configureByText("Orders.java", sample)
        }
        val offset = myFixture.file.text.indexOf(entry) + entry.length - 2
        val runId = service.startAnalysis(myFixture.file.virtualFile, offset, limits)
        val result = runBlocking {
            withTimeout(60_000) {
                service.results.first { it?.runId == runId && it.isTerminal }!!
            }
        }
        // The recent is written after the result is published, so a test that
        // reads the list the instant the result arrives can miss it. Waiting for
        // the write is what makes every recents assertion below deterministic.
        val key = result.rootFrame?.symbol?.key
        if (key != null && result.status == FlowResultStatus.COMPLETED) {
            runBlocking {
                withTimeout(10_000) {
                    while (recents.recents().none { it.entry.key == key }) delay(10)
                }
            }
        }
        return result
    }

    private fun entryRef(result: FlowAnalysisResult): FlowEntryRef =
        FlowEntryRef.of(result.rootFrame!!.symbol, project, myFixture.file.virtualFile)

    fun `test H a finished analysis becomes the newest recent`() {
        analyze("void purchase()")
        analyze("void refund()")
        analyze("void purchase()")

        assertEquals(
            listOf("purchase()", "refund()"),
            recents.recents().map { it.entry.displayName },
        )
    }

    fun `test I a cancelled analysis is not recorded`() {
        analyze("void purchase()")
        val before = recents.recents().map { it.entry.key }

        // Cancelling from the scheduler rather than from the test thread, and not
        // at the first operation: startAnalysis adopts the job only after the run
        // is launched, so cancelling at operation 0 can arrive before there is a
        // job to cancel and the run finishes normally. CI found that race.
        FlowRunHooks.beforeFrameOperation = { operation ->
            if (operation.index >= 1) {
                FlowRunHooks.reset()
                service.cancelActive()
            }
        }
        val entry = "void refund()"
        val offset = myFixture.file.text.indexOf(entry) + entry.length - 2
        val runId = service.startAnalysis(myFixture.file.virtualFile, offset)
        // A run killed mid-frame may never publish a terminal snapshot at all,
        // which is itself a state that must not produce a recent entry.
        val result = runBlocking {
            withTimeoutOrNull(10_000) { service.results.first { it?.runId == runId && it.isTerminal } }
        }
        assertTrue(
            "the run must have stopped short: ${result?.status}",
            result == null || result.status == FlowResultStatus.CANCELLED,
        )
        assertEquals(
            "a cancelled exploration must not push real work out of a capped list",
            before,
            recents.recents().map { it.entry.key },
        )
    }

    fun `test F a stored entry re-runs in a later session`() {
        val first = analyze("void purchase()")
        val ref = entryRef(first)
        flows.save("purchase", ref, FlowLimits(maxDepth = 5))

        // A new session sees only what was persisted.
        val saved = flows.savedFlows().single()
        val outcome = FlowEntryLauncher.launch(project, saved.entry, saved.limits)
        assertTrue(outcome is LaunchOutcome.Started)

        val runId = (outcome as LaunchOutcome.Started).runId
        val result = runBlocking {
            withTimeout(60_000) { service.results.first { it?.runId == runId && it.isTerminal }!! }
        }
        assertEquals(FlowResultStatus.COMPLETED, result.status)
        assertEquals("purchase()", result.rootFrame!!.symbol.displayName)
    }

    fun `test G a saved flow re-runs at the limits it was saved with`() {
        val result = analyze("void purchase()", FlowLimits(maxDepth = 5, maxNodes = 200))
        flows.save("deep", entryRef(result), service.limitsOfCurrentRun()!!)

        val saved = flows.savedFlows().single()
        assertEquals(
            "the settings may have moved on; the saved flow has not",
            5,
            saved.limits.maxDepth,
        )
        assertEquals(200, saved.limits.maxNodes)
    }

    fun `test L an entry whose file is gone does not start an analysis`() {
        val result = analyze("void purchase()")
        val ref = entryRef(result).copy(path = "moved/Elsewhere.java")

        assertEquals(LaunchOutcome.Unresolved, FlowEntryLauncher.launch(project, ref, null))
        assertFalse(FlowEntryLauncher.isResolvable(project, ref))
    }

    fun `test the pinned key of an analyzed root matches its cards`() {
        // The mark is looked up by key, so the key a pin stores must be the key a
        // card carries — otherwise a pin would silently never match.
        val result = analyze("void purchase()")
        flows.togglePin(entryRef(result))

        val save = result.rootFrame!!.events.first { it.targetSymbol?.displayName == "save()" }
        assertFalse(
            "save() is not the pinned callable",
            flows.pinnedKeys().contains(save.targetSymbol!!.key),
        )
        assertTrue(flows.pinnedKeys().contains(result.rootFrame!!.symbol.key))

        val saveRef = FlowEntryRef.of(save.targetSymbol!!, project, myFixture.file.virtualFile)
        flows.togglePin(saveRef)
        assertTrue(flows.pinnedKeys().contains(save.targetSymbol!!.key))
    }

    fun `test O a run reports the callable it is working on`() {
        val seen = mutableListOf<String>()
        val collector = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
            .launch {
                service.progress.collect { it?.currentCallable?.let(seen::add) }
            }
        analyze("void purchase()")
        // Let the collector catch up before cutting it off: a StateFlow
        // conflates, and the value this test is about may not have been
        // delivered yet.
        runBlocking {
            withTimeoutOrNull(10_000) {
                while (!seen.contains("purchase()")) delay(10)
            }
        }
        collector.cancel()

        assertTrue(
            "a run that looks stuck must be able to say what it is stuck on: $seen",
            seen.contains("purchase()"),
        )
        assertTrue(
            "the budget is published so the bar has a denominator",
            service.progress.value!!.nodeBudget > 0,
        )
    }

    fun `test M analyze from here promotes a project-local target`() {
        val result = analyze("void purchase()")
        val save = result.rootFrame!!.events.first { it.targetSymbol?.displayName == "save()" }
        assertEquals(
            "the run recorded that this target has a body worth analyzing",
            "true",
            save.metadata[FlowMetadata.ANALYZABLE],
        )
        assertNotNull(save.targetLocation)
    }

    fun `test N a target with nothing to analyze is not offered`() {
        val result = analyze("void purchase()")
        val events = result.rootFrame!!.events

        val external = events.first { it.targetSymbol?.displayName == "trim()" }
        assertNull(
            "an external call offers nothing to analyze",
            external.metadata[FlowMetadata.ANALYZABLE],
        )
        val abstract = events.first { it.targetSymbol?.displayName == "help()" }
        assertNull(
            "an interface method has no body, so promoting it would fail rather than analyze",
            abstract.metadata[FlowMetadata.ANALYZABLE],
        )
    }

    override fun tearDown() {
        try {
            FlowRunHooks.reset()
            service.cancelActive()
            flows.loadState(FlowLensFlows.State())
            recents.clear()
        } finally {
            super.tearDown()
        }
    }
}
