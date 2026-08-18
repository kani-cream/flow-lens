package com.kanicream.flowlens.core.export

import com.kanicream.flowlens.core.model.FlowAnalysisResult

/**
 * Everything an export needs that the result itself does not carry: the words
 * for the reader's language, and the choices in effect.
 *
 * Exports live in `core` because they are a pure transformation of the model —
 * no PSI, no Swing — which is what makes byte-for-byte determinism testable
 * (`V0.4_SPEC.md` §5.1).
 */
data class ExportContext(
    /** Dispatch choices in effect: callable display name to chosen display name. */
    val choices: List<ChoiceLine> = emptyList(),
    val strings: ExportStrings = ExportStrings(),
)

data class ChoiceLine(val from: String, val to: String)

/**
 * The user-visible words. Defaults are English; the plugin passes localized
 * text, so an export reads in the same language as the tool window.
 */
data class ExportStrings(
    val ambiguous: String = "ambiguous",
    val declaredTarget: String = "declared target",
    val unresolved: String = "could not be resolved",
    val external: String = "outside the project",
    val builtIn: String = "built-in",
    val cycle: String = "already on this path",
    val depthLimited: String = "not entered — depth limit",
    val truncated: String = "stopped — node budget reached",
    val conditional: String = "may be skipped",
    val goroutine: String = "goroutine",
    val deferred: String = "deferred",
    val chosen: String = "chosen",
    val testSource: String = "test source",
    val nothing: String = "nothing",
    val notFollowed: String = "Not followed",
    val dispatchChoices: String = "Dispatch choices",
    val controlFlowSimplified: String = "control flow simplified",
    val chosenByReader: String = "chosen by the reader",
    /** Structure kinds, keyed by [com.kanicream.flowlens.core.model.FlowNodeKind] name. */
    val kinds: Map<String, String> = emptyMap(),
    /** Branch kinds, keyed by [com.kanicream.flowlens.core.model.BranchKind] name. */
    val branchKinds: Map<String, String> = emptyMap(),
    /** Result statuses, keyed by [com.kanicream.flowlens.core.model.FlowResultStatus] name. */
    val statuses: Map<String, String> = emptyMap(),
)

/** What every exporter is handed. */
data class ExportRequest(val result: FlowAnalysisResult, val context: ExportContext)
