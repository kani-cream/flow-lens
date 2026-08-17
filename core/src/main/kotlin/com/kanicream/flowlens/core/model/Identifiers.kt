package com.kanicream.flowlens.core.model

/**
 * Identity of one analysis run. UI must ignore events carrying a [RunId] that is not
 * the current run, so a cancelled run can never mutate a newer canvas.
 */
@JvmInline
value class RunId(val value: Long)

/** Identity of one semantic event (call site), unique within a run. */
@JvmInline
value class NodeId(val value: Int)

/** Identity of one analyzed callable body, unique within a run. */
@JvmInline
value class FrameId(val value: Int)

/**
 * Opaque handle to a source location. The plugin layer maps it to a platform
 * navigation handle; core never sees PSI.
 */
@JvmInline
value class LocationId(val value: Int)
