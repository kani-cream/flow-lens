package com.kanicream.flowlens.analysis.java

import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.kanicream.flowlens.analysis.ExtractedBranch
import com.kanicream.flowlens.analysis.ExtractedCall
import com.kanicream.flowlens.analysis.ExtractedStructure
import com.kanicream.flowlens.analysis.ExtractedTerminator
import com.kanicream.flowlens.analysis.FlowItem
import com.kanicream.flowlens.core.model.BranchKind
import com.kanicream.flowlens.core.model.FlowNodeKind
import com.kanicream.flowlens.service.FlowMetadata
import com.kanicream.flowlens.testutil.RealJdkProjectDescriptor

/**
 * Java control-flow structure (`V0.2_SPEC.md` §8). What runs before a structure
 * stays before it; what repeats or is chosen between lives inside it.
 */
class JavaStructureExtractionTest : LightJavaCodeInsightFixtureTestCase() {

    private val analyzer = JavaFlowAnalyzer()

    override fun getProjectDescriptor(): LightProjectDescriptor = RealJdkProjectDescriptor.INSTANCE

    private fun itemsOf(body: String): List<FlowItem> {
        val file = myFixture.configureByText(
            "Sample.java",
            """
            public class Sample {
                void run(boolean flag, java.util.List<String> items) { $body }
                boolean cond() { return true; }
                java.util.List<String> all() { return items; }
                int subject() { return 1; }
                int one() { return 1; }
                void a() { } void b() { } void c() { } void d() { }
            }
            """.trimIndent(),
        )
        val method = PsiTreeUtil.findChildrenOfType(file, PsiMethod::class.java).first { it.name == "run" }
        return analyzer.extractDirectFlow(method).items
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

    fun `test A an if becomes a condition with then and else`() {
        val items = itemsOf("if (cond()) { a(); } else { b(); } d();")
        assertEquals(listOf("cond", "CONDITION", "d"), shape(items))
        val condition = structure(items)
        assertEquals("cond()", condition.summary)
        assertEquals(
            listOf("THEN=[a]", "ELSE=[b]"),
            condition.branches.map(::branchShape),
        )
    }

    fun `test B an if without else has only a then branch`() {
        val items = itemsOf("if (flag) { a(); } d();")
        val condition = structure(items)
        assertEquals(listOf("THEN=[a]"), condition.branches.map(::branchShape))
        assertEquals(listOf("CONDITION", "d"), shape(items))
    }

    fun `test C a switch keeps its case labels and default`() {
        val items = itemsOf("switch (subject()) { case 1: a(); break; default: b(); }")
        assertEquals(listOf("subject", "SWITCH"), shape(items))
        val switch = structure(items)
        assertEquals("subject()", switch.summary)
        assertEquals(listOf("CASE(1)=[a]", "DEFAULT=[b]"), switch.branches.map(::branchShape))
    }

    fun `test E a loop keeps its repeated condition inside the body`() {
        val items = itemsOf("for (int i = 0; i < one(); i++) { a(); } d();")
        assertEquals(listOf("LOOP", "d"), shape(items))
        val loop = structure(items)
        assertEquals(
            "the condition repeats, so it belongs inside the container",
            listOf("BODY=[one, a]"),
            loop.branches.map(::branchShape),
        )
    }

    fun `test F a for-each evaluates its iterable once before the loop`() {
        val items = itemsOf("for (String s : all()) { a(); }")
        assertEquals(
            "the iterable is evaluated once, so it is not inside the container",
            listOf("all", "LOOP"),
            shape(items),
        )
        assertEquals(listOf("BODY=[a]"), structure(items).branches.map(::branchShape))
    }

    fun `test G a do-while records that its body runs at least once`() {
        val items = itemsOf("do { a(); } while (cond());")
        val loop = structure(items)
        assertEquals(listOf("BODY=[a, cond]"), loop.branches.map(::branchShape))
        assertEquals("true", loop.metadata[FlowMetadata.LOOP_RUNS_AT_LEAST_ONCE])
    }

    fun `test H a try keeps its catch types and finally`() {
        val items = itemsOf(
            "try { a(); } catch (RuntimeException e) { b(); } finally { c(); } d();",
        )
        assertEquals(listOf("TRY", "d"), shape(items))
        assertEquals(
            listOf("TRY=[a]", "CATCH(RuntimeException)=[b]", "FINALLY=[c]"),
            structure(items).branches.map(::branchShape),
        )
    }

    fun `test I a return terminates after its expression`() {
        val items = itemsOf("a(); return;")
        assertEquals(listOf("a", "RETURN"), shape(items))
    }

    fun `test a return says what it hands back`() {
        val bare = itemsOf("return;").filterIsInstance<ExtractedTerminator>().single()
        assertNull("nothing is handed back, so there is nothing to say", bare.summary)

        val valued = itemsOf("if (flag) { return; } return one();")
        val terminators = valued.filterIsInstance<ExtractedTerminator>()
        assertEquals(
            "the two returns stop the path for different reasons",
            "one()",
            terminators.single().summary,
        )
        val inBranch = structure(valued).branches.single()
            .items.filterIsInstance<ExtractedTerminator>().single()
        assertNull(inBranch.summary)
    }

    fun `test a throw says what it throws`() {
        val items = itemsOf("throw new IllegalStateException();")
        val terminator = items.filterIsInstance<ExtractedTerminator>().single()
        assertEquals("new IllegalStateException()", terminator.summary)
    }

    fun `test J a throw evaluates its expression first`() {
        val items = itemsOf("throw new IllegalStateException(String.valueOf(one()));")
        assertEquals(listOf("one", "valueOf", "IllegalStateException", "THROW"), shape(items))
    }

    fun `test M structures nest`() {
        val items = itemsOf("for (String s : items) { if (flag) { a(); } }")
        val loop = structure(items)
        val body = loop.branches.single()
        assertEquals(listOf("CONDITION"), shape(body.items))
        val nested = body.items.filterIsInstance<ExtractedStructure>().single()
        assertEquals(listOf("THEN=[a]"), nested.branches.map(::branchShape))
    }

    fun `test N a call inside a branch is not marked conditional`() {
        // The section already says the call may be skipped, so marking it too
        // would double the signal (`V0.2_SPEC.md` §5).
        val items = itemsOf("if (flag) { a(); }")
        val call = structure(items).branches.single().items.filterIsInstance<ExtractedCall>().single()
        assertFalse(call.conditional)
    }

    fun `test O a short circuit operand is still marked and disclosed`() {
        val file = myFixture.configureByText(
            "Short.java",
            """
            public class Short {
                void run() { boolean x = cond() && other(); }
                boolean cond() { return true; }
                boolean other() { return true; }
            }
            """.trimIndent(),
        )
        val method = PsiTreeUtil.findChildrenOfType(file, PsiMethod::class.java).first { it.name == "run" }
        val extraction = analyzer.extractDirectFlow(method)
        val calls = extraction.items.filterIsInstance<ExtractedCall>()
        assertEquals(listOf(false, true), calls.map { it.conditional })
        assertTrue("what is not represented stays disclosed", extraction.controlFlowSimplified)
    }

    fun `test P represented control flow is no longer reported as simplified`() {
        val file = myFixture.configureByText(
            "Clean.java",
            """
            public class Clean {
                void run(boolean flag) {
                    if (flag) { a(); } else { b(); }
                    for (int i = 0; i < 3; i++) { c(); }
                }
                void a() { } void b() { } void c() { }
            }
            """.trimIndent(),
        )
        val method = PsiTreeUtil.findChildrenOfType(file, PsiMethod::class.java).first { it.name == "run" }
        assertFalse(
            "an if and a loop are represented now, so nothing is simplified",
            analyzer.extractDirectFlow(method).controlFlowSimplified,
        )
    }

    fun `test Q an empty case is kept as an empty branch`() {
        val items = itemsOf("switch (subject()) { case 1: break; default: b(); }")
        val branches = structure(items).branches
        assertEquals(BranchKind.CASE, branches[0].kind)
        assertTrue("a case that does nothing is still a case", branches[0].items.isEmpty())
        assertFalse(branches[1].items.isEmpty())
    }

    fun `test a switch whose cases all break discloses nothing`() {
        // The spec's own case C example. The `break` is the case boundary, which
        // the map draws, so it is not unrepresented flow (`V0.2_SPEC.md` §6).
        val file = myFixture.configureByText(
            "Cases.java",
            """
            public class Cases {
                void run(int k) {
                    switch (k) { case 1: a(); break; default: b(); }
                }
                void a() { } void b() { }
            }
            """.trimIndent(),
        )
        val method = PsiTreeUtil.findChildrenOfType(file, PsiMethod::class.java).first { it.name == "run" }
        assertFalse(analyzer.extractDirectFlow(method).controlFlowSimplified)
    }

    fun `test a case that falls through is disclosed`() {
        val file = myFixture.configureByText(
            "Fall.java",
            """
            public class Fall {
                void run(int k) {
                    switch (k) { case 1: a(); case 2: b(); break; }
                }
                void a() { } void b() { }
            }
            """.trimIndent(),
        )
        val method = PsiTreeUtil.findChildrenOfType(file, PsiMethod::class.java).first { it.name == "run" }
        assertTrue(
            "case 1 runs on into case 2, and the sections do not say so",
            analyzer.extractDirectFlow(method).controlFlowSimplified,
        )
    }

    fun `test an empty case falling into the next one is not a disclosure`() {
        // `case 1: case 2: a();` is one grouped label, not hidden flow.
        val file = myFixture.configureByText(
            "Grouped.java",
            """
            public class Grouped {
                void run(int k) {
                    switch (k) { case 1: case 2: a(); break; }
                }
                void a() { }
            }
            """.trimIndent(),
        )
        val method = PsiTreeUtil.findChildrenOfType(file, PsiMethod::class.java).first { it.name == "run" }
        assertFalse(analyzer.extractDirectFlow(method).controlFlowSimplified)
    }

    fun `test a condition without a call discloses nothing`() {
        val file = myFixture.configureByText(
            "Plain.java",
            """
            public class Plain {
                void run(String s, int n) { if (s != null && n > 0) { a(); } }
                void a() { }
            }
            """.trimIndent(),
        )
        val method = PsiTreeUtil.findChildrenOfType(file, PsiMethod::class.java).first { it.name == "run" }
        assertFalse(
            "a short circuit that skips no call hides nothing",
            analyzer.extractDirectFlow(method).controlFlowSimplified,
        )
    }

    fun `test a break keeps the result disclosed as simplified`() {
        val file = myFixture.configureByText(
            "Jump.java",
            """
            public class Jump {
                void run() { for (int i = 0; i < 3; i++) { if (i == 1) break; a(); } }
                void a() { }
            }
            """.trimIndent(),
        )
        val method = PsiTreeUtil.findChildrenOfType(file, PsiMethod::class.java).first { it.name == "run" }
        assertTrue(analyzer.extractDirectFlow(method).controlFlowSimplified)
    }

    fun `test a case guard is on the map`() {
        val file = myFixture.configureByText(
            "Guard.java",
            """
            public class Guard {
                void run(Object o) {
                    switch (o) { case Integer i when check(i) -> a(); default -> b(); }
                }
                boolean check(int i) { return true; }
                void a() { } void b() { }
            }
            """.trimIndent(),
        )
        val method = PsiTreeUtil.findChildrenOfType(file, PsiMethod::class.java).first { it.name == "run" }
        val switch = analyzer.extractDirectFlow(method).items
            .filterIsInstance<ExtractedStructure>().single()
        assertEquals(
            "the guard is evaluated to choose the case, so it is inside it",
            listOf("check", "a"),
            shape(switch.branches[0].items),
        )
    }

    fun `test an arrow switch discloses nothing because rules cannot fall through`() {
        val file = myFixture.configureByText(
            "Arrow.java",
            """
            public class Arrow {
                void run(int k) {
                    switch (k) { case 1 -> a(); default -> b(); }
                }
                void a() { } void b() { }
            }
            """.trimIndent(),
        )
        val method = PsiTreeUtil.findChildrenOfType(file, PsiMethod::class.java).first { it.name == "run" }
        assertFalse(analyzer.extractDirectFlow(method).controlFlowSimplified)
    }

    fun `test a braced case that breaks discloses nothing`() {
        val file = myFixture.configureByText(
            "Braced.java",
            """
            public class Braced {
                void run(int k) {
                    switch (k) { case 1: { a(); break; } default: b(); }
                }
                void a() { } void b() { }
            }
            """.trimIndent(),
        )
        val method = PsiTreeUtil.findChildrenOfType(file, PsiMethod::class.java).first { it.name == "run" }
        assertFalse(
            "the case leaves through the last statement of its block",
            analyzer.extractDirectFlow(method).controlFlowSimplified,
        )
    }

    fun `test an arrow switch keeps each rule as its own branch`() {
        val items = itemsOf("switch (subject()) { case 1 -> a(); default -> b(); }")
        assertEquals(
            listOf("CASE(1)=[a]", "DEFAULT=[b]"),
            structure(items).branches.map(::branchShape),
        )
    }
}
