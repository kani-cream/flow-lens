package com.kanicream.flowlens.ui

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.kanicream.flowlens.FlowLensBundle
import com.kanicream.flowlens.core.export.ChoiceLine
import com.kanicream.flowlens.core.export.ExportContext
import com.kanicream.flowlens.core.export.ExportRequest
import com.kanicream.flowlens.core.export.ExportStrings
import com.kanicream.flowlens.core.export.MarkdownExporter
import com.kanicream.flowlens.core.export.MermaidExporter
import com.kanicream.flowlens.core.model.FlowAnalysisResult
import com.kanicream.flowlens.dispatch.DispatchChoices
import java.awt.datatransfer.StringSelection

/** The formats a flow can leave the IDE in (`V0.4_SPEC.md` §5). */
enum class ExportFormat { MARKDOWN, MERMAID }

/**
 * Renders the current result and puts it on the clipboard.
 *
 * The clipboard rather than a file: a flow is pasted into a review, an issue, or
 * a document, and asking for a path first would be a step in the way of that.
 */
object FlowExport {

    fun copy(project: Project, result: FlowAnalysisResult, format: ExportFormat): Boolean {
        if (!result.isTerminal || result.rootFrame == null) return false
        val request = ExportRequest(result, contextOf(project))
        val text = when (format) {
            ExportFormat.MARKDOWN -> MarkdownExporter.export(request)
            ExportFormat.MERMAID -> MermaidExporter.export(request)
        }
        if (text.isEmpty()) return false
        CopyPasteManager.getInstance().setContents(StringSelection(text))
        return true
    }

    /**
     * The reader's language and the choices in effect, so an export reads like
     * the tool window and repeats what was chosen (`V0.4_SPEC.md` §5.2).
     */
    private fun contextOf(project: Project): ExportContext = ExportContext(
        choices = DispatchChoices.getInstance(project).all().map {
            ChoiceLine(it.fromDisplayName, it.to.displayName)
        },
        strings = ExportStrings(
            ambiguous = FlowLensBundle.message("enum.dispatch.AMBIGUOUS"),
            declaredTarget = FlowLensBundle.message("enum.dispatch.DECLARED_TARGET"),
            unresolved = FlowLensBundle.message("enum.resolution.UNRESOLVED"),
            external = FlowLensBundle.message("enum.resolution.EXTERNAL"),
            builtIn = FlowLensBundle.message("enum.resolution.BUILT_IN"),
            cycle = FlowLensBundle.message("enum.kind.CYCLE"),
            depthLimited = FlowLensBundle.message("details.limit.depth"),
            truncated = FlowLensBundle.message("status.reason.truncated"),
            conditional = FlowLensBundle.message("details.conditional.hint"),
            goroutine = FlowLensBundle.message("enum.execution.GOROUTINE"),
            deferred = FlowLensBundle.message("enum.execution.DEFERRED"),
            chosen = FlowLensBundle.message("export.chosen"),
            testSource = FlowLensBundle.message("card.badge.test.source"),
            nothing = FlowLensBundle.message("branch.empty"),
            notFollowed = FlowLensBundle.message("export.not.followed"),
            dispatchChoices = FlowLensBundle.message("export.dispatch.choices"),
            controlFlowSimplified = FlowLensBundle.message("status.control.flow.simplified"),
            chosenByReader = FlowLensBundle.message("export.chosen.by.reader"),
        ),
    )
}
