package com.kanicream.flowlens.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.kanicream.flowlens.FlowLensBundle
import com.kanicream.flowlens.settings.FlowLensConfigurable
import com.kanicream.flowlens.settings.FlowLensSettings
import javax.swing.JComponent

/**
 * Analyze / Stop / Fit / depth / settings entry points (guardrails §12).
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
        fun isRunning(): Boolean
        fun canAnalyze(): Boolean
    }

    private val group = DefaultActionGroup(
        AnalyzeAction(),
        StopAction(),
        Separator.getInstance(),
        FitAction(),
        Separator.getInstance(),
        DepthActionGroup(),
        SettingsAction(),
    )

    fun createComponent(targetComponent: JComponent): JComponent {
        val toolbar: ActionToolbar =
            ActionManager.getInstance().createActionToolbar(PLACE, group, true)
        toolbar.targetComponent = targetComponent
        return toolbar.component
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

    private inner class FitAction : AnAction(
        FlowLensBundle.message("action.fit.text"),
        FlowLensBundle.message("action.fit.description"),
        AllIcons.General.FitContent,
    ), DumbAware {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) = commands.fitToView()
    }

    /** Quick depth choices; the value applies to the next run, never the current one. */
    private inner class DepthActionGroup : DefaultActionGroup(), DumbAware {
        init {
            isPopup = true
            templatePresentation.icon = AllIcons.Actions.ListChanges
            FlowLensSettings.QUICK_DEPTHS.forEach { add(DepthAction(it)) }
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            val depth = FlowLensSettings.getInstance(project).snapshot().maxDepth
            e.presentation.text = FlowLensBundle.message("action.depth.text", depth)
            e.presentation.description = FlowLensBundle.message("action.depth.description")
        }
    }

    private inner class DepthAction(private val depth: Int) : ToggleAction(depth.toString()), DumbAware {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun isSelected(e: AnActionEvent): Boolean =
            FlowLensSettings.getInstance(project).snapshot().maxDepth == depth

        override fun setSelected(e: AnActionEvent, selected: Boolean) {
            if (selected) FlowLensSettings.getInstance(project).updateMaxDepth(depth)
        }
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
