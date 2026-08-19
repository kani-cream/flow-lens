package com.kanicream.flowlens.analysis.kotlin

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.kanicream.flowlens.analysis.ExtractedCall
import com.kanicream.flowlens.analysis.ExtractedCallback
import com.kanicream.flowlens.analysis.FlowItem
import com.kanicream.flowlens.core.model.ExecutionMode
import com.kanicream.flowlens.core.model.OrderingStatus
import com.kanicream.flowlens.testutil.RealJdkProjectDescriptor
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Kotlin callback bodies and their timing (`V0.5_SPEC.md` §4.1, cases B–E and S).
 *
 * Kotlin can justify "runs in place" more often than Java can, because `inline`
 * is a language guarantee rather than a claim about an API's intent. Every case
 * here checks that the answer on the map is one the analyzer could justify.
 *
 * The light fixture has no kotlinx-coroutines and no Kotlin stdlib sources, so
 * the recognized declarations are written out in the fixture under their real
 * fully qualified names. That is what the timing lookup keys on.
 */
class KotlinCallbackTest : LightJavaCodeInsightFixtureTestCase() {

    private val analyzer = KotlinFlowAnalyzer()

    override fun getProjectDescriptor(): LightProjectDescriptor = RealJdkProjectDescriptor.INSTANCE

    private fun configure(body: String, decls: String = "") = myFixture.configureByText(
        "sample.kt",
        """
        fun run() { $body }
        fun charge() { }
        fun audit() { }
        fun helper(block: () -> Unit) { }
        inline fun inlined(block: () -> Unit) { block() }
        inline fun escaping(noinline block: () -> Unit) { }
        inline fun elsewhere(crossinline block: () -> Unit) { store { block() } }
        inline fun mixed(first: () -> Unit, noinline second: () -> Unit) { first() }
        fun store(block: () -> Unit) { }
        suspend fun save() { }
        $decls
        """.trimIndent(),
    )

    private fun itemsOf(body: String, decls: String = ""): List<FlowItem> {
        val file = configure(body, decls)
        val function = PsiTreeUtil.findChildrenOfType(file, KtNamedFunction::class.java)
            .first { it.name == "run" }
        return analyzer.extractDirectFlow(function).items
    }

    private fun shape(items: List<FlowItem>): List<String> = items.map {
        when (it) {
            is ExtractedCall -> it.calleeShortName
            is ExtractedCallback -> "callback:${it.receiverShortName}:${it.executionMode}"
            else -> it.javaClass.simpleName
        }
    }

    /** Stand-ins for the coroutine builders, under the names the timing list uses. */
    private val coroutines = """
        package kotlinx.coroutines
        fun launch(block: () -> Unit) { }
        fun withContext(context: Int, block: () -> Unit) { }
    """.trimIndent()

    private fun withCoroutines(body: String): List<FlowItem> {
        myFixture.addFileToProject("coroutines.kt", coroutines)
        return itemsOf(body, decls = "import kotlinx.coroutines.launch\nimport kotlinx.coroutines.withContext")
    }

    fun `test B launch starts concurrent work, so its ordering is unspecified`() {
        val items = withCoroutines("launch { charge() }")
        val callback = items.filterIsInstance<ExtractedCallback>().single()
        assertEquals(ExecutionMode.ASYNC, callback.executionMode)
        assertEquals(OrderingStatus.UNSPECIFIED, callback.orderingStatus)
    }

    fun `test C a lambda passed to an inline function cannot escape, so it runs in place`() {
        val items = itemsOf("inlined { charge() }")
        val callback = items.filterIsInstance<ExtractedCallback>().single()
        assertEquals(
            "`inline` without `noinline` is a language guarantee, not a guess",
            ExecutionMode.SYNC,
            callback.executionMode,
        )
        assertEquals(OrderingStatus.DETERMINISTIC, callback.orderingStatus)
    }

    fun `test C noinline removes the guarantee, so the timing is undetermined`() {
        val items = itemsOf("escaping { charge() }")
        assertEquals(
            ExecutionMode.UNKNOWN,
            items.filterIsInstance<ExtractedCallback>().single().executionMode,
        )
    }

    fun `test C crossinline allows another execution context, so nothing is promised`() {
        // `crossinline` exists precisely so the lambda can be invoked from a local
        // object, a nested function, or another thread — which is the case where
        // "runs in place" would be a false claim.
        val items = itemsOf("elsewhere { charge() }")
        assertEquals(
            ExecutionMode.UNKNOWN,
            items.filterIsInstance<ExtractedCallback>().single().executionMode,
        )
    }

    fun `test one noinline parameter does not cost the other lambda its guarantee`() {
        val items = itemsOf("mixed({ charge() }, { audit() })")
        val callbacks = items.filterIsInstance<ExtractedCallback>()
        assertEquals(2, callbacks.size)
        assertEquals(
            "the promise is made per parameter, so it is read per parameter",
            listOf(ExecutionMode.SYNC, ExecutionMode.UNKNOWN),
            callbacks.map { it.executionMode },
        )
    }

    fun `test named arguments are matched by name, not by position`() {
        val items = itemsOf("mixed(second = { audit() }, first = { charge() })")
        assertEquals(
            listOf(ExecutionMode.UNKNOWN, ExecutionMode.SYNC),
            items.filterIsInstance<ExtractedCallback>().map { it.executionMode },
        )
    }

    fun `test D withContext runs the block before the next statement`() {
        val items = withCoroutines("withContext(1) { charge() }\naudit()")
        val callback = items.filterIsInstance<ExtractedCallback>().single()
        assertEquals(
            "it may change thread, but the map is about order, not about threads",
            ExecutionMode.SYNC,
            callback.executionMode,
        )
        assertEquals(OrderingStatus.DETERMINISTIC, callback.orderingStatus)
    }

    fun `test E a call to a suspend function is an ordinary call`() {
        val items = itemsOf("save()")
        assertEquals(listOf("save"), shape(items))
        assertTrue(
            "suspending is about how it waits, not about handing a body elsewhere",
            items.filterIsInstance<ExtractedCallback>().isEmpty(),
        )
    }

    fun `test a trailing lambda is a callback like any argument`() {
        val items = itemsOf("helper { charge() }")
        assertEquals(listOf("helper", "callback:helper:UNKNOWN"), shape(items))
    }

    fun `test the body is not walked where it is written`() {
        val items = itemsOf("helper { charge() }\naudit()")
        assertEquals(listOf("helper", "callback:helper:UNKNOWN", "audit"), shape(items))
    }

    fun `test S a nested lambda is its own frame with its own timing`() {
        val file = configure("helper { inlined { charge() } }")
        val outer = PsiTreeUtil.findChildrenOfType(file, KtLambdaExpression::class.java).first()
        val inner = analyzer.extractDirectFlow(outer).items
        assertEquals(listOf("inlined", "callback:inlined:SYNC"), shape(inner))
    }

    fun `test N a lambda stored in a variable is not invented as a callback`() {
        val items = itemsOf("val r = { charge() }\naudit()")
        assertTrue(items.filterIsInstance<ExtractedCallback>().isEmpty())
        assertEquals(listOf("audit"), shape(items))
    }
}
