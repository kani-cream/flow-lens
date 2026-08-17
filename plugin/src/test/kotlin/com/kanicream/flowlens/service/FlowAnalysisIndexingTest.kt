package com.kanicream.flowlens.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.kanicream.flowlens.core.model.FlowProgressStage
import com.kanicream.flowlens.core.model.FlowResultStatus
import com.kanicream.flowlens.core.model.ResolutionStatus
import com.kanicream.flowlens.testutil.RealJdkProjectDescriptor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Indexing behavior (`V0.1_SPEC.md` §15): while indexes are unavailable Flow Lens
 * waits and says so, instead of turning a temporary IDE state into a map full of
 * unresolved calls.
 */
class FlowAnalysisIndexingTest : LightJavaCodeInsightFixtureTestCase() {

    override fun getProjectDescriptor(): LightProjectDescriptor = RealJdkProjectDescriptor.INSTANCE

    override fun runInDispatchThread(): Boolean = false

    private val service: FlowAnalysisService get() = FlowAnalysisService.getInstance(project)

    fun `test analysis waits for indexes and then resolves normally`() {
        ApplicationManager.getApplication().invokeAndWait {
            myFixture.configureByText(
                "Indexed.java",
                """
                public class Indexed {
                    void run() { helper(); }
                    void helper() { }
                }
                """.trimIndent(),
            )
        }
        val file = myFixture.file.virtualFile
        val offset = myFixture.file.text.indexOf("void run()") + 6

        val dumbToken = DumbModeTestUtils.startEternalDumbModeTask(project)
        try {
            service.startAnalysis(file, offset)

            // While indexing, the run must not reach a terminal state at all.
            val terminalDuringIndexing = runBlocking {
                withTimeoutOrNull(2_000) { service.results.first { it != null && it.isTerminal } }
            }
            assertNull(
                "indexing must not produce a terminal result full of unresolved calls",
                terminalDuringIndexing,
            )
            val stage = service.progress.value?.stage
            assertEquals(FlowProgressStage.WAITING_FOR_INDEXES, stage)
        } finally {
            DumbModeTestUtils.endEternalDumbModeTask(dumbToken)
        }

        val result = runBlocking {
            withTimeout(60_000) { service.results.first { it != null && it.isTerminal }!! }
        }
        assertEquals(FlowResultStatus.COMPLETED, result.status)
        val call = result.rootFrame!!.events.single()
        assertEquals(
            "after indexing the same call resolves normally",
            ResolutionStatus.PROJECT_LOCAL,
            call.resolutionStatus,
        )
    }

    fun `test the waiting stage is not published when indexes are ready`() {
        ApplicationManager.getApplication().invokeAndWait {
            myFixture.configureByText(
                "Ready.java",
                """
                public class Ready {
                    void run() { helper(); }
                    void helper() { }
                }
                """.trimIndent(),
            )
        }
        val stages = mutableListOf<FlowProgressStage>()
        service.startAnalysis(
            myFixture.file.virtualFile,
            myFixture.file.text.indexOf("void run()") + 6,
        )
        runBlocking {
            withTimeout(60_000) {
                service.progress.first { progress ->
                    progress?.let { stages += it.stage }
                    progress?.isTerminal == true
                }
            }
        }
        assertFalse(
            "a waiting stage must describe real waiting, not every run",
            stages.contains(FlowProgressStage.WAITING_FOR_INDEXES),
        )
    }
}
