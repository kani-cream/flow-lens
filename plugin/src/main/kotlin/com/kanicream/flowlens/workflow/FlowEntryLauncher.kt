package com.kanicream.flowlens.workflow

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.project.Project
import com.kanicream.flowlens.core.model.FlowLimits
import com.kanicream.flowlens.core.model.RunId
import com.kanicream.flowlens.service.FlowAnalysisService

/** Why opening a stored entry did not start an analysis. */
sealed interface LaunchOutcome {

    data class Started(val runId: RunId) : LaunchOutcome

    /**
     * The declaration is gone. `V0.3_SPEC.md` §8: the caller reports this and
     * keeps the entry, rather than analyzing something with a similar name.
     */
    data object Unresolved : LaunchOutcome
}

/** Turns a stored entry back into a running analysis (`V0.3_SPEC.md` §5.4). */
object FlowEntryLauncher {

    fun launch(project: Project, ref: FlowEntryRef, limits: FlowLimits?): LaunchOutcome {
        // Resolution touches PSI, so it needs read access even on the EDT. Only
        // the file and offset leave the read action.
        val target = runReadActionBlocking {
            when (val resolution = FlowEntryResolver.resolve(project, ref)) {
                is EntryResolution.Found ->
                    resolution.file.virtualFile?.let { it to resolution.declaration.textOffset }
                EntryResolution.NotFound -> null
            }
        } ?: return LaunchOutcome.Unresolved

        val (file, offset) = target
        if (!file.isValid) return LaunchOutcome.Unresolved
        return LaunchOutcome.Started(
            FlowAnalysisService.getInstance(project).startAnalysis(file, offset, limits),
        )
    }

    /** Whether a stored entry still points at something analyzable. */
    fun isResolvable(project: Project, ref: FlowEntryRef): Boolean =
        runReadActionBlocking { FlowEntryResolver.resolve(project, ref) is EntryResolution.Found }
}
