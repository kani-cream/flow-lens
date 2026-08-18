package com.kanicream.flowlens.analysis

import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.kanicream.flowlens.analysis.go.GoFlowAnalyzer
import com.kanicream.flowlens.analysis.java.JavaFlowAnalyzer
import com.kanicream.flowlens.analysis.kotlin.KotlinFlowAnalyzer
import com.kanicream.flowlens.testutil.RealJdkProjectDescriptor
import com.goide.psi.GoFunctionOrMethodDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * The conditional marker after v0.2 (`V0.2_SPEC.md` §5).
 *
 * Branches, loops, and exception handling are now represented as structures, so
 * a call inside one is no longer marked: its section already says it may be
 * skipped, and marking it too would double the signal. The marker is reserved
 * for what v0.2 does not represent — short-circuit operands, elvis, safe calls —
 * which is exactly where the renderer still needs it.
 */
class ConditionalCallTest : LightJavaCodeInsightFixtureTestCase() {

    override fun getProjectDescriptor(): LightProjectDescriptor = RealJdkProjectDescriptor.INSTANCE

    private fun javaFlow(body: String): Map<String, Boolean> {
        val file = myFixture.configureByText(
            "Sample.java",
            """
            public class Sample {
                void run(boolean flag, java.util.List<String> items) { $body }
                boolean cond() { return true; }
                void a() { } void b() { } void c() { } void d() { }
            }
            """.trimIndent(),
        )
        val method = PsiTreeUtil.findChildrenOfType(file, PsiMethod::class.java).first { it.name == "run" }
        return JavaFlowAnalyzer().extractDirectFlow(method).calls
            .associate { it.calleeShortName to it.conditional }
    }

    fun `test calls inside represented structures are not marked`() {
        for (body in listOf(
            "if (cond()) { a(); } else { b(); }",
            "for (int i = 0; i < items.size(); i++) { a(); }",
            "switch (items.size()) { case 1: a(); break; default: b(); }",
            "try { a(); } catch (RuntimeException e) { b(); } finally { c(); }",
            "int n = cond() ? one() : two();",
        )) {
            val flow = javaFlow(body)
            assertTrue(
                "no call in `$body` should carry the marker: the structure says it",
                flow.values.none { it },
            )
        }
    }

    fun `test kotlin calls inside represented structures are not marked`() {
        val file = myFixture.configureByText(
            "sample.kt",
            """
            fun run(flag: Boolean) {
                when (subject()) {
                    1 -> a()
                    else -> b()
                }
                if (flag) c()
                while (cond()) { d() }
                try { e() } catch (t: Throwable) { f() }
            }
            fun subject(): Int = 1
            fun cond(): Boolean = false
            fun a() {} fun b() {} fun c() {} fun d() {} fun e() {} fun f() {}
            """.trimIndent(),
        )
        val fn = PsiTreeUtil.findChildrenOfType(file, KtNamedFunction::class.java).first { it.name == "run" }
        val extraction = KotlinFlowAnalyzer().extractDirectFlow(fn)
        assertTrue(extraction.calls.none { it.conditional })
        assertFalse(
            "when, if, while and try are all represented now",
            extraction.controlFlowSimplified,
        )
    }

    fun `test go calls inside represented structures are not marked`() {
        val file = myFixture.configureByText(
            "sample.go",
            """
            package sample

            func run(items []int) {
                if cond() {
                    a()
                } else {
                    b()
                }
                for _, v := range items {
                    _ = v
                    c()
                }
                switch subject() {
                case 1:
                    d()
                }
                e()
            }

            func cond() bool { return true }
            func subject() int { return 1 }
            func a() {}
            func b() {}
            func c() {}
            func d() {}
            func e() {}
            """.trimIndent(),
        )
        val fn = PsiTreeUtil.findChildrenOfType(file, GoFunctionOrMethodDeclaration::class.java)
            .first { it.name == "run" }
        val extraction = GoFlowAnalyzer().extractDirectFlow(fn)
        // Regression: Go PSI getters return nested elements, so an identity-based
        // split walked the if condition twice and emitted it as two call events.
        assertEquals(
            listOf("cond", "a", "b", "c", "subject", "d", "e"),
            extraction.calls.map { it.calleeShortName },
        )
        assertTrue(extraction.calls.none { it.conditional })
        assertFalse(extraction.controlFlowSimplified)
    }

    fun `test kotlin loop bodies are extracted exactly once`() {
        // Regression: KtLoopExpression.getBody() returns a grandchild, so an
        // identity-based split walked the body twice and emitted every call in a
        // loop as two events, doubling cards and node-budget consumption.
        val file = myFixture.configureByText(
            "loops.kt",
            """
            fun run(items: List<Int>) {
                while (cond()) { d() }
                for (i in items) { e() }
                do { f() } while (cond2())
            }
            fun cond(): Boolean = false
            fun cond2(): Boolean = false
            fun d() {} fun e() {} fun f() {}
            """.trimIndent(),
        )
        val fn = PsiTreeUtil.findChildrenOfType(file, KtNamedFunction::class.java).first { it.name == "run" }
        val calls = KotlinFlowAnalyzer().extractDirectFlow(fn).calls
        assertEquals(
            listOf("cond", "d", "e", "f", "cond2"),
            calls.map { it.calleeShortName },
        )
    }

    fun `test java do while body and condition are inside one container`() {
        val flow = javaFlow("do { a(); } while (cond()); b();")
        assertTrue("a loop container carries the repetition, not the marker", flow.values.none { it })
    }

    fun `test go while style loop condition always runs`() {
        val file = myFixture.configureByText(
            "loop.go",
            """
            package sample

            func run() {
                for cond() {
                    a()
                }
                b()
            }

            func cond() bool { return false }
            func a() {}
            func b() {}
            """.trimIndent(),
        )
        val fn = PsiTreeUtil.findChildrenOfType(file, GoFunctionOrMethodDeclaration::class.java)
            .first { it.name == "run" }
        val calls = GoFlowAnalyzer().extractDirectFlow(fn).calls
        assertEquals(listOf("cond", "a", "b"), calls.map { it.calleeShortName })
        assertTrue(calls.none { it.conditional })
    }

    fun `test kotlin elvis and safe calls are conditional`() {
        // The known limitations promise that a short-circuit right operand is
        // marked; elvis short-circuits exactly like && and ||, and a safe call
        // runs only when the receiver is not null.
        val file = myFixture.configureByText(
            "nullable.kt",
            """
            fun run(cached: String?, box: Box?) {
                val value = cached ?: expensiveLoad()
                box?.touch()
                done()
            }
            class Box { fun touch() {} }
            fun expensiveLoad(): String = ""
            fun done() {}
            """.trimIndent(),
        )
        val fn = PsiTreeUtil.findChildrenOfType(file, KtNamedFunction::class.java).first { it.name == "run" }
        val flow = KotlinFlowAnalyzer().extractDirectFlow(fn).calls
            .associate { it.calleeShortName to it.conditional }
        assertEquals(true, flow["expensiveLoad"])
        assertEquals(true, flow["touch"])
        assertEquals(false, flow["done"])
    }

    fun `test straight line calls are never marked conditional`() {
        val flow = javaFlow("a(); b(); c();")
        assertEquals(listOf(false, false, false), listOf(flow["a"], flow["b"], flow["c"]))
    }
}
