package com.kanicream.flowlens.dispatch

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.kanicream.flowlens.core.model.DispatchConfidence
import com.kanicream.flowlens.core.model.FlowAnalysisResult
import com.kanicream.flowlens.core.model.FlowLimits
import com.kanicream.flowlens.service.FlowAnalysisService
import com.kanicream.flowlens.service.FlowMetadata
import com.kanicream.flowlens.testutil.RealJdkProjectDescriptor
import com.kanicream.flowlens.workflow.FlowEntryRef
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Candidates and choices (`V0.4_SPEC.md` §3–4). A choice may change what the
 * traversal enters; it may never change what the model claims to know.
 */
class DispatchChoiceTest : LightJavaCodeInsightFixtureTestCase() {

    override fun getProjectDescriptor(): LightProjectDescriptor = RealJdkProjectDescriptor.INSTANCE

    override fun runInDispatchThread(): Boolean = false

    private val service: FlowAnalysisService get() = FlowAnalysisService.getInstance(project)
    private val choices: DispatchChoices get() = DispatchChoices.getInstance(project)

    override fun setUp() {
        super.setUp()
        ApplicationManager.getApplication().invokeAndWait {
            myFixture.addFileToProject(
                "demo/Gateway.java",
                """
                package demo;
                public interface Gateway { void charge(); }
                """.trimIndent(),
            )
            myFixture.addFileToProject(
                "demo/StripeGateway.java",
                """
                package demo;
                public class StripeGateway implements Gateway {
                    public void charge() { callApi(); }
                    void callApi() { }
                }
                """.trimIndent(),
            )
            myFixture.addFileToProject(
                "demo/PaypalGateway.java",
                """
                package demo;
                public class PaypalGateway implements Gateway {
                    public void charge() { redirect(); }
                    void redirect() { }
                }
                """.trimIndent(),
            )
            myFixture.configureByText(
                "Checkout.java",
                """
                public class Checkout {
                    demo.Gateway gateway;
                    void run() { gateway.charge(); gateway.charge(); }
                }
                """.trimIndent(),
            )
        }
    }

    override fun tearDown() {
        try {
            choices.clearAll()
            service.cancelActive()
        } finally {
            super.tearDown()
        }
    }

    private fun analyze(): FlowAnalysisResult {
        val entry = "void run()"
        val offset = myFixture.file.text.indexOf(entry) + entry.length - 2
        val runId = service.startAnalysis(myFixture.file.virtualFile, offset, FlowLimits())
        return runBlocking {
            withTimeout(60_000) { service.results.first { it?.runId == runId && it.isTerminal }!! }
        }
    }

    private fun interfaceKey(): String = runReadActionBlocking {
        com.kanicream.flowlens.analysis.FlowAnalyzerRegistry
            .forDeclaration(interfaceMethod())!!
            .describeCallable(interfaceMethod()).key
    }

    private fun interfaceMethod(): com.intellij.psi.PsiElement = runReadActionBlocking {
        val file = myFixture.javaFacade.findClass("demo.Gateway")!!
        PsiTreeUtil.findChildrenOfType(file, PsiMethod::class.java).first { it.name == "charge" }
    }

    fun `test A an interface call lists its implementations in a stable order`() {
        val result = runReadActionBlocking { CandidateFinder.find(project, interfaceMethod()) }
        assertEquals(
            listOf("PaypalGateway", "StripeGateway"),
            result.candidates.map { it.symbol.containerName },
        )
        assertFalse("two implementations is not a partial list", result.partial)
    }

    fun `test C a candidate without a body is not offered`() {
        // The interface method itself has no body and must not appear as an
        // implementation of itself.
        val result = runReadActionBlocking { CandidateFinder.find(project, interfaceMethod()) }
        assertTrue(result.candidates.none { it.symbol.containerName == "Gateway" })
    }

    fun `test the call is a dead end before any choice`() {
        val call = analyze().rootFrame!!.events.first()
        assertEquals(DispatchConfidence.AMBIGUOUS, call.dispatchConfidence)
        assertNull("nothing was entered", call.targetFrameId)
        assertNull(call.metadata[FlowMetadata.CHOSEN])
    }

    fun `test E and G choosing continues into the implementation, at every call site`() {
        val candidate = runReadActionBlocking { CandidateFinder.find(project, interfaceMethod()) }
            .candidates.first { it.symbol.containerName == "StripeGateway" }
        val key = runReadActionBlocking {
            com.kanicream.flowlens.analysis.FlowAnalyzerRegistry
                .forDeclaration(interfaceMethod())!!
                .describeCallable(interfaceMethod()).key
        }
        choices.choose(DispatchChoice(key, "charge()", candidate.entry))

        val result = analyze()
        val calls = result.rootFrame!!.events
        assertEquals("both call sites follow the choice", 2, calls.size)
        for (call in calls) {
            assertNotNull("the chosen body was entered", call.targetFrameId)
            val body = result.frame(call.targetFrameId!!)!!
            assertEquals(
                listOf("callApi()"),
                body.events.map { it.targetSymbol?.displayName },
            )
        }
    }

    fun `test F a chosen call still reports the confidence it actually has`() {
        val candidate = runReadActionBlocking { CandidateFinder.find(project, interfaceMethod()) }
            .candidates.first { it.symbol.containerName == "StripeGateway" }
        val key = runReadActionBlocking {
            com.kanicream.flowlens.analysis.FlowAnalyzerRegistry
                .forDeclaration(interfaceMethod())!!
                .describeCallable(interfaceMethod()).key
        }
        choices.choose(DispatchChoice(key, "charge()", candidate.entry))

        val call = analyze().rootFrame!!.events.first()
        assertEquals(
            "a choice is not a proof; the call is still ambiguous",
            DispatchConfidence.AMBIGUOUS,
            call.dispatchConfidence,
        )
        assertEquals(
            "and it says whose body it is showing, unambiguously",
            "StripeGateway.charge()",
            call.metadata[FlowMetadata.CHOSEN],
        )
        assertEquals(
            "the card still names the callable that was actually called",
            "Gateway",
            call.targetSymbol?.containerName,
        )
    }

    fun `test the card and the details panel both say the continuation was chosen`() {
        // The honesty rule is about what the reader sees. The model getting it
        // right while the canvas stays silent is the failure this guards.
        val candidate = runReadActionBlocking { CandidateFinder.find(project, interfaceMethod()) }
            .candidates.first { it.symbol.containerName == "StripeGateway" }
        choices.choose(DispatchChoice(interfaceKey(), "Gateway.charge()", candidate.entry))

        val result = analyze()
        val card = com.kanicream.flowlens.ui.canvas.CanvasViewModelBuilder
            .build(result, emptySet())!!.cards.first()

        assertEquals("StripeGateway.charge()", card.chosenImplementation)
        assertTrue(
            "the card carries a visible mark, not just a field: ${card.trailingNote}",
            card.trailingNote?.contains("StripeGateway") == true,
        )
        assertTrue(
            "and the tooltip explains it: ${card.tooltip}",
            card.tooltip?.contains("StripeGateway") == true,
        )

        val details = com.kanicream.flowlens.ui.details.FlowDetailsModel.stateOf(card.node)
        val chosenRow = details.rows.firstOrNull { it.value.contains("StripeGateway") }
        assertNotNull("the details panel names what was chosen", chosenRow)
        assertFalse(
            "a raw bundle key must not reach the panel",
            chosenRow!!.label.startsWith("details."),
        )
    }

    fun `test a choice does not replace a body the analyzer could prove`() {
        // A call the traversal would enter anyway is not what a choice is for;
        // substituting a subclass there would replace fact with guess.
        ApplicationManager.getApplication().invokeAndWait {
            myFixture.addFileToProject(
                "demo/Base.java",
                """
                package demo;
                public class Base { public void work() { helper(); } void helper() { } }
                """.trimIndent(),
            )
            myFixture.addFileToProject(
                "demo/Derived.java",
                """
                package demo;
                public class Derived extends Base { public void work() { other(); } void other() { } }
                """.trimIndent(),
            )
            myFixture.configureByText(
                "Runner.java",
                """
                public class Runner {
                    demo.Base base;
                    void run() { base.work(); }
                }
                """.trimIndent(),
            )
        }
        val baseWork = runReadActionBlocking {
            val cls = myFixture.javaFacade.findClass("demo.Base")!!
            PsiTreeUtil.findChildrenOfType(cls, PsiMethod::class.java).first { it.name == "work" }
        }
        val key = runReadActionBlocking {
            com.kanicream.flowlens.analysis.FlowAnalyzerRegistry
                .forDeclaration(baseWork)!!.describeCallable(baseWork).key
        }
        val derived = runReadActionBlocking { CandidateFinder.find(project, baseWork) }
            .candidates.first { it.symbol.containerName == "Derived" }
        choices.choose(DispatchChoice(key, "Base.work()", derived.entry))

        val result = analyze()
        val call = result.rootFrame!!.events.first()
        assertNull(
            "the provable body must not be swapped for a chosen one",
            call.metadata[FlowMetadata.CHOSEN],
        )
        val body = result.frame(call.targetFrameId!!)!!
        assertEquals(
            listOf("helper()"),
            body.events.map { it.targetSymbol?.displayName },
        )
    }

    fun `test H clearing a choice makes the call a dead end again`() {
        val candidate = runReadActionBlocking { CandidateFinder.find(project, interfaceMethod()) }
            .candidates.first()
        val key = runReadActionBlocking {
            com.kanicream.flowlens.analysis.FlowAnalyzerRegistry
                .forDeclaration(interfaceMethod())!!
                .describeCallable(interfaceMethod()).key
        }
        choices.choose(DispatchChoice(key, "charge()", candidate.entry))
        assertNotNull(analyze().rootFrame!!.events.first().targetFrameId)

        choices.clear(key)
        val call = analyze().rootFrame!!.events.first()
        assertNull(call.targetFrameId)
        assertNull(call.metadata[FlowMetadata.CHOSEN])
    }

    fun `test J a choice pointing at something gone is not applied`() {
        val key = runReadActionBlocking {
            com.kanicream.flowlens.analysis.FlowAnalyzerRegistry
                .forDeclaration(interfaceMethod())!!
                .describeCallable(interfaceMethod()).key
        }
        choices.choose(
            DispatchChoice(
                key,
                "charge()",
                FlowEntryRef("java:demo.Ghost#charge()", "java", "charge()", "Ghost", "demo/Ghost.java"),
            ),
        )

        val call = analyze().rootFrame!!.events.first()
        assertNull("no continuation, rather than a different one", call.targetFrameId)
        assertNull(call.metadata[FlowMetadata.CHOSEN])
    }
}
