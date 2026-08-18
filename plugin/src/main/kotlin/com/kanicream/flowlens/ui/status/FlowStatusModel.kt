package com.kanicream.flowlens.ui.status

import com.kanicream.flowlens.FlowLensBundle
import com.kanicream.flowlens.core.model.FlowAnalysisResult
import com.kanicream.flowlens.core.model.FlowProgress
import com.kanicream.flowlens.core.model.FlowProgressStage
import com.kanicream.flowlens.core.model.FlowResultStatus

/** How prominently a status should be presented. Never encoded by color alone. */
enum class StatusTone { IDLE, RUNNING, DONE, WARNING, ERROR }

/**
 * Everything the status area shows for one (progress, result) pair. Derived
 * state only — no Swing, no analysis access — so the mapping is unit-testable.
 */
data class FlowStatusViewState(
    /** The analyzed root, so the flow's subject stays visible when scrolled. */
    val rootTitle: String?,
    val headline: String,
    val counters: String?,
    val tone: StatusTone,
    val stopEnabled: Boolean,
    val reanalyzeEnabled: Boolean,
    val simplifiedControlFlow: Boolean,
    val diagnostics: List<String>,
)

/**
 * Maps analysis lifecycle state to the status view. Result state wins over the
 * progress stage for terminal runs so a partial result is never presented as
 * current work, and indexing/cancelled/stale never look like unresolved code
 * (`VISUAL_DESIGN.md` §16).
 */
object FlowStatusModel {

    fun stateOf(progress: FlowProgress?, result: FlowAnalysisResult?): FlowStatusViewState {
        if (progress == null && result == null) {
            return FlowStatusViewState(
                rootTitle = null,
                headline = FlowLensBundle.message("status.idle"),
                counters = null,
                tone = StatusTone.IDLE,
                stopEnabled = false,
                reanalyzeEnabled = false,
                simplifiedControlFlow = false,
                diagnostics = emptyList(),
            )
        }
        // The result reaches its terminal state before the terminal progress event,
        // and the two are collected by independently throttled collectors, so a
        // finished run must not keep showing a running stage.
        val stage = when {
            result?.isTerminal == true -> stageOf(result.status)
            progress != null -> progress.stage
            else -> stageOf(result!!.status)
        }
        // Either signal reaching a terminal state ends the run; if only one of them
        // has been observed yet, that one decides.
        val running = !(progress?.isTerminal ?: false) && !(result?.isTerminal ?: false)
        return FlowStatusViewState(
            rootTitle = result?.rootFrame?.symbol?.displayName,
            headline = FlowLensBundle.message("status.stage.${stage.name}"),
            counters = progress?.let {
                FlowLensBundle.message(
                    "status.counters",
                    it.nodesProduced,
                    it.framesAnalyzed,
                    it.externalCount,
                    it.ambiguousCount,
                )
            },
            tone = toneOf(stage),
            stopEnabled = running,
            reanalyzeEnabled = !running && needsReanalysis(stage),
            simplifiedControlFlow = result?.controlFlowIncomplete == true,
            diagnostics = result?.diagnostics.orEmpty().map { diagnostic ->
                FlowLensBundle.message(diagnostic.messageKey)
            }.distinct(),
        )
    }

    private fun stageOf(status: FlowResultStatus): FlowProgressStage = when (status) {
        FlowResultStatus.RUNNING -> FlowProgressStage.ANALYZING_CHILD_FRAMES
        FlowResultStatus.COMPLETED -> FlowProgressStage.COMPLETED
        FlowResultStatus.TRUNCATED -> FlowProgressStage.TRUNCATED
        FlowResultStatus.CANCELLED -> FlowProgressStage.CANCELLED
        FlowResultStatus.STALE -> FlowProgressStage.STALE
        FlowResultStatus.FAILED -> FlowProgressStage.FAILED
    }

    private fun toneOf(stage: FlowProgressStage): StatusTone = when (stage) {
        FlowProgressStage.COMPLETED -> StatusTone.DONE
        FlowProgressStage.TRUNCATED,
        FlowProgressStage.CANCELLED,
        FlowProgressStage.STALE,
        -> StatusTone.WARNING
        FlowProgressStage.FAILED -> StatusTone.ERROR
        else -> StatusTone.RUNNING
    }

    /** Re-analysis is offered when the run ended without a trustworthy full result. */
    private fun needsReanalysis(stage: FlowProgressStage): Boolean = when (stage) {
        FlowProgressStage.STALE,
        FlowProgressStage.CANCELLED,
        FlowProgressStage.FAILED,
        FlowProgressStage.TRUNCATED,
        -> true
        else -> false
    }
}
