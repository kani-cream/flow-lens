package com.kanicream.flowlens.core.engine

import com.kanicream.flowlens.core.model.FrameId
import com.kanicream.flowlens.core.model.LocationId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PendingFrameQueueTest {

    private fun frame(id: Int, depth: Int): PendingFrame =
        PendingFrame(FrameId(id), LocationId(id), depth, CyclePath.root("root"))

    @Test
    fun `lower depths dequeue before higher depths regardless of enqueue order`() {
        val queue = PendingFrameQueue()
        queue.enqueue(frame(1, 2))
        queue.enqueue(frame(2, 1))
        queue.enqueue(frame(3, 0))
        assertEquals(3, queue.dequeue()?.frameId?.value)
        assertEquals(2, queue.dequeue()?.frameId?.value)
        assertEquals(1, queue.dequeue()?.frameId?.value)
    }

    @Test
    fun `frames at the same depth keep FIFO order`() {
        val queue = PendingFrameQueue()
        queue.enqueue(frame(1, 1))
        queue.enqueue(frame(2, 1))
        queue.enqueue(frame(3, 1))
        assertEquals(listOf(1, 2, 3), buildList {
            while (!queue.isEmpty) add(queue.dequeue()!!.frameId.value)
        })
    }

    @Test
    fun `later shallow frames still run before earlier deep frames`() {
        // Breadth-first bias: a depth-1 frame discovered late must not wait behind
        // an early depth-2 frame (PLAN.md section 10).
        val queue = PendingFrameQueue()
        queue.enqueue(frame(1, 1))
        assertEquals(1, queue.dequeue()?.frameId?.value)
        queue.enqueue(frame(2, 2))
        queue.enqueue(frame(3, 1))
        assertEquals(3, queue.dequeue()?.frameId?.value)
        assertEquals(2, queue.dequeue()?.frameId?.value)
    }

    @Test
    fun `empty queue returns null`() {
        val queue = PendingFrameQueue()
        assertTrue(queue.isEmpty)
        assertNull(queue.dequeue())
    }
}
