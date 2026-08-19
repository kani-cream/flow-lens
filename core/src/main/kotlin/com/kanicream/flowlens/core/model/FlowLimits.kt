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
        /**
         * Measured rather than assumed. 100 came from Milestone 0, where it was
         * the size a feasibility demo laid out quickly — not a ceiling anyone
         * had hit. The first real project hit it immediately: an ordinary Go
         * login handler needs 157 nodes to finish, so the default truncated a
         * normal function by 40%.
         *
         * 250 covers that with room, and is three orders of magnitude below any
         * rendering cost — 800 nodes lay out in 9 ms, and a frame's children are
         * collapsed anyway, so 157 nodes is about 22 cards on screen. What grows
         * with the budget is analysis time, and a reader who needs more can say
         * so in Settings.
         */
        const val DEFAULT_MAX_NODES: Int = 250
    }
}
