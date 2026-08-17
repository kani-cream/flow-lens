package com.kanicream.flowlens.core.model

/**
 * Immutable per-run analysis bounds, snapshotted at run start
 * (IMPLEMENTATION_GUARDRAILS.md section 8).
 */
data class FlowLimits(
    val maxDepth: Int = DEFAULT_MAX_DEPTH,
    val maxNodes: Int = DEFAULT_MAX_NODES,
    val includeTests: Boolean = false,
    val includeLibraries: Boolean = false,
) {
    init {
        require(maxDepth >= 1) { "maxDepth must be >= 1" }
        require(maxNodes >= 2) { "maxNodes must leave room for at least one event and a limit marker" }
    }

    companion object {
        const val DEFAULT_MAX_DEPTH: Int = 3
        const val DEFAULT_MAX_NODES: Int = 100
    }
}
