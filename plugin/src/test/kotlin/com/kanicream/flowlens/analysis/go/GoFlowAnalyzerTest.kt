package com.kanicream.flowlens.analysis.go

import com.goide.psi.GoFunctionOrMethodDeclaration
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.kanicream.flowlens.analysis.DirectFlowExtraction
import com.kanicream.flowlens.analysis.FlowAnalyzerRegistry
import com.kanicream.flowlens.core.model.DispatchConfidence
import com.kanicream.flowlens.core.model.ExecutionMode
import com.kanicream.flowlens.core.model.ResolutionStatus

/**
 * Go analyzer against real Go PSI (Go plugin loaded in the test IDE): package
 * functions, receiver methods, evaluation order, and go/defer execution modes.
 */
class GoFlowAnalyzerTest : BasePlatformTestCase() {

    private val analyzer = GoFlowAnalyzer()

    private fun extractionOf(fileText: String, functionName: String = "run"): DirectFlowExtraction {
        val file = myFixture.configureByText("sample.go", fileText)
        val function = PsiTreeUtil
            .findChildrenOfType(file, GoFunctionOrMethodDeclaration::class.java)
            .first { it.name == functionName }
        return analyzer.extractDirectFlow(function)
    }

    private fun callNames(extraction: DirectFlowExtraction): List<String> =
        extraction.calls.map { it.calleeShortName }

    fun `test package function call resolves exactly`() {
        val extraction = extractionOf(
            """
            package sample

            func run() { helper() }
            func helper() { }
            """.trimIndent(),
        )
        val target = analyzer.resolveCall(extraction.calls.single())
        assertEquals(DispatchConfidence.EXACT, target.dispatchConfidence)
        assertEquals(ResolutionStatus.PROJECT_LOCAL, target.resolutionStatus)
        assertTrue(target.hasAnalyzableBody)
        assertEquals("go", target.symbol!!.languageId)
    }

    fun `test receiver method call resolves with receiver symbol`() {
        val extraction = extractionOf(
            """
            package sample

            type Server struct{}

            func (s *Server) Start() { }

            func run(s *Server) { s.Start() }
            """.trimIndent(),
        )
        val target = analyzer.resolveCall(extraction.calls.single())
        assertEquals(DispatchConfidence.EXACT, target.dispatchConfidence)
        assertEquals("Server.Start()", target.symbol!!.displayName)
        assertTrue(target.hasAnalyzableBody)
    }

    fun `test nested calls follow evaluation order`() {
        val extraction = extractionOf(
            """
            package sample

            func load() string { return "" }
            func convert(s string) string { return s }
            func save(s string) { }

            func run() { save(convert(load())) }
            """.trimIndent(),
        )
        assertEquals(listOf("load", "convert", "save"), callNames(extraction))
    }

    fun `test go statement preserves goroutine execution mode`() {
        val extraction = extractionOf(
            """
            package sample

            func notify() { }

            func run() { go notify() }
            """.trimIndent(),
        )
        val call = extraction.calls.single()
        assertEquals(ExecutionMode.GOROUTINE, call.executionMode)
    }

    fun `test defer statement preserves deferred execution mode`() {
        val extraction = extractionOf(
            """
            package sample

            func cleanup() { }

            func run() { defer cleanup() }
            """.trimIndent(),
        )
        assertEquals(ExecutionMode.DEFERRED, extraction.calls.single().executionMode)
    }

    fun `test deferred call argument evaluates immediately in order`() {
        val extraction = extractionOf(
            """
            package sample

            func produce() int { return 1 }
            func consume(x int) { }

            func run() { defer consume(produce()) }
            """.trimIndent(),
        )
        assertEquals(listOf("produce", "consume"), callNames(extraction))
        assertEquals(ExecutionMode.SYNC, extraction.calls[0].executionMode)
        assertEquals(ExecutionMode.DEFERRED, extraction.calls[1].executionMode)
    }

    fun `test function literal bodies are traversal boundaries`() {
        val extraction = extractionOf(
            """
            package sample

            func helper() { }

            func run() {
                f := func() { helper() }
                f()
            }
            """.trimIndent(),
        )
        // helper() inside the literal must not appear; the f() invocation does.
        assertEquals(listOf("f"), callNames(extraction))
    }

    fun `test a switch is represented rather than reported as simplified`() {
        val extraction = extractionOf(
            """
            package sample

            func a() { }

            func run(x int) {
                switch x {
                case 1:
                    a()
                }
            }
            """.trimIndent(),
        )
        assertFalse("v0.2 represents the switch itself", extraction.controlFlowSimplified)
        assertEquals(listOf("a"), callNames(extraction))
    }

    fun `test entry point detection inside package function`() {
        val file = myFixture.configureByText(
            "entry.go",
            """
            package sample

            func tar<caret>get() { helper() }
            func helper() { }
            """.trimIndent(),
        )
        val entry = analyzer.findEntryPoint(file, myFixture.caretOffset)
        assertNotNull(entry)
        assertEquals("target", (entry as GoFunctionOrMethodDeclaration).name)
    }

    fun `test closure body is not an entry point`() {
        val file = myFixture.configureByText(
            "entry.go",
            """
            package sample

            func outer() {
                f := func() { print<caret>ln("x") }
                f()
            }
            """.trimIndent(),
        )
        assertNull(analyzer.findEntryPoint(file, myFixture.caretOffset))
    }

    fun `test a method is named by its receiver and scoped to its package`() {
        // Regression: the receiver used to be the container as well as part of the
        // name, so the canvas printed `Server.Server.Start()`; and a same-package
        // function then looked like it crossed a package boundary.
        val file = myFixture.configureByText(
            "scope.go",
            """
            package sample

            type Server struct{}

            func (s *Server) Start() { helper() }

            func helper() { }

            func run(s *Server) { s.Start() }
            """.trimIndent(),
        )
        val declarations = PsiTreeUtil
            .findChildrenOfType(file, GoFunctionOrMethodDeclaration::class.java)
        val method = declarations.first { it.name == "Start" }
        val function = declarations.first { it.name == "helper" }

        val methodSymbol = analyzer.describeCallable(method)
        assertEquals("Server.Start()", methodSymbol.displayName)
        assertEquals(
            "the scope a Go call can leave is the package, not the receiver",
            "sample",
            methodSymbol.containerName,
        )

        val functionSymbol = analyzer.describeCallable(function)
        assertEquals("helper()", functionSymbol.displayName)
        assertEquals("sample", functionSymbol.containerName)
        assertFalse(
            "same-package callables share a container so neither gets qualified",
            methodSymbol.containerName != functionSymbol.containerName,
        )
    }

    fun `test go analyzer is registered through the optional descriptor`() {
        assertTrue(FlowAnalyzerRegistry.availableLanguageIds().contains("go"))
    }
}
