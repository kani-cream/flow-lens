package com.kanicream.flowlens.service

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiModificationTracker
import com.kanicream.flowlens.analysis.FlowAnalyzerRegistry
import com.kanicream.flowlens.core.engine.CyclePath
import com.kanicream.flowlens.core.engine.FlowEventSpec
import com.kanicream.flowlens.core.engine.FlowModelBuilder
import com.kanicream.flowlens.core.engine.PendingFrame
import com.kanicream.flowlens.core.engine.PendingFrameQueue
import com.kanicream.flowlens.core.model.DispatchConfidence
import com.kanicream.flowlens.core.model.FlowDiagnostic
import com.kanicream.flowlens.core.model.FlowDiagnosticSeverity
import com.kanicream.flowlens.core.model.FlowLimits
import com.kanicream.flowlens.core.model.FlowLocation
import com.kanicream.flowlens.core.model.FlowNodeKind
import com.kanicream.flowlens.core.model.FlowProgress
import com.kanicream.flowlens.core.model.FlowProgressStage
import com.kanicream.flowlens.core.model.FlowResultStatus
import com.kanicream.flowlens.core.model.FlowSymbol
import com.kanicream.flowlens.core.model.ResolutionStatus
import com.kanicream.flowlens.core.model.RunId
import com.kanicream.flowlens.core.model.SourceOrigin
import com.intellij.openapi.application.smartReadAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private val LOG = logger<FlowAnalysisRun>()

/**
 * One progressive analysis run.
 *
 * Threading contract (IMPLEMENTATION_GUARDRAILS.md sections 5-6): every PSI touch
 * happens inside one bounded, cancellable smart read action per frame; between
 * frames the read lock is released and cancellation is observed. Nothing here runs
 * on the EDT.
 */
internal class FlowAnalysisRun(
    private val project: Project,
    private val runId: RunId,
    private val file: VirtualFile,
    private val offset: Int,
    private val limits: FlowLimits,
    private val handles: RunHandles,
    private val publishResult: (FlowAnalysisResultEvent) -> Unit,
    private val publishProgress: (FlowProgress) -> Unit,
) {
    private val startNanos = System.nanoTime()
    private var nodesProduced = 0
    private var framesAnalyzed = 0
    private var exactCount = 0
    private var declaredTargetCount = 0
    private var ambiguousCount = 0
    private var externalCount = 0
    private var unresolvedCount = 0

    suspend fun execute() {
        progress(FlowProgressStage.STARTING)
        var builder: FlowModelBuilder? = null
        try {
            // smartReadAction waits for indexes instead of producing mass UNRESOLVED
            // output (V0.1_SPEC.md section 15); the visible waiting state comes from
            // this stage event.
            progress(FlowProgressStage.WAITING_FOR_INDEXES)
            val root = smartReadAction(project) { findRoot() }
            if (root == null) {
                progress(FlowProgressStage.FAILED)
                publishResult(
                    FlowAnalysisResultEvent(
                        runId,
                        FlowModelBuilder(runId, limits, sourceRevision = 0).apply {
                            addDiagnostic(
                                FlowDiagnostic(
                                    FlowDiagnosticSeverity.ERROR,
                                    "flow.error.no.entry.point",
                                ),
                            )
                        }.snapshot(FlowResultStatus.FAILED),
                    ),
                )
                return
            }

            progress(FlowProgressStage.EXTRACTING_ROOT)
            val modelBuilder = FlowModelBuilder(runId, limits, root.modificationCount)
            builder = modelBuilder
            val rootFrameId = modelBuilder.openRootFrame(root.symbol, root.entryLocation)
            publishResult(FlowAnalysisResultEvent(runId, modelBuilder.snapshot(FlowResultStatus.RUNNING)))

            val queue = PendingFrameQueue()
            queue.enqueue(
                PendingFrame(
                    frameId = rootFrameId,
                    callableHandle = root.entryLocation.handle,
                    depth = 0,
                    path = CyclePath.root(root.symbol.key),
                ),
            )

            var truncated = false
            var stale = false
            frames@ while (!queue.isEmpty) {
                currentCoroutineContext().ensureActive()
                val task = queue.dequeue() ?: break
                progress(
                    if (task.depth == 0) FlowProgressStage.RESOLVING_ROOT_CALLS
                    else FlowProgressStage.ANALYZING_CHILD_FRAMES,
                )

                val computation = try {
                    smartReadAction(project) { computeFrame(task, root.modificationCount) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: Exception) {
                    // One frame failure must not fail the analysis
                    // (IMPLEMENTATION_GUARDRAILS.md section 9).
                    LOG.warn("Flow Lens frame analysis failed: ${e.javaClass.simpleName}")
                    modelBuilder.addDiagnostic(
                        FlowDiagnostic(
                            FlowDiagnosticSeverity.WARNING,
                            "flow.error.frame.failed",
                            detail = e.javaClass.simpleName,
                        ),
                    )
                    modelBuilder.markFrameComplete(task.frameId)
                    continue
                }

                when (computation) {
                    is FrameComputation.Stale -> {
                        stale = true
                        break@frames
                    }
                    is FrameComputation.Invalid -> {
                        modelBuilder.addDiagnostic(
                            FlowDiagnostic(
                                FlowDiagnosticSeverity.WARNING,
                                "flow.error.frame.invalidated",
                            ),
                        )
                        modelBuilder.markFrameComplete(task.frameId)
                    }
                    is FrameComputation.Computed -> {
                        if (computation.controlFlowSimplified) modelBuilder.controlFlowIncomplete = true
                        for (call in computation.calls) {
                            if (!appendCall(modelBuilder, queue, task, call)) {
                                truncated = true
                                break@frames
                            }
                        }
                        modelBuilder.markFrameComplete(task.frameId)
                    }
                }
                framesAnalyzed += 1
                publishResult(FlowAnalysisResultEvent(runId, modelBuilder.snapshot(FlowResultStatus.RUNNING)))
            }

            val status = when {
                stale -> FlowResultStatus.STALE
                truncated || modelBuilder.wasTruncated -> FlowResultStatus.TRUNCATED
                else -> FlowResultStatus.COMPLETED
            }
            publishResult(FlowAnalysisResultEvent(runId, modelBuilder.snapshot(status)))
            progress(
                when (status) {
                    FlowResultStatus.STALE -> FlowProgressStage.STALE
                    FlowResultStatus.TRUNCATED -> FlowProgressStage.TRUNCATED
                    else -> FlowProgressStage.COMPLETED
                },
            )
            logRunSummary(status)
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                builder?.let {
                    publishResult(FlowAnalysisResultEvent(runId, it.snapshot(FlowResultStatus.CANCELLED)))
                }
                progress(FlowProgressStage.CANCELLED)
            }
            throw e
        } catch (e: ProcessCanceledException) {
            // A PCE escaping outside coroutine cancellation must still leave the run
            // in a terminal state, or the UI would show a transient stage forever.
            withContext(NonCancellable) {
                builder?.let {
                    publishResult(FlowAnalysisResultEvent(runId, it.snapshot(FlowResultStatus.CANCELLED)))
                }
                progress(FlowProgressStage.CANCELLED)
            }
            throw e
        } catch (e: Throwable) {
            LOG.warn("Flow Lens run failed: ${e.javaClass.simpleName}", e)
            builder?.let {
                it.addDiagnostic(
                    FlowDiagnostic(
                        FlowDiagnosticSeverity.ERROR,
                        "flow.error.run.failed",
                        detail = e.javaClass.simpleName,
                    ),
                )
                publishResult(FlowAnalysisResultEvent(runId, it.snapshot(FlowResultStatus.FAILED)))
            }
            progress(FlowProgressStage.FAILED)
        }
    }

    /** Appends one computed call. Returns false when the node budget forced truncation. */
    private fun appendCall(
        builder: FlowModelBuilder,
        queue: PendingFrameQueue,
        task: PendingFrame,
        call: ComputedCall,
    ): Boolean {
        val isCycle = call.cycleKey != null && task.path.contains(call.cycleKey)
        val depthExceeded = call.recursable && !isCycle && task.depth + 1 > limits.maxDepth
        val spec = when {
            isCycle -> call.spec.copy(kind = FlowNodeKind.CYCLE)
            depthExceeded -> call.spec.copy(
                metadata = call.spec.metadata + (FlowMetadata.LIMIT to FlowMetadata.LIMIT_DEPTH),
            )
            else -> call.spec
        }
        val nodeId = builder.addEvent(task.frameId, spec)
        if (nodeId == null) {
            builder.addLimitEvent(task.frameId)
            nodesProduced = builder.nodeCount
            return false
        }
        countResolution(spec)
        nodesProduced = builder.nodeCount
        if (call.recursable && !isCycle && !depthExceeded &&
            call.childSymbol != null && call.childEntryLocation != null
        ) {
            val childFrameId = builder.openChildFrame(
                task.frameId, nodeId, call.childSymbol, call.childEntryLocation,
            )
            queue.enqueue(
                PendingFrame(
                    frameId = childFrameId,
                    callableHandle = call.childEntryLocation.handle,
                    depth = task.depth + 1,
                    path = task.path.push(call.cycleKey ?: call.childSymbol.key),
                ),
            )
        }
        return true
    }

    private fun countResolution(spec: FlowEventSpec) {
        when (spec.resolutionStatus) {
            ResolutionStatus.EXTERNAL -> externalCount += 1
            ResolutionStatus.UNRESOLVED -> unresolvedCount += 1
            else -> Unit
        }
        when (spec.dispatchConfidence) {
            DispatchConfidence.EXACT -> exactCount += 1
            DispatchConfidence.DECLARED_TARGET -> declaredTargetCount += 1
            DispatchConfidence.AMBIGUOUS -> ambiguousCount += 1
            else -> Unit
        }
    }

    // ---- read-action units ----

    private class RootInfo(
        val symbol: FlowSymbol,
        val entryLocation: FlowLocation,
        val modificationCount: Long,
    )

    private sealed interface FrameComputation {
        object Stale : FrameComputation
        object Invalid : FrameComputation
        class Computed(
            val calls: List<ComputedCall>,
            val controlFlowSimplified: Boolean,
        ) : FrameComputation
    }

    private class ComputedCall(
        val spec: FlowEventSpec,
        val recursable: Boolean,
        val cycleKey: String?,
        val childSymbol: FlowSymbol?,
        val childEntryLocation: FlowLocation?,
    )

    /** Runs inside a read action. */
    private fun findRoot(): RootInfo? {
        val psiFile = PsiManager.getInstance(project).findFile(file) ?: return null
        val match = FlowAnalyzerRegistry.findEntryPoint(psiFile, offset) ?: return null
        val (analyzer, entry) = match
        return RootInfo(
            symbol = analyzer.describeCallable(entry),
            entryLocation = handles.locationOf(entry),
            modificationCount = PsiModificationTracker.getInstance(project).modificationCount,
        )
    }

    /** Runs inside a read action; extracts and resolves exactly one frame. */
    private fun computeFrame(task: PendingFrame, expectedModificationCount: Long): FrameComputation {
        // A PSI change invalidates the run's source assumptions
        // (V0.1_SPEC.md section 15): stale, never a silent mix of revisions.
        if (PsiModificationTracker.getInstance(project).modificationCount != expectedModificationCount) {
            return FrameComputation.Stale
        }
        val callable = handles.pointer(task.callableHandle)?.element
            ?: return FrameComputation.Invalid
        if (!callable.isValid) return FrameComputation.Invalid
        val analyzer = FlowAnalyzerRegistry.forDeclaration(callable)
            ?: return FrameComputation.Invalid

        val extraction = analyzer.extractDirectFlow(callable)
        val calls = extraction.calls.map { extracted ->
            val target = try {
                analyzer.resolveCall(extracted)
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                LOG.warn("Flow Lens call resolution failed: ${e.javaClass.simpleName}")
                com.kanicream.flowlens.analysis.ResolvedCallTarget.UNRESOLVED
            }
            val metadata = buildMap {
                put(FlowMetadata.ORIGIN, target.sourceOrigin.name)
                if (target.inTestSource) put(FlowMetadata.TEST_SOURCE, "true")
            }
            val recursable = target.hasAnalyzableBody &&
                target.resolutionStatus == ResolutionStatus.PROJECT_LOCAL &&
                target.sourceOrigin == SourceOrigin.PHYSICAL_SOURCE &&
                (target.dispatchConfidence == DispatchConfidence.EXACT ||
                    target.dispatchConfidence == DispatchConfidence.DECLARED_TARGET) &&
                (limits.includeTests || !target.inTestSource)
            val symbol = target.symbol ?: fallbackSymbol(analyzer.languageId, extracted.calleeShortName)
            ComputedCall(
                spec = FlowEventSpec(
                    kind = if (target.isConstructor) FlowNodeKind.CONSTRUCTOR else extracted.kind,
                    callSiteLocation = handles.locationOf(extracted.callSite),
                    targetSymbol = if (target.resolutionStatus == ResolutionStatus.UNRESOLVED) {
                        fallbackSymbol(analyzer.languageId, extracted.calleeShortName)
                    } else {
                        symbol
                    },
                    targetLocation = target.declaration
                        ?.takeIf { it.isValid && it.containingFile != null }
                        ?.let { handles.locationOf(it) },
                    resolutionStatus = target.resolutionStatus,
                    dispatchConfidence = target.dispatchConfidence,
                    executionMode = extracted.executionMode,
                    orderingStatus = extracted.orderingStatus,
                    metadata = metadata,
                ),
                recursable = recursable,
                cycleKey = target.symbol?.key,
                childSymbol = target.symbol,
                childEntryLocation = if (recursable && target.declaration != null) {
                    handles.locationOf(target.declaration)
                } else {
                    null
                },
            )
        }
        return FrameComputation.Computed(calls, extraction.controlFlowSimplified)
    }

    private fun fallbackSymbol(languageId: String, shortName: String): FlowSymbol =
        FlowSymbol(
            languageId = languageId,
            displayName = "$shortName()",
            containerName = null,
            key = "$languageId:?#$shortName",
        )

    private fun progress(stage: FlowProgressStage) {
        publishProgress(buildProgress(stage))
    }

    private fun buildProgress(stage: FlowProgressStage): FlowProgress =
        FlowProgress(
            runId = runId,
            stage = stage,
            nodesProduced = nodesProduced,
            framesAnalyzed = framesAnalyzed,
            exactCount = exactCount,
            declaredTargetCount = declaredTargetCount,
            ambiguousCount = ambiguousCount,
            externalCount = externalCount,
            unresolvedCount = unresolvedCount,
            elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000,
        )

    private fun logRunSummary(status: FlowResultStatus) {
        LOG.info(
            "runId=${runId.value} status=$status frames=$framesAnalyzed " +
                "exact=$exactCount declared=$declaredTargetCount ambiguous=$ambiguousCount " +
                "external=$externalCount unresolved=$unresolvedCount " +
                "elapsed=${(System.nanoTime() - startNanos) / 1_000_000}ms",
        )
    }
}

/** A result snapshot tagged with its run identity for stale-event rejection. */
internal class FlowAnalysisResultEvent(
    val runId: RunId,
    val result: com.kanicream.flowlens.core.model.FlowAnalysisResult,
)
