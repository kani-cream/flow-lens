package com.kanicream.flowlens.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.kanicream.flowlens.core.model.FlowAnalysisResult
import com.kanicream.flowlens.core.model.FlowLimits
import com.kanicream.flowlens.core.model.FlowNode
import com.kanicream.flowlens.core.model.FlowNodeKind
import com.kanicream.flowlens.testutil.AnalysisTestSupport
import com.kanicream.flowlens.testutil.RealJdkProjectDescriptor
import com.kanicream.flowlens.ui.status.FlowStatusModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * End-to-end coverage of `V1.1_HIDE_EXTERNAL_SPEC.md` through the real analysis
 * service: with the option on, external calls that were not entered leave the
 * map, the removal is counted and disclosed, and a call handed a lambda keeps
 * its card so the reader's own body stays where it belongs.
 */
class V11HideExternalAcceptanceTest : LightJavaCodeInsightFixtureTestCase() {

    override fun getProjectDescriptor(): LightProjectDescriptor = RealJdkProjectDescriptor.INSTANCE

    override fun runInDispatchThread(): Boolean = false

    override fun tearDown() {
        try {
            AnalysisTestSupport.quiesceAnalysis(project)
        } finally {
            super.tearDown()
        }
    }

    private val service: FlowAnalysisService get() = FlowAnalysisService.getInstance(project)

    private val hiding = FlowLimits(hideExternalCalls = true)

    private fun analyze(body: String, limits: FlowLimits = hiding): FlowAnalysisResult {
        val text = """
            import java.util.List;
            import java.util.ArrayList;

            public class Sample {
                List<String> items = new ArrayList<>();
                StringBuilder text = new StringBuilder();
                void run() { $body }
                void mine() { }
            }
        """.trimIndent()
        ApplicationManager.getApplication().invokeAndWait {
            myFixture.configureByText("Sample.java", text)
        }
        val signature = "void run()"
        val offset = myFixture.file.text.indexOf(signature) + signature.length - 1
        service.startAnalysis(myFixture.file.virtualFile, offset, limits)
        return runBlocking {
            withTimeout(60_000) { service.results.first { it != null && it.isTerminal }!! }
        }
    }

    private fun rootEvents(result: FlowAnalysisResult) = result.rootFrame!!.events

    private fun shape(events: List<FlowNode>) = events.map {
        it.targetSymbol?.displayName ?: it.kind.name
    }

    fun `test hidden external calls leave the map and are counted`() {
        val result = analyze("text.append(1); text.append(2); mine();")
        assertEquals(listOf("mine()"), shape(rootEvents(result)))
        assertEquals(2, result.hiddenExternalCount)
    }

    fun `test a run that would have grouped is simply gone`() {
        val result = analyze("text.append(1); text.append(2); text.append(3); mine();")
        assertEquals(listOf("mine()"), shape(rootEvents(result)))
        assertEquals(3, result.hiddenExternalCount)
        assertTrue(
            "nothing left to collapse into a group",
            rootEvents(result).none { it.kind == FlowNodeKind.EXTERNAL_GROUP },
        )
    }

    fun `test a call handed a lambda keeps its card`() {
        val result = analyze("items.forEach((s) -> mine());")
        val events = rootEvents(result)
        assertTrue(
            "the call the body was handed to stays: ${shape(events)}",
            events.any { it.kind == FlowNodeKind.CALL && it.targetSymbol?.displayName == "forEach()" },
        )
        assertTrue(
            "the reader's own body stays with it: ${shape(events)}",
            events.any { it.kind == FlowNodeKind.CALLBACK },
        )
        assertEquals(0, result.hiddenExternalCount)
    }

    fun `test hiding applies inside a branch`() {
        val result = analyze("if (items.isEmpty()) { text.append(1); } mine();")
        val condition = rootEvents(result).first { it.kind == FlowNodeKind.CONDITION }
        assertTrue(
            "the branch keeps no external call",
            condition.branches.all { branch -> branch.events.none { it.targetSymbol?.displayName == "append()" } },
        )
        // Two, not one: the condition's own isEmpty() is an external call too,
        // and hiding is about the call, not about where it sits.
        assertEquals(2, result.hiddenExternalCount)
    }

    fun `test off by default nothing changes`() {
        val result = analyze("text.append(1); text.append(2); mine();", limits = FlowLimits())
        assertEquals(listOf("append()", "append()", "mine()"), shape(rootEvents(result)))
        assertEquals(0, result.hiddenExternalCount)
    }

    fun `test the status area discloses the hidden count`() {
        val result = analyze("text.append(1); text.append(2); mine();")
        val state = FlowStatusModel.stateOf(null, result)
        val reason = state.stopReasons.single { it.firstNode == null }
        assertEquals("there is no node to select, only a disclosure", 2, reason.count)
        assertTrue(reason.text.contains("2"))
    }
}
