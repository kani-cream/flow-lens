package com.kanicream.flowlens.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.kanicream.flowlens.core.model.FlowAnalysisResult
import com.kanicream.flowlens.core.model.FlowLocation
import com.kanicream.flowlens.core.model.FlowProgress
import com.kanicream.flowlens.core.model.RunId
import com.kanicream.flowlens.service.FlowAnalysisService
import com.kanicream.flowlens.ui.canvas.CardVM
import com.kanicream.flowlens.ui.canvas.FlowCanvas
import com.kanicream.flowlens.ui.canvas.FrameVM
import com.kanicream.flowlens.ui.details.FlowDetailsPanel
import com.kanicream.flowlens.ui.status.FlowStatusModel
import com.kanicream.flowlens.ui.status.FlowStatusView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Binds analysis service state to the view components and turns view commands
 * back into service calls (guardrails §12). The canvas renders; this class owns
 * the wiring, throttling, and navigation.
 */
class FlowLensController(private val project: Project) : Disposable, FlowToolbar.Commands {

    private val service = FlowAnalysisService.getInstance(project)
    private val jobs = mutableListOf<Job>()

    val selectionModel = FlowSelectionModel()
    val canvas = FlowCanvas()
    val statusView = FlowStatusView(onReanalyze = ::reanalyze)
    val detailsPanel = FlowDetailsPanel(
        onOpenTarget = { navigateTo(selectionModel.selected?.node?.preferredNavigationLocation) },
        onOpenCallSite = { navigateTo(selectionModel.selected?.node?.callSiteLocation) },
    )

    private var currentRunId: RunId? = null
    private var running = false

    init {
        canvas.onSelectionChanged = selectionModel::select
        canvas.onNavigateToTarget = { card -> navigateTo(card.node.preferredNavigationLocation) }
        canvas.onNavigateToCallSite = { card -> navigateTo(card.node.callSiteLocation) }
        canvas.onNavigateToFrameEntry = { frame: FrameVM -> navigateTo(frame.entryLocation) }
        selectionModel.addListener { card: CardVM? -> detailsPanel.show(card?.node) }
        subscribe()
    }

    // ---- toolbar commands ----

    override fun analyzeAtCaret() {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return
        val file = editor.virtualFile ?: return
        service.startAnalysis(file, editor.caretModel.offset)
        canvas.requestFocusInWindow()
    }

    override fun stop() = service.cancelActive()

    override fun fitToView() = canvas.fitToView()

    override fun zoomIn() = canvas.zoomIn()

    override fun zoomOut() = canvas.zoomOut()

    override fun resetZoom() = canvas.resetZoom()

    override fun zoomPercent(): Int = canvas.zoomPercent

    override fun isRunning(): Boolean = running

    override fun canAnalyze(): Boolean =
        FileEditorManager.getInstance(project).selectedTextEditor != null

    private fun reanalyze() {
        service.reanalyze()
        canvas.requestFocusInWindow()
    }

    // ---- service binding ----

    private fun subscribe() {
        val scope = FlowLensUiScope.getInstance(project)
        // StateFlow conflates, so a throttled collector always ends on the latest
        // snapshot: progressive updates cannot flood the EDT and the final state
        // is never dropped (guardrails §10).
        jobs += scope.launch {
            service.results.collect { result ->
                withContext(Dispatchers.EDT) { applyResult(result) }
                delay(THROTTLE_MILLIS)
            }
        }
        jobs += scope.launch {
            service.progress.collect { progress ->
                withContext(Dispatchers.EDT) { applyProgress(progress) }
                delay(THROTTLE_MILLIS)
            }
        }
    }

    private fun applyResult(result: FlowAnalysisResult?) {
        currentRunId = result?.runId
        canvas.setResult(result)
        refreshStatus(service.progress.value, result)
    }

    private fun applyProgress(progress: FlowProgress?) {
        running = progress != null && !progress.isTerminal
        refreshStatus(progress, service.results.value)
    }

    private fun refreshStatus(progress: FlowProgress?, result: FlowAnalysisResult?) {
        statusView.apply(FlowStatusModel.stateOf(progress, result))
    }

    // ---- navigation ----

    private fun navigateTo(location: FlowLocation?) {
        if (location == null) return
        val runId = currentRunId ?: return
        val pointer = service.navigationPointer(runId, location.handle) ?: return
        // Dereferencing a smart pointer touches PSI, which needs read access even
        // on the EDT; only the descriptor leaves the read action.
        val descriptor = runReadActionBlocking {
            val element = pointer.element?.takeIf { it.isValid }
            val file = element?.containingFile?.virtualFile
            if (element == null || file == null) null
            else OpenFileDescriptor(project, file, element.textOffset)
        } ?: return
        descriptor.navigate(true)
    }

    override fun dispose() {
        jobs.forEach(Job::cancel)
        jobs.clear()
        selectionModel.clear()
    }

    private companion object {
        const val THROTTLE_MILLIS = 80L
    }
}
