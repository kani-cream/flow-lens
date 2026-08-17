package com.kanicream.flowlens.core.engine

import com.kanicream.flowlens.core.model.FrameId
import com.kanicream.flowlens.core.model.LocationId

/**
 * One scheduled child-frame analysis. [callableHandle] identifies the resolved
 * target declaration in the plugin layer; core never holds platform objects.
 */
data class PendingFrame(
    val frameId: FrameId,
    val callableHandle: LocationId,
    val depth: Int,
    val path: CyclePath,
)

/**
 * Breadth-first-biased scheduling structure: frames are dispatched strictly in
 * ascending depth order, FIFO within one depth, so the root-level picture completes
 * before any early branch grows deep (PLAN.md section 10).
 *
 * Not thread-safe; owned by a single analysis run.
 */
class PendingFrameQueue {
    private val byDepth = sortedMapOf<Int, ArrayDeque<PendingFrame>>()

    val isEmpty: Boolean get() = byDepth.isEmpty()

    fun enqueue(frame: PendingFrame) {
        byDepth.getOrPut(frame.depth) { ArrayDeque() }.addLast(frame)
    }

    fun dequeue(): PendingFrame? {
        val entry = byDepth.firstEntry() ?: return null
        val frame = entry.value.removeFirst()
        if (entry.value.isEmpty()) byDepth.remove(entry.key)
        return frame
    }
}
