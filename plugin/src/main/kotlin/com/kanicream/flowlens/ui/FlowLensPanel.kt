package com.kanicream.flowlens.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.icons.AllIcons
import com.kanicream.flowlens.FlowLensBundle
import com.kanicream.flowlens.core.model.FlowLocation
import com.kanicream.flowlens.core.model.FlowProgress
import com.kanicream.flowlens.core.model.FlowAnalysisResult
import com.kanicream.flowlens.core.model.RunId
import com.kanicream.flowlens.service.FlowAnalysisService
import com.kanicream.flowlens.ui.canvas.FlowCanvas
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * Tool-window content: toolbar, status line, Flow Canvas, and details panel.
 * Collects service state flows with throttling so rapid progressive updates are
 * coalesced instead of flooding the EDT (IMPLEMENTATION_GUARDRAILS.md section 10).
 */
class FlowLensPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val service = FlowAnalysisService.getInstance(project)
    private val canvas = FlowCanvas()
    private val statusLabel = JBLabel(FlowLensBundle.message("status.idle")).apply {
        border = JBUI.Borders.empty(4, 10)
    }
    private val detailsPanel = FlowDetailsPanel(
        onOpenTarget = { card -> navigateTo(card.node.targetLocation ?: card.node.callSiteLocation) },
        onOpenCallSite = { card -> navigateTo(card.node.callSiteLocation) },
    )
    private val jobs = mutableListOf<Job>()
    private var currentRunId: RunId? = null

    init {
        val toolbarPanel = JPanel(BorderLayout())
        val group = DefaultActionGroup(AnalyzeAction(), StopAction(), FitAction())
        val toolbar = ActionManager.getInstance().createActionToolbar("FlowLensToolbar", group, true)
        toolbar.targetComponent = this
        toolbarPanel.add(toolbar.component, BorderLayout.WEST)
        toolbarPanel.add(statusLabel, BorderLayout.CENTER)
        add(toolbarPanel, BorderLayout.NORTH)
        add(JBScrollPane(canvas), BorderLayout.CENTER)
        add(detailsPanel, BorderLayout.SOUTH)

        canvas.onSelectionChanged = detailsPanel::showSelection
        canvas.onNavigateToTarget = { card ->
            navigateTo(card.node.targetLocation ?: card.node.callSiteLocation)
        }
        installCollectors()
    }

    private fun installCollectors() {
        val uiScope = FlowLensUiScope.getInstance(project)
        // StateFlow conflates: a slow collector always sees the latest snapshot, and
        // the delay bounds the EDT update rate while the final state is never lost.
        jobs += uiScope.launch {
            service.results.collect { result ->
                withContext(kotlinx.coroutines.Dispatchers.EDT) { applyResult(result) }
                delay(THROTTLE_MILLIS)
            }
        }
        jobs += uiScope.launch {
            service.progress.collect { progress ->
                withContext(kotlinx.coroutines.Dispatchers.EDT) { applyProgress(progress) }
                delay(THROTTLE_MILLIS)
            }
        }
    }

    private fun applyResult(result: FlowAnalysisResult?) {
        currentRunId = result?.runId
        canvas.setResult(result)
    }

    private fun applyProgress(progress: FlowProgress?) {
        if (progress == null) {
            statusLabel.text = FlowLensBundle.message("status.idle")
            return
        }
        val stageText = FlowLensBundle.message("status.stage.${progress.stage.name}")
        val counters = FlowLensBundle.message(
            "status.counters",
            progress.nodesProduced,
            progress.framesAnalyzed,
            progress.externalCount,
            progress.ambiguousCount,
        )
        val simplified = if (service.results.value?.controlFlowIncomplete == true) {
            "  " + FlowLensBundle.message("status.control.flow.simplified")
        } else {
            ""
        }
        statusLabel.text = "$stageText  ·  $counters$simplified"
    }

    private fun navigateTo(location: FlowLocation?) {
        if (location == null) return
        val runId = currentRunId ?: return
        val pointer = service.navigationPointer(runId, location.handle) ?: return
        val element = pointer.element ?: return
        val file = element.containingFile?.virtualFile ?: return
        OpenFileDescriptor(project, file, element.textOffset).navigate(true)
    }

    override fun dispose() {
        jobs.forEach(Job::cancel)
        jobs.clear()
    }

    private inner class AnalyzeAction : AnAction(
        FlowLensBundle.message("action.analyze.text"),
        FlowLensBundle.message("action.analyze.description"),
        AllIcons.Actions.Execute,
    ), DumbAware {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = FileEditorManager.getInstance(project).selectedTextEditor != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return
            val file = editor.virtualFile ?: return
            service.startAnalysis(file, editor.caretModel.offset)
            canvas.requestFocusInWindow()
        }
    }

    private inner class StopAction : AnAction(
        FlowLensBundle.message("action.stop.text"),
        FlowLensBundle.message("action.stop.description"),
        AllIcons.Actions.Suspend,
    ), DumbAware {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = service.progress.value?.isTerminal == false
        }

        override fun actionPerformed(e: AnActionEvent) {
            service.cancelActive()
        }
    }

    private inner class FitAction : AnAction(
        FlowLensBundle.message("action.fit.text"),
        FlowLensBundle.message("action.fit.description"),
        AllIcons.General.FitContent,
    ), DumbAware {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) {
            canvas.fitToView()
        }
    }

    companion object {
        private const val THROTTLE_MILLIS = 80L
    }
}
