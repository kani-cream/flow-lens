package com.kanicream.flowlens.ui

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Platform-managed coroutine scope for tool-window UI collectors. Jobs launched
 * here are cancelled with the project; individual panels also cancel their own
 * jobs on content disposal.
 */
@Service(Service.Level.PROJECT)
class FlowLensUiScope(private val scope: CoroutineScope) {

    fun launch(block: suspend CoroutineScope.() -> Unit): Job = scope.launch(block = block)

    companion object {
        fun getInstance(project: Project): FlowLensUiScope =
            project.getService(FlowLensUiScope::class.java)
    }
}
