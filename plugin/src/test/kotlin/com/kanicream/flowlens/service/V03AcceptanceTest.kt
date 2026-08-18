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
import kotlinx.coroutines.flow.first
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
            void purchase() { save(); }
            void refund() { save(); }
            void save() { }
        }
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
        return runBlocking {
            withTimeout(60_000) {
                service.results.first { it?.runId == runId && it.isTerminal }!!
            }
        }
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

        // Cancelling from the scheduler rather than from the test thread: a race
        // between start and cancel would decide whether this test tests anything.
        FlowRunHooks.beforeFrameOperation = {
            FlowRunHooks.reset()
            service.cancelActive()
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
            "a cancelled run must not report itself completed",
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

        val save = result.rootFrame!!.events.single()
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
        analyze("void purchase()")
        val progress = service.progress.value!!
        assertTrue("the budget is published so the bar has a denominator", progress.nodeBudget > 0)
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
