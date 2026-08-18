package com.kanicream.flowlens.analysis.kotlin

import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.kanicream.flowlens.analysis.DirectFlowExtraction
import com.kanicream.flowlens.core.model.DispatchConfidence
import com.kanicream.flowlens.core.model.ResolutionStatus
import com.kanicream.flowlens.core.model.SourceOrigin
import com.kanicream.flowlens.testutil.RealJdkProjectDescriptor
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Kotlin analyzer against real Kotlin PSI/resolution, including the synthetic
 * declaration policy: compiler-generated members must never become recursive
 * targets (REPO_LENS_LESSONS.md 3.1).
 */
class KotlinFlowAnalyzerTest : LightJavaCodeInsightFixtureTestCase() {

    private val analyzer = KotlinFlowAnalyzer()

    override fun getProjectDescriptor(): LightProjectDescriptor = RealJdkProjectDescriptor.INSTANCE

    private fun extractionOf(fileText: String, functionName: String = "run"): DirectFlowExtraction {
        val file = myFixture.configureByText("sample.kt", fileText)
        val function = com.intellij.psi.util.PsiTreeUtil
            .findChildrenOfType(file, KtNamedFunction::class.java)
            .first { it.name == functionName }
        return analyzer.extractDirectFlow(function)
    }

    private fun callNames(extraction: DirectFlowExtraction): List<String> =
        extraction.calls.map { it.calleeShortName }

    fun `test top level function call resolves exactly`() {
        val extraction = extractionOf(
            """
            fun run() { helper() }
            fun helper() { }
            """.trimIndent(),
        )
        val target = analyzer.resolveCall(extraction.calls.single())
        assertEquals(DispatchConfidence.EXACT, target.dispatchConfidence)
        assertEquals(ResolutionStatus.PROJECT_LOCAL, target.resolutionStatus)
        assertEquals(SourceOrigin.PHYSICAL_SOURCE, target.sourceOrigin)
        assertTrue(target.hasAnalyzableBody)
    }

    fun `test nested calls follow evaluation order`() {
        val extraction = extractionOf(
            """
            fun run() { save(convert(load())) }
            fun load(): String = ""
            fun convert(s: String): String = s
            fun save(s: String) { }
            """.trimIndent(),
        )
        assertEquals(listOf("load", "convert", "save"), callNames(extraction))
    }

    fun `test chained member calls follow receiver first order`() {
        val extraction = extractionOf(
            """
            class Pipe {
                fun run() { source().transform().save() }
                fun source(): Pipe = this
                fun transform(): Pipe = this
                fun save() { }
            }
            """.trimIndent(),
        )
        assertEquals(listOf("source", "transform", "save"), callNames(extraction))
    }

    fun `test member function of final class is exact`() {
        val extraction = extractionOf(
            """
            class Service { fun work() { } }
            fun run(s: Service) { s.work() }
            """.trimIndent(),
        )
        val target = analyzer.resolveCall(extraction.calls.single())
        assertEquals(DispatchConfidence.EXACT, target.dispatchConfidence)
        assertTrue(target.hasAnalyzableBody)
    }

    fun `test open member function is declared target`() {
        val extraction = extractionOf(
            """
            open class Service { open fun work() { } }
            fun run(s: Service) { s.work() }
            """.trimIndent(),
        )
        val target = analyzer.resolveCall(extraction.calls.single())
        assertEquals(DispatchConfidence.DECLARED_TARGET, target.dispatchConfidence)
    }

    fun `test interface function without body is ambiguous`() {
        val extraction = extractionOf(
            """
            interface Gateway { fun charge() }
            fun run(g: Gateway) { g.charge() }
            """.trimIndent(),
        )
        val target = analyzer.resolveCall(extraction.calls.single())
        assertEquals(DispatchConfidence.AMBIGUOUS, target.dispatchConfidence)
        assertFalse(target.hasAnalyzableBody)
    }

    fun `test extension function call resolves to physical source`() {
        val extraction = extractionOf(
            """
            fun String.shout(): String = this
            fun run() { "hi".shout() }
            """.trimIndent(),
        )
        val target = analyzer.resolveCall(extraction.calls.single())
        assertEquals(DispatchConfidence.EXACT, target.dispatchConfidence)
        assertEquals(SourceOrigin.PHYSICAL_SOURCE, target.sourceOrigin)
        assertTrue(target.hasAnalyzableBody)
        assertTrue(target.symbol!!.displayName.contains("shout"))
    }

    fun `test constructor call is exact constructor`() {
        val extraction = extractionOf(
            """
            class Helper(val x: Int)
            fun run() { Helper(1) }
            """.trimIndent(),
        )
        val target = analyzer.resolveCall(extraction.calls.single())
        assertTrue(target.isConstructor)
        assertEquals(DispatchConfidence.EXACT, target.dispatchConfidence)
        // A primary constructor without body is not a recursive target.
        assertFalse(target.hasAnalyzableBody)
    }

    fun `test data class copy is reported as a generated member not a constructor`() {
        // Regression: `copy` has no declaration of its own, so the reference
        // resolves to the class. Treating that as a constructor call made the
        // canvas show `u.copy()` as `User()` with a `new` badge — a generated
        // member presented as authored code.
        val extraction = extractionOf(
            """
            data class User(val name: String)
            fun run(u: User) { u.copy() }
            """.trimIndent(),
        )
        val target = analyzer.resolveCall(extraction.calls.single())
        assertEquals("copy()", target.symbol!!.displayName)
        assertEquals("User", target.symbol!!.containerName)
        assertFalse("a generated member is not a constructor call", target.isConstructor)
        assertEquals(SourceOrigin.SYNTHETIC, target.sourceOrigin)
        assertFalse("synthetic copy() must not be recursable", target.hasAnalyzableBody)
        assertNotNull("it still navigates to the declaration that generates it", target.declaration)
    }

    fun `test data class componentN is reported under its own name`() {
        val extraction = extractionOf(
            """
            data class User(val name: String)
            fun run(u: User) { u.component1() }
            """.trimIndent(),
        )
        val target = analyzer.resolveCall(extraction.calls.single())
        assertEquals("component1()", target.symbol!!.displayName)
        assertEquals(SourceOrigin.SYNTHETIC, target.sourceOrigin)
        assertFalse(target.hasAnalyzableBody)
        assertFalse(target.isConstructor)
    }

    fun `test invoking a function-typed value is an ordinary call`() {
        // Regression: resolution of `handler()` lands on the property, which the
        // generated-member rule mistook for a compiler-generated member and
        // labelled as generated code.
        val extraction = extractionOf(
            """
            class Service {
                val handler: () -> Unit = {}
                fun run() { handler() }
            }
            """.trimIndent(),
        )
        val target = analyzer.resolveCall(extraction.calls.single())
        assertEquals("handler()", target.symbol!!.displayName)
        assertFalse(
            "an authored function value is not compiler-generated",
            target.sourceOrigin == SourceOrigin.SYNTHETIC,
        )
        assertFalse(target.isConstructor)
    }

    fun `test invoking a function parameter is an ordinary call`() {
        val extraction = extractionOf(
            """
            fun run(cb: () -> Unit) { cb() }
            """.trimIndent(),
        )
        val target = analyzer.resolveCall(extraction.calls.single())
        assertEquals("cb()", target.symbol!!.displayName)
        assertFalse(target.sourceOrigin == SourceOrigin.SYNTHETIC)
    }

    fun `test a real constructor call is still a constructor`() {
        val extraction = extractionOf(
            """
            data class User(val name: String)
            fun run() { User("a") }
            """.trimIndent(),
        )
        val target = analyzer.resolveCall(extraction.calls.single())
        assertEquals("User()", target.symbol!!.displayName)
        assertTrue(target.isConstructor)
        assertEquals(SourceOrigin.PHYSICAL_SOURCE, target.sourceOrigin)
    }

    fun `test a constructor call and a generated member are distinguishable in one body`() {
        val extraction = extractionOf(
            """
            data class User(val name: String)
            fun run() {
                val u = User("a")
                u.copy()
            }
            """.trimIndent(),
        )
        val targets = extraction.calls.map { analyzer.resolveCall(it) }
        assertEquals(listOf("User()", "copy()"), targets.map { it.symbol!!.displayName })
        assertEquals(listOf(true, false), targets.map { it.isConstructor })
    }

    fun `test explicitly authored member of data class stays analyzable`() {
        val extraction = extractionOf(
            """
            data class User(val name: String) {
                fun greet() { println(name) }
            }
            fun run(u: User) { u.greet() }
            """.trimIndent(),
        )
        val target = analyzer.resolveCall(extraction.calls.single())
        assertEquals(SourceOrigin.PHYSICAL_SOURCE, target.sourceOrigin)
        assertTrue("authored members must stay recursable", target.hasAnalyzableBody)
    }

    fun `test property access does not create call events`() {
        val extraction = extractionOf(
            """
            class Box { var value: Int = 0 }
            fun run(b: Box) {
                b.value = 1
                val x = b.value
            }
            """.trimIndent(),
        )
        assertEquals(emptyList<String>(), callNames(extraction))
    }

    fun `test lambda bodies are traversal boundaries`() {
        val extraction = extractionOf(
            """
            fun run() { listOf(1).map { helper(it) } }
            fun helper(x: Int): Int = x
            """.trimIndent(),
        )
        assertEquals(listOf("listOf", "map"), callNames(extraction))
    }

    fun `test an if is represented rather than reported as simplified`() {
        val extraction = extractionOf(
            """
            fun run(flag: Boolean) { if (flag) helper() }
            fun helper() { }
            """.trimIndent(),
        )
        assertFalse("v0.2 represents the branch itself", extraction.controlFlowSimplified)
        assertEquals(listOf("helper"), callNames(extraction))
    }

    fun `test entry point detection inside named function`() {
        val file = myFixture.configureByText(
            "entry.kt",
            """
            fun tar<caret>get() { helper() }
            fun helper() { }
            """.trimIndent(),
        )
        val entry = analyzer.findEntryPoint(file, myFixture.caretOffset)
        assertNotNull(entry)
        assertEquals("target", (entry as KtNamedFunction).name)
    }

    fun `test no entry point at top level outside functions`() {
        val file = myFixture.configureByText(
            "entry.kt",
            """
            val consta<caret>nt = 1
            fun helper() { }
            """.trimIndent(),
        )
        assertNull(analyzer.findEntryPoint(file, myFixture.caretOffset))
    }
}
