package com.kanicream.flowlens.core.model

/**
 * Language-neutral source location descriptor.
 *
 * [handle] is resolved to a real navigation target by the plugin layer. The
 * presentable fields exist for details/diagnostics display and tests; they are not
 * durable identity (REPO_LENS_LESSONS.md 3.8).
 */
data class FlowLocation(
    val handle: LocationId,
    val presentablePath: String,
    val line: Int,
)
