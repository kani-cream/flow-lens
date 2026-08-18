package com.kanicream.flowlens.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.kanicream.flowlens.FlowLensBundle
import com.kanicream.flowlens.settings.FlowLensConfigurable
import com.kanicream.flowlens.settings.FlowLensSettings
import javax.swing.JComponent

/**
 * Analyze / Stop / Fit / settings entry points (guardrails §12).
 *
 * The depth control lives only in settings: its value applies to the next run,
 * so surfacing it next to the running analysis suggests an effect it does not
 * have (owner decision, 2026-08-17).
 *
 * The toolbar only issues commands; it holds no analysis state.
 */
class FlowToolbar(
    private val project: Project,
    private val commands: Commands,
) {
    /** Everything the toolbar can ask the controller to do. */
    interface Commands {
        fun analyzeAtCaret()
        fun stop()
        fun fitToView()
        fun zoomIn()
        fun zoomOut()
        fun resetZoom()
        fun zoomPercent(): Int
        fun isRunning(): Boolean
        fun canAnalyze(): Boolean

        /** Shows the saved flows, recents, and pins (`V0.3_SPEC.md` §4–5). */
        fun showFlows(component: JComponent, x: Int, y: Int)
    }

    private val group = DefaultActionGroup(
        AnalyzeAction(),
        StopAction(),
        FlowsAction(),
        Separator.getInstance(),
        ZoomOutAction(),
        ZoomLevelAction(),
        ZoomInAction(),
        FitAction(),
        Separator.getInstance(),
        SettingsAction(),
    )

    private var toolbar: ActionToolbar? = null

    fun createComponent(targetComponent: JComponent): JComponent {
        val created = ActionManager.getInstance().createActionToolbar(PLACE, group, true)
        created.targetComponent = targetComponent
        toolbar = created
        return created.component
    }

    /**
     * Re-runs the action updates so the zoom percentage reflects a change made
     * from the canvas — the wheel or a keyboard shortcut — instead of waiting for
     * the toolbar's own update cycle.
     */
    fun refresh() {
        toolbar?.updateActionsAsync()
    }

    /**
     * A popup rather than a permanent list: the tool window is routinely narrow,
     * and this is something the user opens between analyses, not during one.
     */
    private inner class FlowsAction : AnAction(
        FlowLensBundle.message("action.flows.text"),
        FlowLensBundle.message("action.flows.description"),
        AllIcons.Actions.ListFiles,
    ), DumbAware {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) {
            val source = e.inputEvent?.component as? JComponent ?: return
            commands.showFlows(source, 0, source.height)
        }
    }

    private inner class AnalyzeAction : AnAction(
        FlowLensBundle.message("action.analyze.text"),
        FlowLensBundle.message("action.analyze.description"),
        AllIcons.Actions.Execute,
    ), DumbAware {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = commands.canAnalyze()
        }

        override fun actionPerformed(e: AnActionEvent) = commands.analyzeAtCaret()
    }

    private inner class StopAction : AnAction(
        FlowLensBundle.message("action.stop.text"),
        FlowLensBundle.message("action.stop.description"),
        AllIcons.Actions.Suspend,
    ), DumbAware {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = commands.isRunning()
        }

        override fun actionPerformed(e: AnActionEvent) = commands.stop()
    }

    private inner class ZoomOutAction : AnAction(
        FlowLensBundle.message("action.zoom.out.text"),
        FlowLensBundle.message("action.zoom.out.description"),
        AllIcons.General.ZoomOut,
    ), DumbAware {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) = commands.zoomOut()
    }

    private inner class ZoomInAction : AnAction(
        FlowLensBundle.message("action.zoom.in.text"),
        FlowLensBundle.message("action.zoom.in.description"),
        AllIcons.General.ZoomIn,
    ), DumbAware {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) = commands.zoomIn()
    }

    /** The current zoom, shown as text; clicking it returns to 100%. */
    private inner class ZoomLevelAction : AnAction(), DumbAware {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.text = FlowLensBundle.message("action.zoom.level.text", commands.zoomPercent())
            e.presentation.description = FlowLensBundle.message("action.zoom.reset.description")
            e.presentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true)
        }

        override fun actionPerformed(e: AnActionEvent) = commands.resetZoom()
    }

    private inner class FitAction : AnAction(
        FlowLensBundle.message("action.fit.text"),
        FlowLensBundle.message("action.fit.description"),
        AllIcons.General.FitContent,
    ), DumbAware {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) = commands.fitToView()
    }

    private inner class SettingsAction : AnAction(
        FlowLensBundle.message("action.settings.text"),
        FlowLensBundle.message("action.settings.description"),
        AllIcons.General.Settings,
    ), DumbAware {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, FlowLensConfigurable::class.java)
        }
    }

    private companion object {
        const val PLACE = "FlowLensToolbar"
    }
}
