package com.kanicream.flowlens.testutil

import com.intellij.openapi.project.Project
import com.kanicream.flowlens.core.model.FlowAnalysisResult
import com.kanicream.flowlens.service.FlowAnalysisService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Shared helpers for tests that drive the analysis service.
 *
 * Light fixtures reuse one project across the tests in a class, so a run left
 * in flight by one test would keep publishing into the next one. Every such test
 * class quiesces the service in tearDown through [quiesceAnalysis].
 */
object AnalysisTestSupport {

    /** Cancels any active run and waits until it has reached a terminal state. */
    fun quiesceAnalysis(project: Project) {
        val service = FlowAnalysisService.getInstance(project)
        service.cancelActive()
        runBlocking {
            withTimeoutOrNull(30_000) {
                service.results.first { it == null || it.isTerminal }
            }
        }
    }

    /** Waits for the current run to reach a terminal state and returns it. */
    fun awaitTerminalResult(project: Project, timeoutMillis: Long = 60_000): FlowAnalysisResult =
        runBlocking {
            withTimeout(timeoutMillis) {
                FlowAnalysisService.getInstance(project).results.first { it != null && it.isTerminal }!!
            }
        }
}
