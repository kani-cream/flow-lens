package com.kanicream.flowlens.service

import org.jetbrains.annotations.TestOnly

/**
 * Instrumentation of the run scheduler for lifecycle tests
 * (`TEST_STRATEGY.md` §6: instrument the scheduler instead of asserting timings).
 *
 * The hook fires on the analysis thread immediately before each bounded frame
 * operation and outside any read action, which is what makes source-mutation,
 * cancellation, and read-granularity behavior deterministic to test. Production
 * runs leave the callback null, so the only cost is one volatile read per frame.
 */
internal object FlowRunHooks {

    /** One scheduled bounded operation: the root extraction is index 0. */
    data class FrameOperation(val index: Int, val depth: Int)

    @Volatile
    var beforeFrameOperation: ((FrameOperation) -> Unit)? = null

    fun fire(operation: FrameOperation) {
        beforeFrameOperation?.invoke(operation)
    }

    @TestOnly
    fun reset() {
        beforeFrameOperation = null
    }
}
