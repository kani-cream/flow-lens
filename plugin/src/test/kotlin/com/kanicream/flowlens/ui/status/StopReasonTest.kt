package com.kanicream.flowlens.ui.status

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.kanicream.flowlens.core.engine.FlowEventSpec
import com.kanicream.flowlens.core.engine.FlowModelBuilder
import com.kanicream.flowlens.core.engine.StructureSpec
import com.kanicream.flowlens.core.model.BranchKind
import com.kanicream.flowlens.core.model.DispatchConfidence
import com.kanicream.flowlens.core.model.FlowAnalysisResult
import com.kanicream.flowlens.core.model.FlowLimits
import com.kanicream.flowlens.core.model.FlowNodeKind
import com.kanicream.flowlens.core.model.FlowProgress
import com.kanicream.flowlens.core.model.FlowProgressStage
import com.kanicream.flowlens.core.model.FlowResultStatus
import com.kanicream.flowlens.core.model.FlowSymbol
import com.kanicream.flowlens.core.model.ResolutionStatus
import com.kanicream.flowlens.core.model.RunId
import com.kanicream.flowlens.service.FlowMetadata

/**
 * The status area's account of a run (`V0.3_SPEC.md` §7, acceptance O–S): what
 * is being analyzed, how much budget is left, and why the map stops.
 */
class StopReasonTest : BasePlatformTestCase() {

    private fun symbol(name: String) = FlowSymbol("java", "$name()", "Owner", "java:Owner#$name()")

    private fun call(
        name: String,
        resolution: ResolutionStatus = ResolutionStatus.PROJECT_LOCAL,
        metadata: Map<String, String> = emptyMap(),
    ) = FlowEventSpec(
        kind = FlowNodeKind.CALL,
        callSiteLocation = null,
        targetSymbol = symbol(name),
        resolutionStatus = resolution,
        dispatchConfidence = DispatchConfidence.EXACT,
        metadata = metadata,
    )

    private fun progress(
        stage: FlowProgressStage,
        nodes: Int = 0,
        budget: Int = 100,
        callable: String? = null,
    ) = FlowProgress(
        runId = RunId(1),
        stage = stage,
        nodesProduced = nodes,
        framesAnalyzed = 1,
        exactCount = 0,
        declaredTargetCount = 0,
        ambiguousCount = 0,
        externalCount = 0,
        unresolvedCount = 0,
        elapsedMillis = 1,
        currentCallable = callable,
        nodeBudget = budget,
    )

    private fun resultWith(
        status: FlowResultStatus = FlowResultStatus.COMPLETED,
        build: (FlowModelBuilder, com.kanicream.flowlens.core.model.FrameId) -> Unit,
    ): FlowAnalysisResult {
        val builder = FlowModelBuilder(RunId(1), FlowLimits(), 0)
        val root = builder.openRootFrame(symbol("run"), null)
        build(builder, root)
        return builder.snapshot(status)
    }

    fun `test O the stage names the callable being analyzed`() {
        val state = FlowStatusModel.stateOf(
            progress(FlowProgressStage.ANALYZING_CHILD_FRAMES, callable = "charge()"),
            null,
        )
        assertEquals("charge()", state.currentCallable)
    }

    fun `test P the budget bar fills as nodes are produced`() {
        val quiet = FlowStatusModel.stateOf(
            progress(FlowProgressStage.ANALYZING_CHILD_FRAMES, nodes = 25, budget = 100),
            null,
        )
        assertEquals(0.25, quiet.budgetFraction!!, 0.001)

        val full = FlowStatusModel.stateOf(
            progress(FlowProgressStage.ANALYZING_CHILD_FRAMES, nodes = 120, budget = 100),
            null,
        )
        assertEquals("a full bar means truncation, not overflow", 1.0, full.budgetFraction!!, 0.001)
    }

    fun `test the bar disappears when nothing is running`() {
        val result = resultWith { builder, root -> builder.addEvent(root, call("a")) }
        val state = FlowStatusModel.stateOf(
            progress(FlowProgressStage.COMPLETED, nodes = 5),
            result,
        )
        assertNull("a finished run has no budget left to spend", state.budgetFraction)
    }

    fun `test Q a finished run counts the reasons its map stops`() {
        val result = resultWith { builder, root ->
            builder.addEvent(
                root,
                call("blocked", metadata = mapOf(FlowMetadata.LIMIT to FlowMetadata.LIMIT_DEPTH)),
            )
            builder.addEvent(root, call("mystery", resolution = ResolutionStatus.UNRESOLVED))
            builder.addEvent(root, call("trim", resolution = ResolutionStatus.EXTERNAL))
            builder.addEvent(root, call("plain"))
        }
        val state = FlowStatusModel.stateOf(progress(FlowProgressStage.COMPLETED), result)

        assertEquals(listOf(1, 1, 1), state.stopReasons.map { it.count })
        assertTrue(state.stopReasons.all { it.firstNode != null })
        assertFalse(
            "a raw bundle key must never reach the status area",
            state.stopReasons.any { it.text.startsWith("status.") },
        )
    }

    fun `test a reason counts nodes inside branch sections too`() {
        val result = resultWith { builder, root ->
            val condition = builder.openStructure(
                root,
                StructureSpec(FlowNodeKind.CONDITION, null, "flag"),
            )!!
            builder.openBranch(condition, BranchKind.THEN, null)
            builder.addEvent(root, call("mystery", resolution = ResolutionStatus.UNRESOLVED))
            builder.closeStructure(condition)
        }
        val state = FlowStatusModel.stateOf(progress(FlowProgressStage.COMPLETED), result)
        assertEquals("a section is not a blind spot", 1, state.stopReasons.single().count)
    }

    fun `test R a reason points at the first node it describes`() {
        val result = resultWith { builder, root ->
            builder.addEvent(root, call("plain"))
            builder.addEvent(root, call("mystery", resolution = ResolutionStatus.UNRESOLVED))
            builder.addEvent(root, call("other", resolution = ResolutionStatus.UNRESOLVED))
        }
        val state = FlowStatusModel.stateOf(progress(FlowProgressStage.COMPLETED), result)
        val reason = state.stopReasons.single()

        assertEquals(2, reason.count)
        val firstUnresolved = result.rootFrame!!.events
            .first { it.resolutionStatus == ResolutionStatus.UNRESOLVED }
        assertEquals(firstUnresolved.id, reason.firstNode)
    }

    fun `test S a run with nothing to explain shows no reasons`() {
        val result = resultWith { builder, root -> builder.addEvent(root, call("plain")) }
        val state = FlowStatusModel.stateOf(progress(FlowProgressStage.COMPLETED), result)
        assertTrue(
            "a line that always appears would stop meaning anything",
            state.stopReasons.isEmpty(),
        )
    }

    fun `test reasons are withheld while the run is still going`() {
        val result = resultWith(FlowResultStatus.RUNNING) { builder, root ->
            builder.addEvent(root, call("mystery", resolution = ResolutionStatus.UNRESOLVED))
        }
        val running = FlowStatusModel.stateOf(
            progress(FlowProgressStage.ANALYZING_CHILD_FRAMES),
            result,
        )
        assertTrue(
            "a count that changes as the run proceeds would be noise",
            running.stopReasons.isEmpty(),
        )
    }
}
