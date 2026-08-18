package com.kanicream.flowlens.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.kanicream.flowlens.core.model.FlowLimits

/**
 * Project-scoped persisted settings (`V0.1_SPEC.md` §19).
 *
 * Analysis code never reads this component during traversal; a run works on the
 * immutable [FlowLimits] snapshot taken at start (guardrails §8), so changing a
 * threshold mid-run cannot produce an internally inconsistent result.
 */
@Service(Service.Level.PROJECT)
@State(name = "FlowLensSettings", storages = [Storage("flowLens.xml")])
class FlowLensSettings : PersistentStateComponent<FlowLensSettings.State> {

    /** Serialized form. Mutable by contract of the XML serializer. */
    class State {
        var maxDepth: Int = FlowLimits.DEFAULT_MAX_DEPTH
        var maxNodes: Int = FlowLimits.DEFAULT_MAX_NODES
        var includeTests: Boolean = false
        var includeLibraries: Boolean = false
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    /**
     * Immutable configuration for one run. Persisted values are clamped rather
     * than trusted: the file can be hand-edited or written by an older version,
     * and an out-of-range value must not break analysis.
     */
    fun snapshot(): FlowLimits = FlowLimits(
        maxDepth = state.maxDepth.coerceIn(MIN_DEPTH, MAX_DEPTH),
        maxNodes = state.maxNodes.coerceIn(MIN_NODES, MAX_NODES),
        includeTests = state.includeTests,
        includeLibraries = state.includeLibraries,
    )

    companion object {
        const val MIN_DEPTH: Int = 1
        const val MAX_DEPTH: Int = 10
        const val MIN_NODES: Int = 10
        const val MAX_NODES: Int = 1000

        fun getInstance(project: Project): FlowLensSettings =
            project.getService(FlowLensSettings::class.java)
    }
}
