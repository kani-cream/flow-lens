package com.kanicream.flowlens.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.kanicream.flowlens.FlowLensBundle
import com.kanicream.flowlens.core.model.ExecutionMode
import com.kanicream.flowlens.core.model.FlowAnalysisResult
import com.kanicream.flowlens.core.model.FlowLimits
import com.kanicream.flowlens.core.model.FlowNode
import com.kanicream.flowlens.core.model.FlowNodeKind
import com.kanicream.flowlens.core.model.OrderingStatus
import com.kanicream.flowlens.testutil.RealJdkProjectDescriptor
import com.kanicream.flowlens.ui.canvas.CanvasViewModelBuilder
import com.kanicream.flowlens.ui.details.FlowDetailsModel
import com.kanicream.flowlens.ui.status.FlowStatusModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * End-to-end coverage of the `V0.5_SPEC.md` §7 acceptance cases through the real
 * analysis service: a body handed to a call becomes a frame, and what the map
 * says about when it runs survives extraction, the run engine, the model, the
 * canvas, the details panel, and the status summary as one piece.
 *
 * The per-language timing rules themselves live with their analyzers
 * (`JavaCallbackTest`, `KotlinCallbackTest`, `GoCallbackTest`), which is where
 * cases B–I are.
 */
class V05AcceptanceTest : LightJavaCodeInsightFixtureTestCase() {

    override fun getProjectDescriptor(): LightProjectDescriptor = RealJdkProjectDescriptor.INSTANCE

    override fun runInDispatchThread(): Boolean = false

    private val service: FlowAnalysisService get() = FlowAnalysisService.getInstance(project)

    private fun analyze(
        body: String,
        members: String = "",
        limits: FlowLimits = FlowLimits(),
    ): FlowAnalysisResult {
        val text = """
            import java.util.List;
            import java.util.concurrent.ExecutorService;

            public class Sample {
                ExecutorService executor;
                List<String> items;
                void run() { $body }
                void charge() { }
                void audit() { }
                void save() { }
                void helper(Runnable task) { }
                $members
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

    private fun names(events: List<FlowNode>) =
        events.map { it.targetSymbol?.displayName ?: it.kind.name }

    private fun allNodes(result: FlowAnalysisResult): List<FlowNode> {
        val out = mutableListOf<FlowNode>()
        fun visit(nodes: List<FlowNode>) {
            for (node in nodes) {
                out += node
                node.branches.forEach { visit(it.events) }
            }
        }
        result.frames.values.forEach { visit(it.events) }
        return out
    }

    private fun bodyOf(result: FlowAnalysisResult, node: FlowNode): List<FlowNode>? =
        node.targetFrameId?.let(result::frame)?.events

    private fun callbacks(result: FlowAnalysisResult) =
        allNodes(result).filter { it.kind == FlowNodeKind.CALLBACK }

    // ---- A, O: the body is on the map, and the flow still continues past the call ----

    fun `test A a body handed to a call becomes a frame of its own`() {
        val result = analyze("executor.submit(() -> charge()); save();")
        val events = rootEvents(result)
        assertEquals(listOf("submit()", "{ } → submit()", "save()"), names(events))

        val callback = events[1]
        assertEquals(ExecutionMode.ASYNC, callback.executionMode)
        assertEquals(OrderingStatus.UNSPECIFIED, callback.orderingStatus)
        assertEquals(
            "the body must be enterable, or nothing was gained by showing it",
            listOf("charge()"),
            bodyOf(result, callback)?.let(::names),
        )
    }

    fun `test O the next synchronous step follows the call, not the callback`() {
        val result = analyze("executor.submit(() -> charge()); save();")
        val events = rootEvents(result)
        // save() sits after the callback in source order because the map has to
        // draw it somewhere, but it is the step that follows submit(). Asserting
        // only the connector style left the question of what precedes save()
        // unasked, and the answer was wrong.
        assertEquals(
            "the body is attached to the call it was handed to",
            events[0].id,
            events[1].attachedTo,
        )
        assertNull("save() is a link in the chain, not a body hung off one", events[2].attachedTo)
        assertEquals(OrderingStatus.DETERMINISTIC, events[2].orderingStatus)
        assertEquals(OrderingStatus.UNSPECIFIED, events[1].orderingStatus)

        val cards = CanvasViewModelBuilder.build(result, emptySet())!!.cards
        assertTrue("an asynchronous body must not claim to be the next step", cards[1].dashedIncomingConnector)
        assertFalse(cards[2].dashedIncomingConnector)
        assertTrue("the body hangs off the call", cards[1].attached)
        assertFalse(cards[2].attached)
        assertTrue(
            "an attached body is set in, which is what leaves the connector room to pass it",
            cards[1].bounds.x > cards[0].bounds.x,
        )
        assertEquals(
            "and what follows the call is back on the call's own column",
            cards[0].bounds.x,
            cards[2].bounds.x,
        )
    }

    fun `test J two bodies handed to ONE call become two frames, in argument order`() {
        // The case is two lambdas in one call. Chaining two calls with one lambda
        // each looked like it and tested nothing about telling them apart.
        val result = analyze(
            "pair(() -> charge(), () -> audit()); save();",
            members = "void pair(Runnable first, Runnable second) { }",
        )
        val callbacks = callbacks(result)
        assertEquals(2, callbacks.size)
        assertEquals(listOf("charge()"), bodyOf(result, callbacks[0])?.let(::names))
        assertEquals(listOf("audit()"), bodyOf(result, callbacks[1])?.let(::names))

        val names = callbacks.map { it.targetSymbol!!.displayName }
        assertEquals(
            "one name for two bodies leaves the reader unable to tell which is which",
            names.size,
            names.distinct().size,
        )
        val keys = callbacks.map { it.targetSymbol!!.key }
        assertEquals("and identity is what pins and choices are keyed by", keys.size, keys.distinct().size)

        val call = rootEvents(result).first { it.targetSymbol?.displayName == "pair()" }
        assertEquals(listOf(call.id, call.id), callbacks.map { it.attachedTo })
        assertNull(
            "save() follows pair(), not either body",
            rootEvents(result).first { it.targetSymbol?.displayName == "save()" }.attachedTo,
        )
    }

    // ---- K, L, M: a callback body costs what any other body costs ----

    fun `test K a body past the depth limit is marked and not entered`() {
        val result = analyze(
            "executor.submit(() -> executor.submit(() -> charge()));",
            limits = FlowLimits(maxDepth = 1),
        )
        val inner = callbacks(result).last()
        assertEquals(
            FlowMetadata.LIMIT_DEPTH,
            inner.metadata[FlowMetadata.LIMIT],
        )
        assertNull("nothing about a lambda earns it an exemption", bodyOf(result, inner))
    }

    fun `test L a body that exhausts the node budget truncates the run`() {
        val result = analyze(
            "executor.submit(() -> { charge(); audit(); save(); });",
            limits = FlowLimits(maxNodes = 3),
        )
        assertTrue(allNodes(result).any { it.kind == FlowNodeKind.LIMIT })
    }

    fun `test M a body that calls back into its own frame is a cycle`() {
        val result = analyze("executor.submit(() -> run());")
        val body = bodyOf(result, callbacks(result).single())!!
        assertEquals(
            "recursion through a callback is still recursion",
            listOf(FlowNodeKind.CYCLE),
            body.map { it.kind },
        )
    }

    // ---- N: nothing is invented ----

    fun `test N a body never handed to a call is not a callback`() {
        val result = analyze("Runnable r = () -> charge(); save();")
        assertTrue(callbacks(result).isEmpty())
        assertEquals(listOf("save()"), names(rootEvents(result)))
    }

    // ---- P, Q: disclosure ----

    fun `test P a run whose callbacks are all justified says nothing extra`() {
        val result = analyze("items.forEach(s -> charge());")
        val callback = callbacks(result).single()
        assertEquals(ExecutionMode.SYNC, callback.executionMode)
        assertTrue(
            "a line that appears for every run would stop meaning anything",
            FlowStatusModel.stateOf(null, result).stopReasons.none { it.text.contains("timing") },
        )
    }

    fun `test Q an undetermined timing is counted and leads to the card that has it`() {
        val result = analyze("helper(() -> charge());")
        val callback = callbacks(result).single()
        assertEquals(ExecutionMode.UNKNOWN, callback.executionMode)

        val reason = FlowStatusModel.stateOf(null, result).stopReasons
            .single { it.text == FlowLensBundle.message("status.reason.callback.timing", 1) }
        assertEquals(1, reason.count)
        assertEquals(
            "a count the reader cannot act on is a report, not an entry point",
            callback.id,
            reason.firstNode,
        )
    }

    fun `test an undetermined timing is stated in words, not left to position`() {
        val result = analyze("helper(() -> charge());")
        val callback = callbacks(result).single()
        val rows = FlowDetailsModel.stateOf(callback).rows.map { "${it.label}: ${it.value}" }
        assertTrue(
            rows.toString(),
            rows.any { it.contains(FlowLensBundle.message("enum.execution.UNKNOWN")) },
        )
        val card = CanvasViewModelBuilder.build(result, emptySet())!!.cards[1]
        assertTrue(
            "silence would let the reader supply their own assumption",
            card.tooltip.contains(FlowLensBundle.message("enum.execution.UNKNOWN")),
        )
        assertNotNull(card.executionGlyph)
    }

    // ---- the simplified-control-flow warning has to be findable ----

    fun `test a simplified body is counted and leads to the call that owns it`() {
        // Regression from the first real project: one `continue` in a nested
        // loop set a banner over a hundred-node map that said only that
        // something, somewhere, had been simplified.
        val result = analyze(
            "helper(() -> charge()); skip();",
            members = """
                void skip() {
                    for (String s : items) { if (s.isEmpty()) { continue; } audit(); }
                }
            """.trimIndent(),
        )
        assertTrue("the fixture must actually simplify something", result.controlFlowIncomplete)

        val simplified = result.frames.values.filter { it.controlFlowSimplified }
        assertEquals("and only the body that has it", 1, simplified.size)
        assertEquals("skip()", simplified.single().symbol.displayName)

        val reason = FlowStatusModel.stateOf(null, result).stopReasons
            .single { it.text == FlowLensBundle.message("status.reason.control.flow.simplified", 1) }
        val owner = rootEvents(result).first { it.targetSymbol?.displayName == "skip()" }
        assertEquals(
            "a warning a reader cannot follow is a banner, not a disclosure",
            owner.id,
            reason.firstNode,
        )
    }

    // ---- T: v0.2 structures inside a callback body ----

    fun `test T a branch inside a body renders like a branch anywhere else`() {
        val result = analyze("executor.submit(() -> { if (flag()) { charge(); } else { audit(); } });",
            members = "boolean flag() { return true; }")
        val body = bodyOf(result, callbacks(result).single())!!
        assertEquals(listOf("flag()", "CONDITION"), names(body))
        assertEquals(
            listOf(listOf("charge()"), listOf("audit()")),
            body[1].branches.map { names(it.events) },
        )
    }
}
