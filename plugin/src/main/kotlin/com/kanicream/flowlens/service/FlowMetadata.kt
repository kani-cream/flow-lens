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
}
