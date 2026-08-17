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
) {
    init {
        require(depth >= 0) { "depth must be >= 0" }
    }
}
