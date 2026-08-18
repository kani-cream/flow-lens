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
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.ui.Messages
import javax.swing.JComponent
import com.kanicream.flowlens.FlowLensBundle
import com.kanicream.flowlens.core.model.FlowLimits
import com.kanicream.flowlens.core.model.ResolutionStatus
import com.kanicream.flowlens.workflow.FlowEntryLauncher
import com.kanicream.flowlens.core.model.FlowSymbol
import com.kanicream.flowlens.service.FlowMetadata
import com.kanicream.flowlens.workflow.EntryResolution
import com.kanicream.flowlens.workflow.FlowEntryResolver
import com.kanicream.flowlens.workflow.FlowEntryRef
import com.kanicream.flowlens.workflow.FlowLensFlows
import com.kanicream.flowlens.workflow.LaunchOutcome
import com.kanicream.flowlens.service.FlowAnalysisService
import com.kanicream.flowlens.ui.canvas.CardVM
import com.kanicream.flowlens.ui.canvas.FlowCanvas
import com.kanicream.flowlens.ui.canvas.FlowCanvasActions
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
    val statusView = FlowStatusView(
        onReanalyze = ::reanalyze,
        onSelectNode = { nodeId -> canvas.selectNode(nodeId) },
    )
    val detailsPanel = FlowDetailsPanel(
        onOpenTarget = {
            navigateTo(selectionModel.selected?.node?.preferredNavigationLocation, takeFocus = false)
        },
        onOpenCallSite = {
            navigateTo(selectionModel.selected?.node?.callSiteLocation, takeFocus = false)
        },
    )

    /** Notified when view-only state such as zoom changes, so chrome can refresh. */
    var onViewStateChanged: () -> Unit = {}

    private var currentRunId: RunId? = null
    private var running = false

    private val canvasActions = FlowCanvasActions(canvas, this)

    init {
        canvas.onContextMenu = canvasActions::showContextMenu
        canvas.onSelectionChanged = selectionModel::select
        canvas.onNavigateToTarget = { card, takeFocus ->
            navigateTo(card.node.preferredNavigationLocation, takeFocus)
        }
        canvas.onNavigateToCallSite = { card, takeFocus ->
            navigateTo(card.node.callSiteLocation, takeFocus)
        }
        canvas.onNavigateToFrameEntry = { frame: FrameVM, takeFocus ->
            navigateTo(frame.entryLocation, takeFocus)
        }
        // Selecting the entry clears the call details: the entry is a frame, not
        // a call event, so there is no call-site or dispatch state to describe.
        canvas.onEntrySelected = { selectionModel.select(null) }
        canvas.onZoomChanged = { onViewStateChanged() }
        canvas.onTogglePin = ::togglePin
        canvas.onAnalyzeFromHere = ::analyzeFromHere
        canvas.canAnalyzeFrom = ::canAnalyzeFrom
        flows.onChanged = { refreshPins() }
        refreshPins()
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

    override fun showFlows(component: JComponent, x: Int, y: Int) {
        flowsMenu.show(component, x, y)
    }

    // ---- workflow commands ----

    private val flows get() = FlowLensFlows.getInstance(project)

    private val flowsMenu = FlowsMenu(
        project = project,
        openEntry = { ref, limits -> openEntry(ref, limits) },
        saveCurrent = ::saveCurrentFlow,
        canSaveCurrent = { service.results.value?.rootFrame != null },
    )

    /**
     * Saves the current entry point with the limits the run used, so opening it
     * later reproduces that analysis rather than re-running it under whatever the
     * settings happen to be (`V0.3_SPEC.md` §5.1).
     */
    private fun saveCurrentFlow() {
        val entry = currentEntry() ?: return
        val name = Messages.showInputDialog(
            project,
            FlowLensBundle.message("flows.save.prompt.message"),
            FlowLensBundle.message("flows.save.prompt.title"),
            null,
            entry.displayName,
            null,
        )?.takeIf { it.isNotBlank() } ?: return
        flows.save(name, entry, service.limitsOfCurrentRun() ?: FlowLimits())
    }

    private fun refreshPins() {
        canvas.pinnedKeys = flows.pinnedKeys()
    }

    /**
     * Pins the selected callable, or the entry when nothing is selected: a pin
     * marks a function, so the root is as pinnable as any call it makes.
     */
    private fun togglePin(card: CardVM?) {
        val result = service.results.value ?: return
        val symbol = card?.node?.targetSymbol ?: result.rootFrame?.symbol ?: return
        val location = card?.node?.targetLocation ?: result.rootFrame?.entryLocation
        val file = fileOf(location) ?: return
        val ref = storableRef(symbol, file) ?: return
        flows.togglePin(ref)
        refreshPins()
    }

    /**
     * A stored entry has to be findable again by its key alone, so anything that
     * would not survive the round trip is refused rather than stored as a mark
     * that can never match (`V0.3_SPEC.md` §8).
     *
     * That rules out a placeholder key for an unresolved call, a
     * compiler-generated member, a file outside the project — whose path could
     * not be stored relatively — and a key that resolves to a different
     * declaration than the one being marked.
     */
    private fun storableRef(symbol: FlowSymbol, file: VirtualFile): FlowEntryRef? {
        if (!FlowEntryRef.isStorable(symbol)) return null
        if (!FlowEntryRef.isInsideProject(project, file)) return null
        val ref = FlowEntryRef.of(symbol, project, file)
        val resolved = runReadActionBlocking { FlowEntryResolver.resolve(project, ref) }
        if (resolved !is EntryResolution.Found) return null
        return ref
    }

    /** Promotes a call's target to the new root (`V0.3_SPEC.md` §6). */
    private fun analyzeFromHere(card: CardVM) {
        if (!canAnalyzeFrom(card)) return
        val location = card.node.targetLocation ?: return
        val pointer = currentRunId?.let { service.navigationPointer(it, location.handle) } ?: return
        val target = runReadActionBlocking {
            val element = pointer.element?.takeIf { it.isValid } ?: return@runReadActionBlocking null
            element.containingFile?.virtualFile?.let { it to element.textOffset }
        } ?: return
        service.startAnalysis(target.first, target.second)
        canvas.requestFocusInWindow()
    }

    /**
     * Only a target with a body offers something to analyze. An external,
     * unresolved, or built-in call would start a run with nothing in it, so the
     * command is disabled rather than failing (`V0.3_SPEC.md` §6.1).
     */
    private fun canAnalyzeFrom(card: CardVM): Boolean =
        card.node.targetLocation != null &&
            card.node.metadata[FlowMetadata.ANALYZABLE] == "true"

    /** Opens a stored entry, reporting rather than guessing when it is gone. */
    fun openEntry(ref: FlowEntryRef, limits: FlowLimits?): LaunchOutcome {
        val outcome = FlowEntryLauncher.launch(project, ref, limits)
        when (outcome) {
            is LaunchOutcome.Started -> canvas.requestFocusInWindow()
            // The menu only checked that the file is still there, because
            // resolving every entry would parse every file on the EDT. The
            // declaration can still be gone, and saying so beats doing nothing.
            LaunchOutcome.Unresolved -> Messages.showWarningDialog(
                project,
                FlowLensBundle.message("flows.entry.missing.message", ref.displayName),
                FlowLensBundle.message("flows.entry.missing.title"),
            )
        }
        return outcome
    }

    /** The current root as a storable entry, or null when there is no result. */
    fun currentEntry(): FlowEntryRef? {
        val root = service.results.value?.rootFrame ?: return null
        val file = fileOf(root.entryLocation) ?: return null
        return storableRef(root.symbol, file)
    }

    private fun fileOf(location: FlowLocation?): VirtualFile? {
        val handle = location?.handle ?: return null
        val pointer = currentRunId?.let { service.navigationPointer(it, handle) } ?: return null
        return runReadActionBlocking {
            pointer.element?.takeIf { it.isValid }?.containingFile?.virtualFile
        }
    }

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

    /**
     * Shows [location] in the editor. [takeFocus] is false while the user is
     * reading the flow: the caret moves so the declaration is on screen, but the
     * keyboard stays on the canvas so the next arrow key continues the trace
     * instead of typing into the file. Only an explicit Jump to Source hands the
     * keyboard over.
     */
    private fun navigateTo(location: FlowLocation?, takeFocus: Boolean) {
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
        descriptor.navigate(takeFocus)
    }

    override fun dispose() {
        jobs.forEach(Job::cancel)
        jobs.clear()
        selectionModel.clear()
        // The store outlives the tool window, so a listener left installed would
        // hold the whole disposed Swing tree (guardrails §15).
        flows.onChanged = {}
    }

    private companion object {
        const val THROTTLE_MILLIS = 80L
    }
}
