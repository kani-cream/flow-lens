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
 * Conditional-execution marking (`V0.1_SPEC.md` §13): calls that may not run must
 * be distinguishable so the renderer never claims a proven path. Calls that always
 * run must NOT be marked — the negative control matters as much as the positive.
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

    fun `test java if branches are conditional but the condition is not`() {
        val flow = javaFlow("if (cond()) { a(); } else { b(); } c();")
        assertEquals(false, flow["cond"])
        assertEquals(true, flow["a"])
        assertEquals(true, flow["b"])
        assertEquals(false, flow["c"])
    }

    fun `test java loop body is conditional while the loop header is not`() {
        val flow = javaFlow("for (int i = 0; i < items.size(); i++) { a(); } b();")
        assertEquals(false, flow["size"])
        assertEquals(true, flow["a"])
        assertEquals(false, flow["b"])
    }

    fun `test java catch section is conditional while try and finally are not`() {
        val flow = javaFlow("try { a(); } catch (RuntimeException e) { b(); } finally { c(); } d();")
        assertEquals(false, flow["a"])
        assertEquals(true, flow["b"])
        assertEquals(false, flow["c"])
        assertEquals(false, flow["d"])
    }

    fun `test java short circuit right operand is conditional`() {
        val flow = javaFlow("boolean x = cond() && cond2(); a();")
        assertEquals(false, flow["cond"])
        assertEquals(true, flow["cond2"])
        assertEquals(false, flow["a"])
    }

    fun `test java switch body is conditional`() {
        val flow = javaFlow("switch (items.size()) { case 1: a(); break; default: b(); } c();")
        assertEquals(false, flow["size"])
        assertEquals(true, flow["a"])
        assertEquals(true, flow["b"])
        assertEquals(false, flow["c"])
    }

    fun `test java ternary branches are conditional`() {
        val flow = javaFlow("int n = cond() ? one() : two(); a();")
        assertEquals(false, flow["cond"])
        assertEquals(true, flow["one"])
        assertEquals(true, flow["two"])
        assertEquals(false, flow["a"])
    }

    fun `test kotlin when and elvis style branches are conditional`() {
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
                e()
            }
            fun subject(): Int = 1
            fun cond(): Boolean = false
            fun a() {} fun b() {} fun c() {} fun d() {} fun e() {}
            """.trimIndent(),
        )
        val fn = PsiTreeUtil.findChildrenOfType(file, KtNamedFunction::class.java).first { it.name == "run" }
        val flow = KotlinFlowAnalyzer().extractDirectFlow(fn).calls
            .associate { it.calleeShortName to it.conditional }
        assertEquals(false, flow["subject"])
        assertEquals(true, flow["a"])
        assertEquals(true, flow["b"])
        assertEquals(true, flow["c"])
        assertEquals(false, flow["cond"])
        assertEquals(true, flow["d"])
        assertEquals(false, flow["e"])
    }

    fun `test go branches and loop bodies are conditional`() {
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
        val calls = GoFlowAnalyzer().extractDirectFlow(fn).calls
        // Regression: Go PSI getters return nested elements, so an identity-based
        // split walked the if condition twice and emitted it as two call events.
        assertEquals(
            listOf("cond", "a", "b", "c", "subject", "d", "e"),
            calls.map { it.calleeShortName },
        )
        val flow = calls.associate { it.calleeShortName to it.conditional }
        assertEquals(false, flow["cond"])
        assertEquals(true, flow["a"])
        assertEquals(true, flow["b"])
        assertEquals(true, flow["c"])
        assertEquals(false, flow["subject"])
        assertEquals(true, flow["d"])
        assertEquals(false, flow["e"])
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
        val flow = calls.associate { it.calleeShortName to it.conditional }
        assertEquals("a while condition always runs at least once", false, flow["cond"])
        assertEquals(true, flow["d"])
        assertEquals(true, flow["e"])
        assertEquals("a do-while body always runs once", false, flow["f"])
        assertEquals(false, flow["cond2"])
    }

    fun `test java do while body and condition always run`() {
        val flow = javaFlow("do { a(); } while (cond()); b();")
        assertEquals(false, flow["a"])
        assertEquals(false, flow["cond"])
        assertEquals(false, flow["b"])
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
        val flow = calls.associate { it.calleeShortName to it.conditional }
        assertEquals("a for-condition runs at least once", false, flow["cond"])
        assertEquals(true, flow["a"])
        assertEquals(false, flow["b"])
    }

    fun `test straight line calls are never marked conditional`() {
        val flow = javaFlow("a(); b(); c();")
        assertEquals(listOf(false, false, false), listOf(flow["a"], flow["b"], flow["c"]))
    }
}
