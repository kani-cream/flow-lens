package com.kanicream.flowlens.core.model

/**
 * One analyzed callable body. Owns the ordered semantic events extracted from it.
 * Depth counts callable-body crossings only: the root frame is depth 0.
 */
data class FlowFrame(
    val id: FrameId,
    val symbol: FlowSymbol,
    val entryLocation: FlowLocation?,
    val depth: Int,
    val events: List<FlowNode>,
    val bodyComplete: Boolean,
    /**
     * True when something in this body was not represented — a `break`, a
     * `continue`, a short-circuit operand.
     *
     * Recorded per frame rather than per run so the warning can say *where*.
     * A banner that says "something somewhere was simplified" over a
     * hundred-node map is honest and unusable, which is the same failure the
     * stop-reason list was built to fix (`V0.3_SPEC.md` §7.3).
     */
    val controlFlowSimplified: Boolean = false,
) {
    init {
        require(depth >= 0) { "depth must be >= 0" }
    }
}
