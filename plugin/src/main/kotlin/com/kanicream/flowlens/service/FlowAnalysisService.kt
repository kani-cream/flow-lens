package com.kanicream.flowlens.service

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.SmartPsiElementPointer
import com.kanicream.flowlens.core.model.FlowAnalysisResult
import com.kanicream.flowlens.core.model.FlowLimits
import com.kanicream.flowlens.core.model.FlowProgress
import com.kanicream.flowlens.core.model.LocationId
import com.kanicream.flowlens.core.model.RunId
import com.kanicream.flowlens.core.model.FlowResultStatus
import com.kanicream.flowlens.workflow.FlowEntryRef
import com.kanicream.flowlens.workflow.FlowLensRecents
import com.kanicream.flowlens.dispatch.DispatchChoices
import com.kanicream.flowlens.settings.FlowLensSettings
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

    /**
     * A cancel that arrived before the job existed. `startAnalysis` publishes the
     * run id before launching, so Stop pressed in that window used to cancel
     * nothing — the run simply carried on. CI found this through a test that
     * cancelled at the first frame operation.
     */
    private var cancelRequestedFor: RunId? = null
    private var lastRequest: AnalysisRequest? = null

    /** The root request of the current run, kept so it can be repeated. */
    private data class AnalysisRequest(
        val file: VirtualFile,
        val offset: Int,
        val limits: FlowLimits,
        /**
         * Resolved when the run starts, on the caller's thread. Deriving it later
         * would need a read action on the publishing path, which deadlocks a run
         * whose caller is blocked waiting for that very result.
         */
        val relativePath: String,
        val rootHandle: LocationId? = null,
    )

    private val mutableResults = MutableStateFlow<FlowAnalysisResult?>(null)
    private val mutableProgress = MutableStateFlow<FlowProgress?>(null)

    /** Latest snapshot of the current run; null before the first analysis. */
    val results: StateFlow<FlowAnalysisResult?> = mutableResults

    /** Latest progress event of the current run. */
    val progress: StateFlow<FlowProgress?> = mutableProgress

    /**
     * Starts a new analysis from the caret position. Safe to call from the EDT: the
     * caller captures only the file and offset; all analysis work is background.
     *
     * [limits] is normally omitted so the run snapshots the current project
     * settings; tests pass an explicit snapshot. Either way the run keeps that
     * immutable configuration for its whole lifetime (guardrails §8).
     */
    fun startAnalysis(file: VirtualFile, offset: Int, limits: FlowLimits? = null): RunId {
        val effectiveLimits = limits ?: FlowLensSettings.getInstance(project).snapshot()
        val relativePath = FlowEntryRef.relativePathOf(project, file)
        val runId = RunId(runCounter.incrementAndGet())
        val handles = RunHandles(project)
        val previous: Job?
        synchronized(lock) {
            previous = activeJob
            activeRunId = runId
            activeHandles = handles
            lastRequest = AnalysisRequest(file, offset, effectiveLimits, relativePath)
            cancelRequestedFor = null
            // The previous run's job is about to be cancelled and is no longer
            // what Stop should reach. Leaving it here made a Stop pressed during
            // the launch window cancel a run that had already finished, while the
            // new one carried on.
            activeJob = null
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
            limits = effectiveLimits,
            choices = DispatchChoices.getInstance(project).snapshot(),
            handles = handles,
            publishResult = ::acceptResult,
            publishProgress = ::acceptProgress,
            onStaleChoices = { keys -> DispatchChoices.getInstance(project).dropStale(keys) },
        )
        val job = scope.launch(Dispatchers.Default) { run.execute() }
        synchronized(lock) {
            when {
                activeRunId != runId -> job.cancel()
                cancelRequestedFor == runId -> {
                    cancelRequestedFor = null
                    job.cancel()
                }
                else -> activeJob = job
            }
        }
        return runId
    }

    /** The limits the current run used, so a saved flow can reproduce it. */
    fun limitsOfCurrentRun(): FlowLimits? = synchronized(lock) { lastRequest?.limits }

    /** Cooperative cancellation; completed nodes stay navigable. */
    fun cancelActive() {
        val job = synchronized(lock) {
            // Remember the request even when there is no job yet, so a run that is
            // still being launched is cancelled the moment it can be.
            if (activeJob == null) cancelRequestedFor = activeRunId
            activeJob
        }
        job?.cancel()
    }

    /**
     * Repeats the last analysis, used by the re-analyze affordance after a stale,
     * cancelled, or failed run. The root is located through its smart pointer so
     * an edit that moved the declaration does not restart from a wrong offset;
     * the original caret offset is the fallback.
     */
    fun reanalyze(): RunId? {
        val request = synchronized(lock) { lastRequest } ?: return null
        val pointer = synchronized(lock) { activeHandles }?.pointer(request.rootHandle ?: LocationId(-1))
        val offset = runReadActionBlocking {
            pointer?.element?.takeIf { it.isValid }?.textOffset ?: request.offset
        }
        if (!request.file.isValid) return null
        return startAnalysis(request.file, offset, request.limits)
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
        val finished = synchronized(lock) {
            if (activeRunId != event.runId) return
            mutableResults.value = event.result
            val rootHandle = event.result.rootFrame?.entryLocation?.handle
            if (rootHandle != null && lastRequest?.rootHandle == null) {
                lastRequest = lastRequest?.copy(rootHandle = rootHandle)
            }
            if (event.result.isTerminal) lastRequest else null
        }
        if (finished != null) recordRecent(event.result, finished)
    }

    /**
     * A finished analysis becomes a recent entry. Only a run that produced a map
     * counts: a cancelled or stale exploration must not push real work out of a
     * capped list (`V0.3_SPEC.md` §5.2).
     */
    private fun recordRecent(result: FlowAnalysisResult, request: AnalysisRequest) {
        if (result.status != FlowResultStatus.COMPLETED &&
            result.status != FlowResultStatus.TRUNCATED
        ) {
            return
        }
        val symbol = result.rootFrame?.symbol ?: return
        if (!request.file.isValid) return
        // Bookkeeping must not be able to fail an analysis: the result is already
        // published (guardrails §9). Nothing here touches PSI, so it also cannot
        // block the run behind a lock the waiting caller may be holding.
        try {
            FlowLensRecents.getInstance(project).record(
                FlowEntryRef(
                    key = symbol.key,
                    languageId = symbol.languageId,
                    displayName = symbol.displayName,
                    containerName = symbol.containerName,
                    path = request.relativePath,
                ),
                request.limits,
            )
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("Flow Lens could not record a recent flow: ${e.javaClass.simpleName}")
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
        private val LOG = Logger.getInstance(FlowAnalysisService::class.java)

        fun getInstance(project: Project): FlowAnalysisService =
            project.getService(FlowAnalysisService::class.java)
    }
}
