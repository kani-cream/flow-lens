package com.kanicream.flowlens.analysis.java

import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.kanicream.flowlens.analysis.ExtractedCall
import com.kanicream.flowlens.analysis.ExtractedCallback
import com.kanicream.flowlens.analysis.FlowItem
import com.kanicream.flowlens.core.model.ExecutionMode
import com.kanicream.flowlens.core.model.OrderingStatus
import com.kanicream.flowlens.testutil.RealJdkProjectDescriptor

/**
 * Java callback bodies and their timing (`V0.5_SPEC.md` §4, cases A, F, G, J, N).
 *
 * The point of every case here is the same: a body handed to a call is on the
 * map, and what the map says about when it runs is something the analyzer can
 * justify.
 */
class JavaCallbackTest : LightJavaCodeInsightFixtureTestCase() {

    private val analyzer = JavaFlowAnalyzer()

    override fun getProjectDescriptor(): LightProjectDescriptor = RealJdkProjectDescriptor.INSTANCE

    private fun itemsOf(body: String, fields: String = ""): List<FlowItem> {
        val file = myFixture.configureByText(
            "Sample.java",
            """
            import java.util.List;
            import java.util.concurrent.ExecutorService;

            public class Sample {
                ExecutorService executor;
                List<String> items;
                $fields
                void run() { $body }
                void charge() { }
                void audit() { }
                void helper(Runnable task) { }
            }
            """.trimIndent(),
        )
        val method = PsiTreeUtil.findChildrenOfType(file, PsiMethod::class.java).first { it.name == "run" }
        return analyzer.extractDirectFlow(method).items
    }

    private fun shape(items: List<FlowItem>): List<String> = items.map {
        when (it) {
            is ExtractedCall -> it.calleeShortName
            is ExtractedCallback -> "callback:${it.receiverShortName}:${it.executionMode}"
            else -> it.javaClass.simpleName
        }
    }

    fun `test A a lambda given to an executor is on the map, running later`() {
        val items = itemsOf("executor.submit(() -> charge());")
        assertEquals(listOf("submit", "callback:submit:ASYNC"), shape(items))

        val callback = items.filterIsInstance<ExtractedCallback>().single()
        assertEquals(
            "an asynchronous body must not be presented as the next step",
            OrderingStatus.UNSPECIFIED,
            callback.orderingStatus,
        )
    }

    fun `test F a lambda the JDK runs in place is part of the flow`() {
        val items = itemsOf("items.forEach(s -> charge());")
        assertEquals(listOf("forEach", "callback:forEach:SYNC"), shape(items))
        assertEquals(
            OrderingStatus.DETERMINISTIC,
            items.filterIsInstance<ExtractedCallback>().single().orderingStatus,
        )
    }

    fun `test G a lambda given to an unrecognized method says the timing is unknown`() {
        val items = itemsOf("helper(() -> charge());")
        val callback = items.filterIsInstance<ExtractedCallback>().single()
        assertEquals(
            "guessing here would trade an honest omission for a confident error",
            ExecutionMode.UNKNOWN,
            callback.executionMode,
        )
        assertEquals(OrderingStatus.UNSPECIFIED, callback.orderingStatus)
    }

    fun `test J two lambdas in one call become two events, in argument order`() {
        val items = itemsOf(
            "java.util.Optional.of(1).map(x -> charge()).ifPresent(x -> audit());",
        )
        val callbacks = items.filterIsInstance<ExtractedCallback>()
        assertEquals(listOf("map", "ifPresent"), callbacks.map { it.receiverShortName })
    }

    fun `test N a lambda that is not passed to a call is not invented as a callback`() {
        val items = itemsOf("Runnable r = () -> charge(); audit();", fields = "")
        assertTrue(
            "its invocation site is elsewhere; following it would be reverse analysis",
            items.filterIsInstance<ExtractedCallback>().isEmpty(),
        )
        assertEquals(listOf("audit"), shape(items))
    }

    fun `test the body is not walked where it is written`() {
        // charge() belongs to the lambda's own frame, not to run()'s sequence:
        // putting it here would claim it runs at this point.
        val items = itemsOf("executor.submit(() -> charge()); audit();")
        assertEquals(
            listOf("submit", "callback:submit:ASYNC", "audit"),
            shape(items),
        )
    }

    fun `test O the step after a call still follows the call`() {
        val items = itemsOf("executor.submit(() -> charge()); audit();")
        // The callback sits between them in source order, but audit() is the next
        // synchronous step; the canvas draws the callback's connector dashed.
        val callback = items.filterIsInstance<ExtractedCallback>().single()
        assertEquals(OrderingStatus.UNSPECIFIED, callback.orderingStatus)
        assertEquals(
            OrderingStatus.DETERMINISTIC,
            items.filterIsInstance<ExtractedCall>().first { it.calleeShortName == "audit" }.orderingStatus,
        )
    }
}
