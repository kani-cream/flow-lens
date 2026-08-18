package com.kanicream.flowlens.analysis.go

import com.goide.psi.GoFunctionOrMethodDeclaration
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Two Go directories can both declare `package main`. Keying on the package
 * name alone gave their functions one identity, and that key is what cycle
 * detection compares along a path — so an unrelated `run()` in another command
 * would have been reported as a cycle, and one pin would have marked both.
 */
class GoPackageIdentityTest : BasePlatformTestCase() {

    private val analyzer = GoFlowAnalyzer()

    private fun keyOf(path: String, name: String): String {
        val file = myFixture.addFileToProject(
            path,
            """
            package main

            func $name() { }
            """.trimIndent(),
        )
        val declaration = PsiTreeUtil.findChildrenOfType(file, GoFunctionOrMethodDeclaration::class.java)
            .first { it.name == name }
        return analyzer.describeCallable(declaration).key
    }

    fun `test two package main directories do not share one identity`() {
        val api = keyOf("cmd/api/main.go", "run")
        val worker = keyOf("cmd/worker/main.go", "run")

        assertFalse(
            "cmd/api.run and cmd/worker.run are different functions: $api",
            api == worker,
        )
        assertTrue("the directory is what distinguishes them: $api", api.contains("cmd/api"))
        assertTrue(worker.contains("cmd/worker"))
    }

    fun `test the package name is still what the card shows`() {
        val file = myFixture.addFileToProject(
            "cmd/api/server.go",
            """
            package main

            func serve() { }
            """.trimIndent(),
        )
        val declaration = PsiTreeUtil.findChildrenOfType(file, GoFunctionOrMethodDeclaration::class.java)
            .first { it.name == "serve" }
        val symbol = analyzer.describeCallable(declaration)

        assertEquals("serve()", symbol.displayName)
        assertEquals(
            "the container is the package, which is what a reader recognizes",
            "main",
            symbol.containerName,
        )
    }
}
