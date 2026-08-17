package com.kanicream.flowlens.analysis

import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.kanicream.flowlens.analysis.java.JavaFlowAnalyzer
import com.kanicream.flowlens.analysis.kotlin.KotlinFlowAnalyzer
import com.kanicream.flowlens.core.model.DispatchConfidence
import com.kanicream.flowlens.core.model.FlowNodeKind
import com.kanicream.flowlens.testutil.RealJdkProjectDescriptor
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * Explicit constructor invocation and explicit superclass dispatch
 * (`V0.1_SPEC.md` §5 Java, §8 exact dispatch).
 */
class ExplicitConstructorCallTest : LightJavaCodeInsightFixtureTestCase() {

    private val java = JavaFlowAnalyzer()

    override fun getProjectDescriptor(): LightProjectDescriptor = RealJdkProjectDescriptor.INSTANCE

    private fun methodOf(text: String, name: String, fileName: String = "Sample.java"): PsiMethod {
        val file = myFixture.configureByText(fileName, text)
        return PsiTreeUtil.findChildrenOfType(file, PsiMethod::class.java).first {
            if (name == "<init>") it.isConstructor else it.name == name
        }
    }

    fun `test explicit this constructor invocation is an exact constructor event`() {
        val ctor = methodOf(
            """
            public class Sample {
                Sample() { this(1); }
                Sample(int v) { helper(); }
                void helper() { }
            }
            """.trimIndent(),
            "<init>",
        )
        val call = java.extractDirectFlow(ctor).calls.single()
        val target = java.resolveCall(call)
        assertTrue("this(...) must be a constructor event", target.isConstructor)
        assertEquals(DispatchConfidence.EXACT, target.dispatchConfidence)
        assertTrue("the delegated constructor body is analyzable", target.hasAnalyzableBody)
    }

    fun `test explicit super constructor invocation resolves to the base constructor`() {
        myFixture.addFileToProject(
            "Base.java",
            "public class Base { Base(int v) { init(); } void init() { } }",
        )
        val ctor = methodOf(
            """
            public class Child extends Base {
                Child() { super(2); }
            }
            """.trimIndent(),
            "<init>",
            fileName = "Child.java",
        )
        val call = java.extractDirectFlow(ctor).calls.single()
        val target = java.resolveCall(call)
        assertTrue(target.isConstructor)
        assertEquals(DispatchConfidence.EXACT, target.dispatchConfidence)
        assertEquals("Base()", target.symbol!!.displayName)
    }

    fun `test explicit super method call is exact rather than declared target`() {
        myFixture.addFileToProject(
            "Base.java",
            "public class Base { public void work() { } }",
        )
        val method = methodOf(
            """
            public class Child extends Base {
                @Override public void work() { super.work(); }
            }
            """.trimIndent(),
            "work",
            fileName = "Child.java",
        )
        val call = java.extractDirectFlow(method).calls.single()
        val target = java.resolveCall(call)
        assertEquals(DispatchConfidence.EXACT, target.dispatchConfidence)
    }

    fun `test constructor entry point reports constructor events in body order`() {
        val ctor = methodOf(
            """
            public class Sample {
                Sample() { super(); prepare(); new Helper(); }
                void prepare() { }
                static class Helper { }
            }
            """.trimIndent(),
            "<init>",
        )
        val extraction = java.extractDirectFlow(ctor)
        assertEquals(listOf("super", "prepare", "Helper"), extraction.calls.map { it.calleeShortName })
        assertEquals(FlowNodeKind.CONSTRUCTOR, extraction.calls.last().kind)
    }

    fun `test kotlin explicit super call is exact`() {
        val file = myFixture.configureByText(
            "sample.kt",
            """
            open class Base { open fun work() { } }
            class Child : Base() {
                override fun work() { super.work() }
            }
            """.trimIndent(),
        )
        val fn = PsiTreeUtil.findChildrenOfType(file, KtNamedFunction::class.java)
            .first { it.name == "work" && it.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.OVERRIDE_KEYWORD) }
        val kotlin = KotlinFlowAnalyzer()
        val target = kotlin.resolveCall(kotlin.extractDirectFlow(fn).calls.single())
        assertEquals(DispatchConfidence.EXACT, target.dispatchConfidence)
    }

    fun `test kotlin ordinary open call stays declared target`() {
        val file = myFixture.configureByText(
            "other.kt",
            """
            open class Service { open fun work() { } }
            fun run(s: Service) { s.work() }
            """.trimIndent(),
        )
        val fn = PsiTreeUtil.findChildrenOfType(file, KtNamedFunction::class.java).first { it.name == "run" }
        val kotlin = KotlinFlowAnalyzer()
        val target = kotlin.resolveCall(kotlin.extractDirectFlow(fn).calls.single())
        assertEquals(DispatchConfidence.DECLARED_TARGET, target.dispatchConfidence)
    }
}
