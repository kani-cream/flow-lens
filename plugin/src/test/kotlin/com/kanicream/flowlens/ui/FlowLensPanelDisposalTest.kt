package com.kanicream.flowlens.ui

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.kanicream.flowlens.service.FlowAnalysisService
import com.kanicream.flowlens.testutil.RealJdkProjectDescriptor

/**
 * Tool-window lifecycle (guardrails §16): closing the content must dispose the
 * controller and its collectors, and an analysis started afterwards must not
 * touch the disposed view.
 */
class FlowLensPanelDisposalTest : LightJavaCodeInsightFixtureTestCase() {

    override fun getProjectDescriptor(): LightProjectDescriptor = RealJdkProjectDescriptor.INSTANCE

    fun `test disposing the panel disposes its controller`() {
        val panel = FlowLensPanel(project)
        val controller = panel.controller
        assertFalse(Disposer.isDisposed(controller))
        Disposer.dispose(panel)
        assertTrue("the controller must dispose with the tool-window content", Disposer.isDisposed(controller))
    }

    fun `test analysis after disposal does not fail`() {
        val panel = FlowLensPanel(project)
        Disposer.dispose(panel)

        myFixture.configureByText(
            "AfterDisposal.java",
            """
            public class AfterDisposal {
                void run() { helper(); }
                void helper() { }
            }
            """.trimIndent(),
        )
        // A run started after the view is gone must be harmless: the service owns
        // the run, the disposed collectors simply no longer observe it.
        FlowAnalysisService.getInstance(project)
            .startAnalysis(myFixture.file.virtualFile, myFixture.file.text.indexOf("void run()") + 6)
        FlowAnalysisService.getInstance(project).cancelActive()
    }

    fun `test a second panel can be created after the first is disposed`() {
        val first = FlowLensPanel(project)
        Disposer.dispose(first)
        val second = FlowLensPanel(project)
        assertFalse(Disposer.isDisposed(second))
        Disposer.dispose(second)
    }
}
