package com.kanicream.flowlens.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.SmartPsiElementPointer
import com.kanicream.flowlens.core.model.FlowAnalysisResult
import com.kanicream.flowlens.core.model.FlowLimits
import com.kanicream.flowlens.core.model.FlowProgress
import com.kanicream.flowlens.core.model.LocationId
import com.kanicream.flowlens.core.model.RunId
import com.intellij.psi.PsiElement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Single owner of the active analysis run for one project
 * (IMPLEMENTATION_GUARDRAILS.md section 7).
 *
 * Exactly one run is active; starting a new one cancels and replaces the previous
 * run immediately. Result/progress events carry the run identity, and events from a
 * run that is no longer current are dropped before reaching any state observable by
 * the UI, so a cancelled run can never mutate a newer canvas.
 */
@Service(Service.Level.PROJECT)
class FlowAnalysisService(
    private val project: Project,
    private val scope: CoroutineScope,
) {
    private val runCounter = AtomicLong()
    private val lock = Any()
    private var activeJob: Job? = null
    private var activeRunId: RunId? = null
    private var activeHandles: RunHandles? = null

    private val mutableResults = MutableStateFlow<FlowAnalysisResult?>(null)
    private val mutableProgress = MutableStateFlow<FlowProgress?>(null)

    /** Latest snapshot of the current run; null before the first analysis. */
    val results: StateFlow<FlowAnalysisResult?> = mutableResults

    /** Latest progress event of the current run. */
    val progress: StateFlow<FlowProgress?> = mutableProgress

    /**
     * Starts a new analysis from the caret position. Safe to call from the EDT: the
     * caller captures only the file and offset; all analysis work is background.
     */
    fun startAnalysis(file: VirtualFile, offset: Int, limits: FlowLimits = FlowLimits()): RunId {
        val runId = RunId(runCounter.incrementAndGet())
        val handles = RunHandles(project)
        val previous: Job?
        synchronized(lock) {
            previous = activeJob
            activeRunId = runId
            activeHandles = handles
            // The old flow stops being current the moment a new root is requested
            // (REPO_LENS_LESSONS.md 3.6).
            mutableResults.value = null
            mutableProgress.value = null
        }
        previous?.cancel()
        val run = FlowAnalysisRun(
            project = project,
            runId = runId,
            file = file,
            offset = offset,
            limits = limits,
            handles = handles,
            publishResult = ::acceptResult,
            publishProgress = ::acceptProgress,
        )
        val job = scope.launch(Dispatchers.Default) { run.execute() }
        synchronized(lock) {
            // Only adopt the job if this run is still the current one.
            if (activeRunId == runId) activeJob = job else job.cancel()
        }
        return runId
    }

    /** Cooperative cancellation; completed nodes stay navigable. */
    fun cancelActive() {
        synchronized(lock) { activeJob }?.cancel()
    }

    /** Resolves a neutral location handle for navigation. Current run only. */
    fun navigationPointer(runId: RunId, location: LocationId): SmartPsiElementPointer<PsiElement>? {
        val handles = synchronized(lock) {
            if (activeRunId == runId) activeHandles else null
        }
        return handles?.pointer(location)
    }

    /** Internal for lifecycle tests: events from a non-current run must be dropped. */
    internal fun acceptResult(event: FlowAnalysisResultEvent) {
        synchronized(lock) {
            if (activeRunId != event.runId) return
            mutableResults.value = event.result
        }
    }

    /** Internal for lifecycle tests: events from a non-current run must be dropped. */
    internal fun acceptProgress(event: FlowProgress) {
        synchronized(lock) {
            if (activeRunId != event.runId) return
            mutableProgress.value = event
        }
    }

    companion object {
        fun getInstance(project: Project): FlowAnalysisService =
            project.getService(FlowAnalysisService::class.java)
    }
}
