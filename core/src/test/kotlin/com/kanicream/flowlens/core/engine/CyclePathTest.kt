package com.kanicream.flowlens.core.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CyclePathTest {

    @Test
    fun `detects a symbol already on the traversal path`() {
        val path = CyclePath.root("A").push("B")
        assertTrue(path.contains("A"))
        assertTrue(path.contains("B"))
        assertFalse(path.contains("C"))
    }

    @Test
    fun `push returns a new path and leaves the original unchanged`() {
        val root = CyclePath.root("A")
        val extended = root.push("B")
        assertFalse(root.contains("B"))
        assertTrue(extended.contains("B"))
        assertEquals(1, root.depth)
        assertEquals(2, extended.depth)
    }

    @Test
    fun `independent branches may repeat the same symbol`() {
        // A -> B and A -> C -> B: B on the second branch is not a cycle of the first.
        val root = CyclePath.root("A")
        val branch1 = root.push("B")
        val branch2 = root.push("C")
        assertTrue(branch1.contains("B"))
        assertFalse(branch2.contains("B"))
        assertTrue(branch2.push("B").contains("B"))
    }
}
