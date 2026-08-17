package com.kanicream.flowlens.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.wm.ToolWindowManager
import com.kanicream.flowlens.FlowLensBundle
import com.kanicream.flowlens.service.FlowAnalysisService

/** Editor context action: analyze the flow of the callable at the caret. */
class AnalyzeFlowAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.text = FlowLensBundle.message("action.analyze.text")
        e.presentation.description = FlowLensBundle.message("action.analyze.description")
        e.presentation.isEnabledAndVisible =
            e.project != null && e.getData(CommonDataKeys.EDITOR) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = editor.virtualFile ?: return
        FlowAnalysisService.getInstance(project).startAnalysis(file, editor.caretModel.offset)
        ToolWindowManager.getInstance(project).getToolWindow("Flow Lens")?.activate(null)
    }
}
