package com.kanicream.flowlens.analysis.go

import com.goide.psi.GoFunctionOrMethodDeclaration
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.kanicream.flowlens.analysis.ExtractedBranch
import com.kanicream.flowlens.analysis.ExtractedCall
import com.kanicream.flowlens.analysis.ExtractedStructure
import com.kanicream.flowlens.analysis.ExtractedTerminator
import com.kanicream.flowlens.analysis.FlowItem
import com.kanicream.flowlens.service.FlowMetadata

/** Go control-flow structure (`V0.2_SPEC.md` §8, cases K and L). */
class GoStructureExtractionTest : BasePlatformTestCase() {

    private val analyzer = GoFlowAnalyzer()

    private val helpers = """
        func cond() bool { return true }
        func subject() int { return 1 }
        func items() []string { return nil }
        func a() { }
        func b() { }
        func c() { }
        func d() { }
    """.trimIndent()

    private fun itemsOf(body: String, decls: String = ""): List<FlowItem> {
        val file = myFixture.configureByText(
            "sample.go",
            "package sample\n\nfunc run(flag bool, ch chan int) {\n$body\n}\n\n$helpers\n$decls",
        )
        val function = PsiTreeUtil.findChildrenOfType(file, GoFunctionOrMethodDeclaration::class.java)
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

    fun `test an if becomes a condition with then and else`() {
        val items = itemsOf("if cond() { a() } else { b() }\nd()")
        assertEquals(listOf("cond", "CONDITION", "d"), shape(items))
        assertEquals(listOf("THEN=[a]", "ELSE=[b]"), structure(items).branches.map(::branchShape))
    }

    fun `test an if init statement runs before the condition node`() {
        val items = itemsOf("if x := subject(); x > 0 { a() }")
        assertEquals(listOf("subject", "CONDITION"), shape(items))
        assertEquals(listOf("THEN=[a]"), structure(items).branches.map(::branchShape))
    }

    fun `test K a switch keeps its init and subject before the node`() {
        val items = itemsOf("switch x := subject(); x {\ncase 1:\na()\ndefault:\nb()\n}")
        assertEquals(listOf("subject", "SWITCH"), shape(items))
        assertEquals(
            listOf("CASE(1)=[a]", "DEFAULT=[b]"),
            structure(items).branches.map(::branchShape),
        )
        assertEquals(
            "the init already has its own card before the container",
            "x",
            structure(items).summary,
        )
    }

    fun `test L a select is a multi-way branch marked as a select`() {
        val items = itemsOf("select {\ncase <-ch:\na()\ndefault:\nb()\n}")
        val select = structure(items)
        assertEquals(listOf("SWITCH"), shape(items))
        assertEquals("true", select.metadata[FlowMetadata.SELECT])
        assertEquals(listOf("CASE", "DEFAULT"), select.branches.map { it.kind.name })
        assertEquals(listOf("a"), shape(select.branches[0].items))
    }

    fun `test a for loop keeps its repeated condition inside the body`() {
        val items = itemsOf("for cond() { a() }\nd()")
        assertEquals(listOf("LOOP", "d"), shape(items))
        assertEquals(listOf("BODY=[cond, a]"), structure(items).branches.map(::branchShape))
    }

    fun `test F a range loop evaluates its range once before the loop`() {
        val items = itemsOf("for _, s := range items() {\n_ = s\na()\n}")
        assertEquals(listOf("items", "LOOP"), shape(items))
        assertEquals(listOf("BODY=[a]"), structure(items).branches.map(::branchShape))
    }

    fun `test I a return terminates after its expression`() {
        val items = itemsOf("a()\nreturn")
        assertEquals(listOf("a", "RETURN"), shape(items))
        assertNull(items.filterIsInstance<ExtractedTerminator>().single().summary)
    }

    fun `test a return lists every value it hands back`() {
        val file = myFixture.configureByText(
            "multi.go",
            """
            package sample

            func run() (int, error) { return subject(), nil }
            func subject() int { return 1 }
            """.trimIndent(),
        )
        val function = PsiTreeUtil.findChildrenOfType(file, GoFunctionOrMethodDeclaration::class.java)
            .first { it.name == "run" }
        val terminator = analyzer.extractDirectFlow(function).items
            .filterIsInstance<ExtractedTerminator>().single()
        assertEquals(
            "a Go function returns several values at once",
            "subject(), nil",
            terminator.summary,
        )
    }

    fun `test M structures nest`() {
        val items = itemsOf("for _, s := range items() {\n_ = s\nif flag { a() }\n}")
        val body = structure(items).branches.single()
        val nested = body.items.filterIsInstance<ExtractedStructure>().single()
        assertEquals(listOf("THEN=[a]"), nested.branches.map(::branchShape))
    }

    fun `test N a call inside a branch is not marked conditional`() {
        val items = itemsOf("if flag { a() }")
        val call = structure(items).branches.single().items.filterIsInstance<ExtractedCall>().single()
        assertFalse(call.conditional)
    }

    fun `test O a short circuit operand is still marked and disclosed`() {
        val file = myFixture.configureByText(
            "short.go",
            """
            package sample

            func run() { _ = cond() && other() }
            func cond() bool { return true }
            func other() bool { return true }
            """.trimIndent(),
        )
        val function = PsiTreeUtil.findChildrenOfType(file, GoFunctionOrMethodDeclaration::class.java)
            .first { it.name == "run" }
        val extraction = analyzer.extractDirectFlow(function)
        assertEquals(listOf(false, true), extraction.calls.map { it.conditional })
        assertTrue(extraction.controlFlowSimplified)
    }

    fun `test a call written in a case label is on the map`() {
        // The idiomatic tagless switch: the label is the condition.
        val items = itemsOf("switch {\ncase cond():\na()\ndefault:\nb()\n}")
        val switch = structure(items)
        assertEquals(
            "the label's call is evaluated to choose the case, so it is inside it",
            listOf("CASE(cond())=[cond, a]", "DEFAULT=[b]"),
            switch.branches.map(::branchShape),
        )
    }

    fun `test a call in a select comm case is on the map`() {
        val items = itemsOf("select {\ncase v := <-recv():\n_ = v\na()\n}", decls = "func recv() chan int { return nil }")
        val select = structure(items)
        assertEquals(
            listOf("CASE(v := <-recv())=[recv, a]"),
            select.branches.map(::branchShape),
        )
    }

    fun `test a labelled break out of a loop is disclosed`() {
        val file = myFixture.configureByText(
            "labelled.go",
            """
            package sample

            func run(k int) {
            Outer:
                for {
                    switch k {
                    case 1:
                        break Outer
                    }
                }
            }
            """.trimIndent(),
        )
        val function = PsiTreeUtil.findChildrenOfType(file, GoFunctionOrMethodDeclaration::class.java)
            .first { it.name == "run" }
        assertTrue(
            "the jump leaves the loop, not the switch, and the map does not draw it",
            analyzer.extractDirectFlow(function).controlFlowSimplified,
        )
    }

    fun `test a break that leaves a switch case discloses nothing`() {
        val file = myFixture.configureByText(
            "brk.go",
            """
            package sample

            func run(k int) {
                switch k {
                case 1:
                    a()
                    break
                }
            }
            func a() { }
            """.trimIndent(),
        )
        val function = PsiTreeUtil.findChildrenOfType(file, GoFunctionOrMethodDeclaration::class.java)
            .first { it.name == "run" }
        assertFalse(analyzer.extractDirectFlow(function).controlFlowSimplified)
    }

    fun `test a break that leaves a loop is disclosed`() {
        val file = myFixture.configureByText(
            "loopbrk.go",
            """
            package sample

            func run() {
                for {
                    a()
                    break
                }
            }
            func a() { }
            """.trimIndent(),
        )
        val function = PsiTreeUtil.findChildrenOfType(file, GoFunctionOrMethodDeclaration::class.java)
            .first { it.name == "run" }
        assertTrue(analyzer.extractDirectFlow(function).controlFlowSimplified)
    }

    fun `test P represented control flow is no longer reported as simplified`() {
        val file = myFixture.configureByText(
            "clean.go",
            """
            package sample

            func run(flag bool) {
                if flag { a() } else { b() }
                for i := 0; i < 3; i++ { c() }
            }
            func a() { }
            func b() { }
            func c() { }
            """.trimIndent(),
        )
        val function = PsiTreeUtil.findChildrenOfType(file, GoFunctionOrMethodDeclaration::class.java)
            .first { it.name == "run" }
        assertFalse(analyzer.extractDirectFlow(function).controlFlowSimplified)
    }
}
