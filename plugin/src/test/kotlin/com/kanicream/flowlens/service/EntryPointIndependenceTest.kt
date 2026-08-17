package com.kanicream.flowlens.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.kanicream.flowlens.core.model.FlowAnalysisResult
import com.kanicream.flowlens.core.model.FlowFrame
import com.kanicream.flowlens.core.model.FlowLimits
import com.kanicream.flowlens.testutil.RealJdkProjectDescriptor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * A callable's own events must not depend on how the user got there: analyzing
 * `handle()` directly and reaching it from `purchase()` have to describe the same
 * body. Only how deep the traversal continues below it may differ, because depth
 * and the node budget are counted from the root.
 */
class EntryPointIndependenceTest : LightJavaCodeInsightFixtureTestCase() {

    override fun getProjectDescriptor(): LightProjectDescriptor = RealJdkProjectDescriptor.INSTANCE

    override fun runInDispatchThread(): Boolean = false

    private val service: FlowAnalysisService get() = FlowAnalysisService.getInstance(project)

    private fun setUpSources() {
        ApplicationManager.getApplication().invokeAndWait {
            myFixture.addFileToProject(
                "Repository.java",
                "public class Repository { public static void persist() { } }",
            )
            myFixture.addFileToProject(
                "service.kt",
                """
                object KtService {
                    @JvmStatic
                    fun handle() {
                        Repository.persist()
                        val u = User("a")
                        u.copy()
                        u.greet()
                    }
                }

                data class User(val name: String) {
                    fun greet() {
                        println(name)
                    }
                }
                """.trimIndent(),
            )
            myFixture.configureByText(
                "Controller.java",
                """
                public class Controller {
                    void purchase() { KtService.handle(); }
                }
                """.trimIndent(),
            )
        }
    }

    private fun analyze(marker: String, limits: FlowLimits): FlowAnalysisResult {
        val file = myFixture.file.virtualFile
        val offset = myFixture.file.text.indexOf(marker) + marker.length - 2
        service.startAnalysis(file, offset, limits)
        return runBlocking {
            withTimeout(60_000) { service.results.first { it != null && it.isTerminal }!! }
        }
    }

    private fun eventNames(frame: FlowFrame): List<String> =
        frame.events.map { it.targetSymbol?.displayName ?: "?" }

    fun `test a callable describes the same body from either entry point`() {
        setUpSources()
        val limits = FlowLimits(maxDepth = 5)

        // Reached through the Java root.
        val fromController = analyze("void purchase()", limits)
        val handleCall = fromController.rootFrame!!.events.single()
        assertEquals("handle()", handleCall.targetSymbol!!.displayName)
        val nestedBody = fromController.frame(handleCall.targetFrameId!!)!!

        // Analyzed directly as the root.
        ApplicationManager.getApplication().invokeAndWait {
            myFixture.configureByText(
                "service2.kt",
                myFixture.file.let { _ ->
                    """
                    object KtService2 {
                        @JvmStatic
                        fun handle() {
                            Repository.persist()
                            val u = User("a")
                            u.copy()
                            u.greet()
                        }
                    }
                    """.trimIndent()
                },
            )
        }
        val asRoot = analyze("fun handle()", limits)

        assertEquals(
            "the same source produces the same events regardless of entry point",
            eventNames(nestedBody),
            eventNames(asRoot.rootFrame!!),
        )
        assertEquals(
            listOf("persist()", "User()", "copy()", "greet()"),
            eventNames(asRoot.rootFrame!!),
        )
    }

    fun `test the depth budget is counted from the root not from the callable`() {
        setUpSources()
        // handle() sits one level down, so its callees' bodies need one more level
        // of budget than they do when handle() is the root. This is by design; the
        // blocked call carries an explicit marker rather than silently looking
        // like a call with nothing inside.
        val shallow = analyze("void purchase()", FlowLimits(maxDepth = 1))
        val handleCall = shallow.rootFrame!!.events.single()
        val body = shallow.frame(handleCall.targetFrameId!!)!!
        val greet = body.events.first { it.targetSymbol?.displayName == "greet()" }
        assertNull("greet()'s body is beyond the depth budget here", greet.targetFrameId)
        assertEquals(FlowMetadata.LIMIT_DEPTH, greet.metadata[FlowMetadata.LIMIT])

        val deep = analyze("void purchase()", FlowLimits(maxDepth = 3))
        val deepBody = deep.frame(deep.rootFrame!!.events.single().targetFrameId!!)!!
        val deepGreet = deepBody.events.first { it.targetSymbol?.displayName == "greet()" }
        assertNotNull("with more budget the same call is entered", deepGreet.targetFrameId)
    }

    fun `test a generated member looks the same from either entry point`() {
        setUpSources()
        val fromController = analyze("void purchase()", FlowLimits(maxDepth = 5))
        val body = fromController.frame(
            fromController.rootFrame!!.events.single().targetFrameId!!,
        )!!
        val copy = body.events.first { it.targetSymbol?.displayName == "copy()" }
        assertNull("a generated member is never entered", copy.targetFrameId)
        assertEquals("SYNTHETIC", copy.metadata[FlowMetadata.ORIGIN])
    }
}
