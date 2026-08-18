package com.kanicream.flowlens.analysis

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.kanicream.flowlens.testutil.RealJdkProjectDescriptor
import org.jetbrains.kotlin.psi.KtNamedFunction

/**
 * A symbol key has to be unique within a project. It is what cycle detection
 * compares along a path and what a Flow Pin is stored under, so two unrelated
 * functions sharing one key make the analyzer report a cycle between them and
 * make one pin mark both.
 */
class SymbolIdentityTest : LightJavaCodeInsightFixtureTestCase() {

    override fun getProjectDescriptor(): LightProjectDescriptor = RealJdkProjectDescriptor.INSTANCE

    private fun javaKeyOf(path: String, text: String, method: String): String {
        val file = myFixture.addFileToProject(path, text)
        return runReadActionBlocking {
            val declaration = PsiTreeUtil.findChildrenOfType(file, PsiMethod::class.java)
                .first { it.name == method }
            FlowAnalyzerRegistry.forDeclaration(declaration)!!.describeCallable(declaration).key
        }
    }

    fun `test two same-named classes in different packages do not share a key`() {
        // Both fall back to the file qualifier when the class has no qualified
        // name of its own; a bare file name repeats across packages.
        val first = javaKeyOf(
            "one/Anon.java",
            """
            package one;
            public class Anon {
                Runnable make() { return new Runnable() { public void run() { } }; }
            }
            """.trimIndent(),
            "run",
        )
        val second = javaKeyOf(
            "two/Anon.java",
            """
            package two;
            public class Anon {
                Runnable make() { return new Runnable() { public void run() { } }; }
            }
            """.trimIndent(),
            "run",
        )
        assertFalse(
            "two anonymous run() in different packages would otherwise read as one callable",
            first == second,
        )
    }

    fun `test a qualified class still keys on its qualified name`() {
        val key = javaKeyOf(
            "demo/Service.java",
            """
            package demo;
            public class Service { void charge(int amount) { } }
            """.trimIndent(),
            "charge",
        )
        assertEquals("java:demo.Service#charge(int)", key)
    }

    fun `test a kotlin local function is not an entry point`() {
        val file = myFixture.configureByText(
            "local.kt",
            """
            fun parent() {
                fun helper() { work() }
                helper()
            }
            fun work() { }
            """.trimIndent(),
        )
        val analyzer = com.kanicream.flowlens.analysis.kotlin.KotlinFlowAnalyzer()
        runReadActionBlocking {
            val local = PsiTreeUtil.findChildrenOfType(file, KtNamedFunction::class.java)
                .first { it.name == "helper" }
            assertNull(
                "the extractor treats a nested function as a boundary, so it must " +
                    "not become a root either",
                analyzer.findEntryPoint(file, local.textOffset + 4),
            )
            val top = PsiTreeUtil.findChildrenOfType(file, KtNamedFunction::class.java)
                .first { it.name == "parent" }
            assertNotNull(
                "a top-level function is still an entry point",
                analyzer.findEntryPoint(file, top.textOffset + 4),
            )
        }
    }
}
