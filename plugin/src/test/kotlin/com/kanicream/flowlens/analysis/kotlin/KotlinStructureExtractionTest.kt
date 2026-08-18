package com.kanicream.flowlens.analysis.kotlin

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.kanicream.flowlens.analysis.ExtractedBranch
import com.kanicream.flowlens.analysis.ExtractedCall
import com.kanicream.flowlens.analysis.ExtractedStructure
import com.kanicream.flowlens.analysis.ExtractedTerminator
import com.kanicream.flowlens.analysis.FlowItem
import com.kanicream.flowlens.service.FlowMetadata
import com.kanicream.flowlens.testutil.RealJdkProjectDescriptor
import org.jetbrains.kotlin.psi.KtNamedFunction

/** Kotlin control-flow structure (`V0.2_SPEC.md` §8, case D and the shared cases). */
class KotlinStructureExtractionTest : LightJavaCodeInsightFixtureTestCase() {

    private val analyzer = KotlinFlowAnalyzer()

    override fun getProjectDescriptor(): LightProjectDescriptor = RealJdkProjectDescriptor.INSTANCE

    private val helpers = """
        fun cond(): Boolean = true
        fun subject(): Int = 1
        fun one(): Int = 1
        fun items(): List<String> = emptyList()
        fun a() { }
        fun b() { }
        fun c() { }
        fun d() { }
    """.trimIndent()

    private fun itemsOf(body: String): List<FlowItem> {
        val file = myFixture.configureByText("sample.kt", "fun run(flag: Boolean) { $body }\n$helpers")
        val function = PsiTreeUtil.findChildrenOfType(file, KtNamedFunction::class.java)
            .first { it.name == "run" }
        return analyzer.extractDirectFlow(function).items
    }

    private fun shape(items: List<FlowItem>): List<String> = items.map { item ->
        when (item) {
            is ExtractedCall -> item.calleeShortName
            is ExtractedTerminator -> item.kind.name
            is ExtractedStructure -> item.kind.name
        }
    }

    private fun branchShape(branch: ExtractedBranch): String =
        "${branch.kind}${branch.label?.let { "($it)" }.orEmpty()}=${shape(branch.items)}"

    private fun structure(items: List<FlowItem>): ExtractedStructure =
        items.filterIsInstance<ExtractedStructure>().first()

    fun `test an if expression becomes a condition`() {
        val items = itemsOf("if (cond()) { a() } else { b() }; d()")
        assertEquals(listOf("cond", "CONDITION", "d"), shape(items))
        assertEquals(listOf("THEN=[a]", "ELSE=[b]"), structure(items).branches.map(::branchShape))
    }

    fun `test D a when keeps its entry conditions as branch labels`() {
        val items = itemsOf("when (subject()) { 1 -> a(); else -> b() }")
        assertEquals(listOf("subject", "SWITCH"), shape(items))
        val switch = structure(items)
        assertEquals("subject()", switch.summary)
        assertEquals(listOf("CASE(1)=[a]", "DEFAULT=[b]"), switch.branches.map(::branchShape))
    }

    fun `test a subjectless when is still a multi-way branch`() {
        val items = itemsOf("when { cond() -> a(); else -> b() }")
        val switch = structure(items)
        assertEquals(
            "the guard repeats per branch, so it belongs inside its branch",
            listOf("CASE(cond())=[cond, a]", "DEFAULT=[b]"),
            switch.branches.map(::branchShape),
        )
    }

    fun `test a when entry guard is on the map`() {
        val items = itemsOf("when (subject()) { 1 if cond() -> a(); else -> b() }")
        assertEquals(
            "the guard is evaluated to choose the entry, so it is inside it",
            listOf("CASE(1)=[cond, a]", "DEFAULT=[b]"),
            structure(items).branches.map(::branchShape),
        )
    }

    fun `test a while loop keeps its repeated condition inside the body`() {
        val items = itemsOf("while (cond()) { a() }; d()")
        assertEquals(listOf("LOOP", "d"), shape(items))
        assertEquals(listOf("BODY=[cond, a]"), structure(items).branches.map(::branchShape))
    }

    fun `test F a for loop evaluates its range once before the loop`() {
        val items = itemsOf("for (s in items()) { a() }")
        assertEquals(listOf("items", "LOOP"), shape(items))
        assertEquals(listOf("BODY=[a]"), structure(items).branches.map(::branchShape))
    }

    fun `test G a do-while records that its body runs at least once`() {
        val items = itemsOf("do { a() } while (cond())")
        val loop = structure(items)
        assertEquals(listOf("BODY=[a, cond]"), loop.branches.map(::branchShape))
        assertEquals("true", loop.metadata[FlowMetadata.LOOP_RUNS_AT_LEAST_ONCE])
    }

    fun `test H a try keeps its catch types and finally`() {
        val items = itemsOf("try { a() } catch (e: RuntimeException) { b() } finally { c() }; d()")
        assertEquals(listOf("TRY", "d"), shape(items))
        assertEquals(
            listOf("TRY=[a]", "CATCH(RuntimeException)=[b]", "FINALLY=[c]"),
            structure(items).branches.map(::branchShape),
        )
    }

    fun `test I a return terminates after its expression`() {
        val items = itemsOf("a(); return")
        assertEquals(listOf("a", "RETURN"), shape(items))
    }

    fun `test a return says what it hands back`() {
        val bare = itemsOf("return").filterIsInstance<ExtractedTerminator>().single()
        assertNull(bare.summary)

        val valued = myFixture.configureByText(
            "ret.kt",
            """
            fun run(): Int { return one() }
            fun one(): Int = 1
            """.trimIndent(),
        )
        val function = PsiTreeUtil.findChildrenOfType(valued, KtNamedFunction::class.java)
            .first { it.name == "run" }
        val terminator = analyzer.extractDirectFlow(function).items
            .filterIsInstance<ExtractedTerminator>().single()
        assertEquals("one()", terminator.summary)
    }

    fun `test J a throw evaluates its expression first`() {
        val items = itemsOf("throw IllegalStateException(one().toString())")
        assertEquals("THROW", shape(items).last())
        assertEquals("one", shape(items).first())
    }

    fun `test M structures nest`() {
        val items = itemsOf("for (s in items()) { if (flag) { a() } }")
        val body = structure(items).branches.single()
        val nested = body.items.filterIsInstance<ExtractedStructure>().single()
        assertEquals(listOf("THEN=[a]"), nested.branches.map(::branchShape))
    }

    fun `test N a call inside a branch is not marked conditional`() {
        val items = itemsOf("if (flag) { a() }")
        val call = structure(items).branches.single().items.filterIsInstance<ExtractedCall>().single()
        assertFalse(call.conditional)
    }

    fun `test O elvis and safe calls keep the marker and stay disclosed`() {
        val file = myFixture.configureByText(
            "elvis.kt",
            """
            fun run(s: String?) { val x = s?.trim() ?: fallback() }
            fun fallback(): String = ""
            """.trimIndent(),
        )
        val function = PsiTreeUtil.findChildrenOfType(file, KtNamedFunction::class.java)
            .first { it.name == "run" }
        val extraction = analyzer.extractDirectFlow(function)
        assertTrue(
            "an operand that may be skipped is still marked",
            extraction.calls.any { it.conditional },
        )
        assertTrue("what is not represented stays disclosed", extraction.controlFlowSimplified)
    }

    fun `test a safe call that skips no call discloses nothing`() {
        val file = myFixture.configureByText(
            "safe.kt",
            """
            fun run(s: String?) { val n = s?.length }
            """.trimIndent(),
        )
        val function = PsiTreeUtil.findChildrenOfType(file, KtNamedFunction::class.java)
            .first { it.name == "run" }
        assertFalse(
            "`s?.length` reads a property; no call is being skipped",
            analyzer.extractDirectFlow(function).controlFlowSimplified,
        )
    }

    fun `test P represented control flow is no longer reported as simplified`() {
        val file = myFixture.configureByText(
            "clean.kt",
            """
            fun run(flag: Boolean) {
                if (flag) { a() } else { b() }
                for (i in 0..2) { c() }
            }
            fun a() { }
            fun b() { }
            fun c() { }
            """.trimIndent(),
        )
        val function = PsiTreeUtil.findChildrenOfType(file, KtNamedFunction::class.java)
            .first { it.name == "run" }
        assertFalse(analyzer.extractDirectFlow(function).controlFlowSimplified)
    }
}
