package com.kanicream.flowlens.analysis.java

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.kanicream.flowlens.analysis.DirectFlowExtraction
import com.kanicream.flowlens.analysis.FlowAnalyzerRegistry
import com.kanicream.flowlens.core.model.DispatchConfidence
import com.kanicream.flowlens.core.model.FlowNodeKind
import com.kanicream.flowlens.core.model.ResolutionStatus
import com.kanicream.flowlens.core.model.SourceOrigin

/**
 * Java analyzer against real Java PSI: evaluation order, resolution, dispatch
 * confidence, and provenance. PSI is never mocked (TEST_STRATEGY.md Layer B).
 */
class JavaFlowAnalyzerTest : LightJavaCodeInsightFixtureTestCase() {

    private val analyzer = JavaFlowAnalyzer()

    override fun getProjectDescriptor(): com.intellij.testFramework.LightProjectDescriptor =
        com.kanicream.flowlens.testutil.RealJdkProjectDescriptor.INSTANCE

    private fun extractionOf(classBody: String, methodName: String = "run"): DirectFlowExtraction {
        val file = myFixture.configureByText(
            "Sample.java",
            "public class Sample { $classBody }",
        )
        val method = com.intellij.psi.util.PsiTreeUtil
            .findChildrenOfType(file, com.intellij.psi.PsiMethod::class.java)
            .first { it.name == methodName }
        return analyzer.extractDirectFlow(method)
    }

    private fun callNames(extraction: DirectFlowExtraction): List<String> =
        extraction.calls.map { it.calleeShortName }

    fun `test linear calls keep source order`() {
        val extraction = extractionOf(
            """
            void run() { a(); b(); c(); }
            void a() {} void b() {} void c() {}
            """.trimIndent(),
        )
        assertEquals(listOf("a", "b", "c"), callNames(extraction))
    }

    fun `test nested calls follow evaluation order not PSI preorder`() {
        val extraction = extractionOf(
            """
            void run() { save(convert(load())); }
            String load() { return ""; }
            String convert(String s) { return s; }
            void save(String s) {}
            """.trimIndent(),
        )
        assertEquals(listOf("load", "convert", "save"), callNames(extraction))
    }

    fun `test chained calls follow receiver first order`() {
        val extraction = extractionOf(
            """
            void run() { source().transform().save(); }
            Sample source() { return this; }
            Sample transform() { return this; }
            void save() {}
            """.trimIndent(),
        )
        assertEquals(listOf("source", "transform", "save"), callNames(extraction))
    }

    fun `test argument lists evaluate left to right before the call`() {
        val extraction = extractionOf(
            """
            void run() { foo(a(), b()); }
            int a() { return 1; } int b() { return 2; }
            void foo(int x, int y) {}
            """.trimIndent(),
        )
        assertEquals(listOf("a", "b", "foo"), callNames(extraction))
    }

    fun `test duplicate targets stay separate call events`() {
        val extraction = extractionOf(
            """
            void run() { validate(1); validate(2); }
            void validate(int v) {}
            """.trimIndent(),
        )
        assertEquals(listOf("validate", "validate"), callNames(extraction))
        val targets = extraction.calls.map { analyzer.resolveCall(it).symbol?.key }
        assertEquals(2, targets.size)
        assertEquals(targets[0], targets[1])
    }

    fun `test constructor call is extracted and resolves exactly`() {
        val extraction = extractionOf(
            """
            void run() { new Helper(); }
            static class Helper { Helper() { } }
            """.trimIndent(),
        )
        val call = extraction.calls.single()
        assertEquals(FlowNodeKind.CONSTRUCTOR, call.kind)
        val target = analyzer.resolveCall(call)
        assertTrue(target.isConstructor)
        assertEquals(DispatchConfidence.EXACT, target.dispatchConfidence)
        assertEquals(ResolutionStatus.PROJECT_LOCAL, target.resolutionStatus)
        assertTrue(target.hasAnalyzableBody)
    }

    fun `test static and private calls are exact`() {
        val extraction = extractionOf(
            """
            void run() { stat(); priv(); }
            static void stat() {}
            private void priv() {}
            """.trimIndent(),
        )
        val confidences = extraction.calls.map { analyzer.resolveCall(it).dispatchConfidence }
        assertEquals(listOf(DispatchConfidence.EXACT, DispatchConfidence.EXACT), confidences)
    }

    fun `test ordinary virtual call is declared target`() {
        myFixture.configureByText(
            "Service.java",
            "public class Service { public void work() { } }",
        )
        val extraction = extractionOf(
            """
            void run(Service s) { s.work(); }
            """.trimIndent(),
        )
        val target = analyzer.resolveCall(extraction.calls.single())
        assertEquals(DispatchConfidence.DECLARED_TARGET, target.dispatchConfidence)
        assertEquals(ResolutionStatus.PROJECT_LOCAL, target.resolutionStatus)
        assertTrue(target.hasAnalyzableBody)
    }

    fun `test interface call without body is ambiguous and not recursable`() {
        myFixture.configureByText("Gateway.java", "public interface Gateway { void charge(); }")
        val extraction = extractionOf(
            """
            void run(Gateway g) { g.charge(); }
            """.trimIndent(),
        )
        val target = analyzer.resolveCall(extraction.calls.single())
        assertEquals(DispatchConfidence.AMBIGUOUS, target.dispatchConfidence)
        assertFalse(target.hasAnalyzableBody)
    }

    fun `test JDK call is external library target`() {
        val extraction = extractionOf(
            """
            void run(String s) { s.trim(); }
            """.trimIndent(),
        )
        val target = analyzer.resolveCall(extraction.calls.single())
        assertEquals(ResolutionStatus.EXTERNAL, target.resolutionStatus)
        assertEquals(SourceOrigin.LIBRARY, target.sourceOrigin)
        assertFalse(target.hasAnalyzableBody)
    }

    fun `test unknown call is unresolved and does not abort siblings`() {
        val extraction = extractionOf(
            """
            void run() { missing(); after(); }
            void after() {}
            """.trimIndent(),
        )
        assertEquals(listOf("missing", "after"), callNames(extraction))
        val first = analyzer.resolveCall(extraction.calls[0])
        val second = analyzer.resolveCall(extraction.calls[1])
        assertEquals(ResolutionStatus.UNRESOLVED, first.resolutionStatus)
        assertEquals(ResolutionStatus.PROJECT_LOCAL, second.resolutionStatus)
    }

    fun `test calls inside if mark control flow simplified`() {
        val extraction = extractionOf(
            """
            void run(boolean flag) { if (flag) { a(); } }
            void a() {}
            """.trimIndent(),
        )
        assertTrue(extraction.controlFlowSimplified)
        assertEquals(listOf("a"), callNames(extraction))
    }

    fun `test lambda bodies are traversal boundaries`() {
        val extraction = extractionOf(
            """
            void run() { java.util.stream.Stream.of(1).map(x -> helper(x)); }
            int helper(int x) { return x; }
            """.trimIndent(),
        )
        // of() and map() are explicit calls; helper() inside the lambda is not part
        // of this frame's synchronous flow (negative control).
        assertEquals(listOf("of", "map"), callNames(extraction))
    }

    fun `test entry point detection inside method and signature`() {
        val file = myFixture.configureByText(
            "Entry.java",
            """
            public class Entry {
                void tar<caret>get() { helper(); }
                void helper() {}
            }
            """.trimIndent(),
        )
        val entry = analyzer.findEntryPoint(file, myFixture.caretOffset)
        assertNotNull(entry)
        assertEquals("target", (entry as com.intellij.psi.PsiMethod).name)
    }

    fun `test no entry point outside any method`() {
        val file = myFixture.configureByText(
            "Entry.java",
            """
            public class Entry {
                int fie<caret>ld;
                void helper() {}
            }
            """.trimIndent(),
        )
        assertNull(analyzer.findEntryPoint(file, myFixture.caretOffset))
    }

    fun `test abstract method is not an entry point`() {
        val file = myFixture.configureByText(
            "Entry.java",
            "public abstract class Entry { abstract void ru<caret>n(); }",
        )
        assertNull(analyzer.findEntryPoint(file, myFixture.caretOffset))
    }

    fun `test registry routes java declarations to the java analyzer`() {
        val file = myFixture.configureByText(
            "Entry.java",
            "public class Entry { void ru<caret>n() { } }",
        )
        val match = FlowAnalyzerRegistry.findEntryPoint(file, myFixture.caretOffset)
        assertNotNull(match)
        assertEquals("java", match!!.first.languageId)
    }
}
