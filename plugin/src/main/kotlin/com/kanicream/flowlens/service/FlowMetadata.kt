package com.kanicream.flowlens.service

/** Metadata keys attached to flow nodes. Values are technical tokens, never source text. */
object FlowMetadata {
    /** Present ("depth") when a call could not be entered because of the depth limit. */
    const val LIMIT = "flowlens.limit"
    const val LIMIT_DEPTH = "depth"

    /** Source origin of the resolved target declaration (SourceOrigin name). */
    const val ORIGIN = "flowlens.origin"

    /** Present ("true") when the target declaration is in test sources. */
    const val TEST_SOURCE = "flowlens.testSource"

    /**
     * Present ("true") when the call may not execute because it sits inside a
     * branch, loop body, catch clause, or short-circuit operand. v0.1 has no
     * branch model, so the renderer uses this instead of drawing a connector
     * that would claim a proven path (`V0.1_SPEC.md` §13).
     */
    const val CONDITIONAL = "flowlens.conditional"
}
