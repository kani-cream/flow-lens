package com.kanicream.flowlens.analysis.go

import com.goide.psi.GoFunctionLit
import com.goide.psi.GoFunctionOrMethodDeclaration
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.kanicream.flowlens.analysis.ExtractedCall
import com.kanicream.flowlens.analysis.ExtractedCallback
import com.kanicream.flowlens.analysis.FlowItem
import com.kanicream.flowlens.core.model.ExecutionMode
import com.kanicream.flowlens.core.model.OrderingStatus

/**
 * Go closure bodies and their timing (`V0.5_SPEC.md` §4.2, cases H and I).
 *
 * Go is the one language that states the timing itself: `go` and `defer` are
 * keywords, so nothing has to be looked up or guessed.
 */
class GoCallbackTest : BasePlatformTestCase() {

    private val analyzer = GoFlowAnalyzer()

    private fun configure(body: String) = myFixture.configureByText(
        "sample.go",
        """
        package sample

        func run() {
        $body
        }

        func charge() { }
        func cleanup() { }
        func audit() { }
        func helper(f func()) { }
        """.trimIndent(),
    )

    private fun itemsOf(body: String): List<FlowItem> {
        val file = configure(body)
        val function = PsiTreeUtil.findChildrenOfType(file, GoFunctionOrMethodDeclaration::class.java)
            .first { it.name == "run" }
        return analyzer.extractDirectFlow(function).items
    }

    private fun shape(items: List<FlowItem>): List<String> = items.map {
        when (it) {
            is ExtractedCall -> it.calleeShortName
            is ExtractedCallback -> "callback:${it.receiverShortName ?: "in-place"}:${it.executionMode}"
            else -> it.javaClass.simpleName
        }
    }

    fun `test H a goroutine body is visible, running concurrently`() {
        val items = itemsOf("go func() { charge() }()")
        val callback = items.filterIsInstance<ExtractedCallback>().single()
        assertEquals(ExecutionMode.GOROUTINE, callback.executionMode)
        assertEquals(OrderingStatus.UNSPECIFIED, callback.orderingStatus)
    }

    fun `test H the goroutine body is not walked where it is written`() {
        val items = itemsOf("go func() { charge() }()\naudit()")
        assertEquals(listOf("callback:in-place:GOROUTINE", "audit"), shape(items))
        assertFalse("charge() belongs to the closure's own frame", shape(items).contains("charge"))
    }

    fun `test H the goroutine body is reachable as its own frame`() {
        val file = configure("go func() { charge() }()")
        val literal = PsiTreeUtil.findChildOfType(file, GoFunctionLit::class.java)!!
        assertTrue(analyzer.supportsDeclaration(literal))
        assertTrue(analyzer.hasAnalyzableBody(literal))
        assertEquals(listOf("charge"), shape(analyzer.extractDirectFlow(literal).items))
    }

    fun `test I a deferred body runs on the way out`() {
        val items = itemsOf("defer func() { cleanup() }()")
        val callback = items.filterIsInstance<ExtractedCallback>().single()
        assertEquals(ExecutionMode.DEFERRED, callback.executionMode)
        assertEquals(OrderingStatus.UNSPECIFIED, callback.orderingStatus)
    }

    fun `test a closure handed to an ordinary function has undetermined timing`() {
        val items = itemsOf("helper(func() { charge() })")
        val callback = items.filterIsInstance<ExtractedCallback>().single()
        assertEquals("helper", callback.receiverShortName)
        assertEquals(ExecutionMode.UNKNOWN, callback.executionMode)
    }

    fun `test an immediately invoked closure runs right here`() {
        val items = itemsOf("func() { charge() }()")
        val callback = items.filterIsInstance<ExtractedCallback>().single()
        assertEquals(ExecutionMode.SYNC, callback.executionMode)
        assertEquals(OrderingStatus.DETERMINISTIC, callback.orderingStatus)
    }

    fun `test an invoked literal is one event, not a call plus its body`() {
        // Regression: the call and the body are the same thing here, and there is
        // no declaration to resolve — so the extra call card could only ever
        // report itself as unresolved, beside a callback card that had the body.
        assertEquals(listOf("callback:in-place:GOROUTINE"), shape(itemsOf("go func() { charge() }()")))
        assertEquals(listOf("callback:in-place:DEFERRED"), shape(itemsOf("defer func() { cleanup() }()")))
        assertEquals(listOf("callback:in-place:SYNC"), shape(itemsOf("func() { charge() }()")))
    }

    fun `test a literal handed to a call is still named after that call`() {
        assertEquals(
            listOf("helper", "callback:helper:UNKNOWN"),
            shape(itemsOf("helper(func() { charge() })")),
        )
    }

    fun `test N a closure stored in a variable is not invented as a callback`() {
        val items = itemsOf("f := func() { charge() }\naudit()")
        assertTrue(items.filterIsInstance<ExtractedCallback>().isEmpty())
        assertEquals(listOf("audit"), shape(items))
    }
}
