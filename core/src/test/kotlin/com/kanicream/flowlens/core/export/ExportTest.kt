package com.kanicream.flowlens.core.export

import com.kanicream.flowlens.core.engine.FlowEventSpec
import com.kanicream.flowlens.core.engine.FlowModelBuilder
import com.kanicream.flowlens.core.engine.StructureSpec
import com.kanicream.flowlens.core.model.BranchKind
import com.kanicream.flowlens.core.model.DispatchConfidence
import com.kanicream.flowlens.core.model.FlowAnalysisResult
import com.kanicream.flowlens.core.model.FlowLimits
import com.kanicream.flowlens.core.model.FlowNodeKind
import com.kanicream.flowlens.core.model.FlowResultStatus
import com.kanicream.flowlens.core.model.FlowSymbol
import com.kanicream.flowlens.core.model.ResolutionStatus
import com.kanicream.flowlens.core.model.RunId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `V0.4_SPEC.md` §5–7, acceptance K–T. */
class ExportTest {

    private fun symbol(name: String, container: String? = "Owner") =
        FlowSymbol("java", "$name()", container, "java:$container#$name()")

    /** A location whose path is the kind an absolute one would be mistaken for. */
    private fun location(index: Int) = com.kanicream.flowlens.core.model.FlowLocation(
        com.kanicream.flowlens.core.model.LocationId(index),
        "/Users/someone/secret/Owner.java",
        index,
    )

    private fun call(
        name: String,
        container: String? = "Owner",
        resolution: ResolutionStatus = ResolutionStatus.PROJECT_LOCAL,
        dispatch: DispatchConfidence = DispatchConfidence.EXACT,
        metadata: Map<String, String> = emptyMap(),
    ) = FlowEventSpec(
        kind = FlowNodeKind.CALL,
        callSiteLocation = location(1),
        targetLocation = location(2),
        targetSymbol = symbol(name, container),
        resolutionStatus = resolution,
        dispatchConfidence = dispatch,
        metadata = metadata,
    )

    /** `check(); if (flag) { charge() } else { }; trim(); mystery()` plus a chosen call. */
    private fun sample(): FlowAnalysisResult {
        val b = FlowModelBuilder(RunId(1), FlowLimits(), 0)
        val root = b.openRootFrame(symbol("purchase"), null)
        val checkNode = b.addEvent(root, call("check"))!!
        val body = b.openChildFrame(root, checkNode, symbol("check"), null)
        b.addEvent(body, call("audit"))
        val condition = b.openStructure(root, StructureSpec(FlowNodeKind.CONDITION, null, "flag"))!!
        b.openBranch(condition, BranchKind.THEN, null)
        b.addEvent(root, call("charge"))
        b.openBranch(condition, BranchKind.ELSE, null)
        b.closeStructure(condition)
        b.addEvent(root, call("trim", "String", ResolutionStatus.EXTERNAL))
        b.addEvent(
            root,
            call(
                "pay",
                "Gateway",
                dispatch = DispatchConfidence.AMBIGUOUS,
                metadata = mapOf(MarkdownExporter.CHOSEN_KEY to "StripeGateway.pay()"),
            ),
        )
        return b.snapshot(FlowResultStatus.COMPLETED)
    }

    private fun request(result: FlowAnalysisResult = sample()) = ExportRequest(
        result,
        ExportContext(choices = listOf(ChoiceLine("Gateway.pay()", "StripeGateway.pay()"))),
    )

    @Test
    fun `L exporting twice produces identical text`() {
        val request = request()
        assertEquals(MarkdownExporter.export(request), MarkdownExporter.export(request))
        assertEquals(MermaidExporter.export(request), MermaidExporter.export(request))
    }

    @Test
    fun `K an ambiguous call and its choice both appear`() {
        val markdown = MarkdownExporter.export(request())
        assertTrue(markdown.contains("ambiguous"), markdown)
        assertTrue(markdown.contains("chosen: `StripeGateway.pay()`"), "the chosen body is named on the call")
        assertTrue(markdown.contains("chosen by the reader"), "and the choice is listed once more")
    }

    @Test
    fun `N a collapsed frame is exported anyway`() {
        // Expansion is a view state; the flow is what was analyzed.
        val markdown = MarkdownExporter.export(request())
        assertTrue(markdown.contains("audit()"), "the analyzed body is present")
    }

    @Test
    fun `branches and their labels are exported, including an empty one`() {
        val markdown = MarkdownExporter.export(request())
        assertTrue(markdown.contains("**then**"), markdown)
        assertTrue(markdown.contains("**else** — nothing"), "an empty branch says so")
    }

    @Test
    fun `S a flow with nothing to explain has no not-followed section`() {
        val b = FlowModelBuilder(RunId(1), FlowLimits(), 0)
        val root = b.openRootFrame(symbol("run"), null)
        b.addEvent(root, call("a"))
        val clean = ExportRequest(b.snapshot(FlowResultStatus.COMPLETED), ExportContext())
        val markdown = MarkdownExporter.export(clean)

        assertFalse(markdown.contains("Not followed"), "a section that always appears would stop meaning anything")
        assertFalse(markdown.contains("Dispatch choices"))
    }

    @Test
    fun `M a truncated result says so`() {
        val b = FlowModelBuilder(RunId(1), FlowLimits(maxNodes = 2), 0)
        val root = b.openRootFrame(symbol("run"), null)
        b.addEvent(root, call("a"))
        b.addEvent(root, call("b"))
        b.addLimitEvent(root)
        val markdown = MarkdownExporter.export(
            ExportRequest(b.snapshot(FlowResultStatus.TRUNCATED), ExportContext()),
        )
        assertTrue(markdown.contains("truncated"), markdown)
        assertTrue(markdown.contains("node budget"), markdown)
    }

    @Test
    fun `O a label with quotes and angle brackets stays inside its node`() {
        val b = FlowModelBuilder(RunId(1), FlowLimits(), 0)
        val root = b.openRootFrame(symbol("run"), null)
        b.openStructure(
            root,
            StructureSpec(FlowNodeKind.CONDITION, null, """x < "y" && z > [0]"""),
        )!!.let(b::closeStructure)
        val mermaid = MermaidExporter.export(
            ExportRequest(b.snapshot(FlowResultStatus.COMPLETED), ExportContext()),
        )

        assertFalse(mermaid.contains("\"y\""), "a raw quote would end the label early")
        assertFalse(mermaid.contains(" < "))
        assertTrue(mermaid.contains("#quot;"), mermaid)
        assertTrue(mermaid.contains("#lt;"))
        // Every label opens and closes exactly once per line.
        for (line in mermaid.lines().filter { it.contains("[\"") }) {
            assertEquals(2, line.count { it == '"' }, "unbalanced quotes in: $line")
        }
    }

    @Test
    fun `P a structure becomes a subgraph`() {
        val mermaid = MermaidExporter.export(request())
        assertTrue(mermaid.contains("subgraph s0"), mermaid)
        assertTrue(mermaid.contains("end"))
        assertTrue(mermaid.contains("then"), "the section is named")
    }

    @Test
    fun `node ids are positional so the diagram diffs cleanly`() {
        val mermaid = MermaidExporter.export(request())
        assertTrue(mermaid.startsWith("flowchart TD\n  n0["), mermaid)
        val ids = Regex("^  (n\\d+)\\[", RegexOption.MULTILINE).findAll(mermaid)
            .map { it.groupValues[1] }.toList()
        assertEquals(ids.sortedBy { it.drop(1).toInt() }, ids, "ids are dense and ordered")
    }

    @Test
    fun `an uncertain step gets a dashed edge`() {
        val b = FlowModelBuilder(RunId(1), FlowLimits(), 0)
        val root = b.openRootFrame(symbol("run"), null)
        b.addEvent(root, call("sure"))
        b.addEvent(
            root,
            call("maybe", metadata = mapOf(MarkdownExporter.CONDITIONAL_KEY to "true")),
        )
        val mermaid = MermaidExporter.export(
            ExportRequest(b.snapshot(FlowResultStatus.COMPLETED), ExportContext()),
        )
        assertTrue(mermaid.contains(" --> n1"), "a certain step keeps the solid edge")
        assertTrue(mermaid.contains(" -.-> n2"), "one that may be skipped must not claim to run next")
    }

    @Test
    fun `Q a cycle points back rather than starting a new node`() {
        val b = FlowModelBuilder(RunId(1), FlowLimits(), 0)
        val root = b.openRootFrame(symbol("run"), null)
        val first = b.addEvent(root, call("recurse"))!!
        val inner = b.openChildFrame(root, first, symbol("recurse"), null)
        b.addEvent(
            inner,
            FlowEventSpec(
                kind = FlowNodeKind.CYCLE,
                callSiteLocation = null,
                targetSymbol = symbol("recurse"),
                resolutionStatus = ResolutionStatus.PROJECT_LOCAL,
                dispatchConfidence = DispatchConfidence.EXACT,
            ),
        )
        val mermaid = MermaidExporter.export(
            ExportRequest(b.snapshot(FlowResultStatus.COMPLETED), ExportContext()),
        )
        // The repeated callable keeps one node; the cycle is an edge back to it.
        assertEquals(2, Regex("^  n\\d+\\[", RegexOption.MULTILINE).findAll(mermaid).count(), mermaid)
        assertTrue(mermaid.contains("-.->|"), mermaid)
        assertTrue(mermaid.lines().any { it.contains("-.->|") && it.trimEnd().endsWith("n1") }, mermaid)
    }

    @Test
    fun `a branch subgraph lists its members without swallowing the structure`() {
        val mermaid = MermaidExporter.export(request())
        val lines = mermaid.lines()
        val structureLine = lines.indexOfFirst { it.contains("flag") }
        val subgraphStart = lines.indexOfFirst { it.contains("subgraph s0") }
        assertTrue(structureLine in 0 until subgraphStart, mermaid)
        // Every edge comes after every subgraph block, so no edge endpoint can
        // pull a node into a cluster it does not belong to.
        val lastEnd = lines.indexOfLast { it.trim() == "end" }
        val firstEdge = lines.indexOfFirst { it.contains("-->") || it.contains("-.->") }
        assertTrue(firstEdge > lastEnd, mermaid)
    }

    @Test
    fun `Mermaid discloses what Markdown discloses`() {
        val b = FlowModelBuilder(RunId(1), FlowLimits(), 0)
        val root = b.openRootFrame(symbol("run"), null)
        b.addEvent(
            root,
            call("blocked", metadata = mapOf(MarkdownExporter.LIMIT_KEY to MarkdownExporter.LIMIT_DEPTH)),
        )
        b.addEvent(root, call("mystery", resolution = ResolutionStatus.UNRESOLVED))
        val export = ExportRequest(b.snapshot(FlowResultStatus.COMPLETED), ExportContext())
        val mermaid = MermaidExporter.export(export)

        assertTrue(mermaid.contains("depth limit"), mermaid)
        assertTrue(mermaid.contains("%%"), "the summary survives as comments")
        for (line in StopReasons.of(export)) {
            assertTrue(mermaid.contains(line), "missing from the diagram: $line")
        }
    }

    @Test
    fun `the reader's own words are used when supplied`() {
        val japanese = ExportContext(
            strings = ExportStrings(
                kinds = mapOf("CONDITION" to "条件"),
                branchKinds = mapOf("THEN" to "真の場合"),
                statuses = mapOf("COMPLETED" to "完了"),
            ),
        )
        val markdown = MarkdownExporter.export(ExportRequest(sample(), japanese))
        assertTrue(markdown.contains("完了"), markdown)
        assertTrue(markdown.contains("**真の場合**"), markdown)
    }

    @Test
    fun `a reason reads the way the status area reads it`() {
        val b = FlowModelBuilder(RunId(1), FlowLimits(), 0)
        val root = b.openRootFrame(symbol("run"), null)
        b.addEvent(root, call("trim", "String", ResolutionStatus.EXTERNAL))
        b.addEvent(root, call("other", "String", ResolutionStatus.EXTERNAL))
        val markdown = MarkdownExporter.export(
            ExportRequest(b.snapshot(FlowResultStatus.COMPLETED), ExportContext()),
        )
        assertTrue(markdown.contains("Outside the project: 2"), markdown)
        assertFalse(markdown.contains("- 2 —"), "a bare count reads as a list index")
    }

    @Test
    fun `T no path from the model leaks into either format`() {
        // The sample's nodes carry an absolute path, so this can actually fail.
        val request = request()
        assertTrue(
            request.result.rootFrame!!.events.first().callSiteLocation != null,
            "the fixture must carry a path for this to test anything",
        )
        for (text in listOf(MarkdownExporter.export(request), MermaidExporter.export(request))) {
            assertFalse(text.contains("/Users/"), text)
            assertFalse(text.contains("secret"), text)
            assertFalse(text.contains("C:\\"))
        }
    }

    @Test
    fun `R a run in progress is not a flow anyone should export`() {
        val b = FlowModelBuilder(RunId(1), FlowLimits(), 0)
        val root = b.openRootFrame(symbol("run"), null)
        b.addEvent(root, call("a"))
        val running = b.snapshot(FlowResultStatus.RUNNING)

        assertFalse(running.isTerminal, "the fixture must be mid-run for this to test anything")
        // The exporters render whatever they are handed; refusing a partial map
        // is the caller's job, and this is the property that decision rests on.
        val partial = MarkdownExporter.export(ExportRequest(running, ExportContext()))
        val finished = MarkdownExporter.export(
            ExportRequest(b.snapshot(FlowResultStatus.COMPLETED), ExportContext()),
        )
        assertFalse(
            partial == finished,
            "a partial map and a finished one must not be indistinguishable",
        )
    }

    @Test
    fun `an empty result exports nothing rather than a broken document`() {
        val b = FlowModelBuilder(RunId(1), FlowLimits(), 0)
        val empty = ExportRequest(b.snapshot(FlowResultStatus.FAILED), ExportContext())
        assertEquals("", MarkdownExporter.export(empty))
        assertEquals("", MermaidExporter.export(empty))
    }
}
