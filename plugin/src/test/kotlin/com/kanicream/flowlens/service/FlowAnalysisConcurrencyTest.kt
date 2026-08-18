package com.kanicream.flowlens.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.kanicream.flowlens.core.model.FlowAnalysisResult
import com.kanicream.flowlens.core.model.FlowLimits
import com.kanicream.flowlens.core.model.FlowResultStatus
import com.kanicream.flowlens.testutil.RealJdkProjectDescriptor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Read-action granularity, source mutation, and cancellation
 * (`TEST_STRATEGY.md` §6). The run scheduler is instrumented so these assert
 * structural behavior at known points instead of racing against wall-clock time.
 *
 * The test body runs off the EDT so it can take write actions while an analysis
 * is in flight — the very situation a whole-flow read action would block.
 */
class FlowAnalysisConcurrencyTest : LightJavaCodeInsightFixtureTestCase() {

    override fun getProjectDescriptor(): LightProjectDescriptor = RealJdkProjectDescriptor.INSTANCE

    override fun runInDispatchThread(): Boolean = false

    private val service: FlowAnalysisService get() = FlowAnalysisService.getInstance(project)

    override fun tearDown() {
        try {
            FlowRunHooks.reset()
            service.cancelActive()
        } finally {
            super.tearDown()
        }
    }

    private val deepSample = """
        public class Deep {
            void run() { d1(); d1b(); }
            void d1() { d2(); }
            void d1b() { d2(); }
            void d2() { d3(); }
            void d3() { }
        }
    """.trimIndent()

    private fun configureDeepSample() {
        ApplicationManager.getApplication().invokeAndWait {
            myFixture.configureByText("Deep.java", deepSample)
        }
    }

    private fun caretAtRun(): Int = myFixture.file.text.indexOf("void run()") + 6

    private fun awaitTerminal(): FlowAnalysisResult = runBlocking {
        withTimeout(60_000) { service.results.first { it != null && it.isTerminal }!! }
    }

    fun `test each frame runs in its own bounded operation with the read lock released between them`() {
        configureDeepSample()
        val operations = CopyOnWriteArrayList<FlowRunHooks.FrameOperation>()
        val readLockHeld = CopyOnWriteArrayList<Boolean>()
        FlowRunHooks.beforeFrameOperation = { operation ->
            operations += operation
            readLockHeld += ApplicationManager.getApplication().isReadAccessAllowed
        }

        service.startAnalysis(myFixture.file.virtualFile, caretAtRun(), FlowLimits(maxDepth = 3))
        val result = awaitTerminal()

        assertEquals(FlowResultStatus.COMPLETED, result.status)
        // One operation for the root extraction plus one per analyzed frame: the
        // traversal is never a single whole-flow read action.
        assertEquals(result.frames.size + 1, operations.size)
        assertTrue("more than one bounded operation is required", operations.size > 2)
        assertFalse(
            "the read lock must be released between frames",
            readLockHeld.any { it },
        )
        // Depth order: a frame is only scheduled after shallower frames.
        assertEquals(operations.map { it.depth }.sorted(), operations.map { it.depth })
    }

    fun `test source modification during analysis marks the result stale`() {
        configureDeepSample()
        FlowRunHooks.beforeFrameOperation = { operation ->
            // Edit exactly once, after the root frame has been extracted, so the
            // next bounded operation observes a changed source revision.
            if (operation.index == 1) {
                FlowRunHooks.reset()
                ApplicationManager.getApplication().invokeAndWait {
                    WriteCommandAction.runWriteCommandAction(project) {
                        myFixture.editor.document.insertString(0, "// edited\n")
                    }
                }
            }
        }

        service.startAnalysis(myFixture.file.virtualFile, caretAtRun())
        val result = awaitTerminal()

        assertEquals(FlowResultStatus.STALE, result.status)
        assertNotNull("partial content stays available for inspection", result.rootFrame)
    }

    fun `test cancellation mid traversal keeps partial navigable results`() {
        configureDeepSample()
        FlowRunHooks.beforeFrameOperation = { operation ->
            if (operation.index == 2) {
                FlowRunHooks.reset()
                service.cancelActive()
            }
        }

        service.startAnalysis(myFixture.file.virtualFile, caretAtRun())
        val result = awaitTerminal()

        assertEquals(FlowResultStatus.CANCELLED, result.status)
        val root = result.rootFrame!!
        assertTrue("completed nodes are retained", root.events.isNotEmpty())
        assertTrue(
            "retained nodes stay navigable",
            root.events.all { it.callSiteLocation != null },
        )
    }

    fun `test a write action can proceed while an analysis is running`() {
        configureDeepSample()
        var writeCompleted = false
        FlowRunHooks.beforeFrameOperation = { operation ->
            if (operation.index == 1) {
                FlowRunHooks.reset()
                ApplicationManager.getApplication().invokeAndWait {
                    WriteCommandAction.runWriteCommandAction(project) {
                        myFixture.editor.document.insertString(0, "// concurrent\n")
                        writeCompleted = true
                    }
                }
            }
        }

        service.startAnalysis(myFixture.file.virtualFile, caretAtRun())
        awaitTerminal()

        assertTrue("a write action must not wait for the whole traversal", writeCompleted)
    }

    fun `test restarting analysis rapidly never interleaves run generations`() {
        configureDeepSample()
        val offset = caretAtRun()
        val file = myFixture.file.virtualFile
        repeat(5) { service.startAnalysis(file, offset) }
        val lastRunId = service.startAnalysis(file, offset)
        val result = awaitTerminal()
        assertEquals(lastRunId, result.runId)
        assertTrue(result.status == FlowResultStatus.COMPLETED || result.status == FlowResultStatus.CANCELLED)
    }
}
