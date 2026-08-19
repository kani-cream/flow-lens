package com.kanicream.flowlens.service

import com.kanicream.flowlens.core.model.ResolutionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The grouping rule on its own (`V1.0_GROUPING_SPEC.md` §3), away from PSI and
 * the run engine. What decides whether a map is readable is which runs collapse
 * and which do not, so that question gets a test of its own.
 */
class LibraryGroupingTest {

    private fun key(container: String) = LibraryGrouping.GroupingKey("go", container)

    /** An item is either groupable under a key, or opaque. */
    private data class Item(val name: String, val key: LibraryGrouping.GroupingKey?)

    private fun shape(items: List<Item>): List<String> = LibraryGrouping.collapse(
        items = items,
        keyOf = { it.key },
        group = { k, run -> "${k.container}×${run.size}" },
        single = { it.name },
    )

    @Test
    fun `A three in a row become one group`() {
        val items = listOf(Item("a", key("gin")), Item("b", key("gin")), Item("c", key("gin")))
        assertEquals(listOf("gin×3"), shape(items))
    }

    @Test
    fun `B two in a row stay two cards`() {
        // Two cards are not yet noise, and a group of two costs a concept to
        // save a line.
        val items = listOf(Item("a", key("gin")), Item("b", key("gin")))
        assertEquals(listOf("a", "b"), shape(items))
    }

    @Test
    fun `C a different library between them ends the run`() {
        val items = listOf(Item("a", key("gin")), Item("b", key("strings")), Item("c", key("gin")))
        assertEquals(listOf("a", "b", "c"), shape(items))
    }

    @Test
    fun `D the reader's own code separates two groups`() {
        val items = listOf(
            Item("a", key("gin")), Item("b", key("gin")), Item("c", key("gin")),
            Item("mine", null),
            Item("d", key("gin")), Item("e", key("gin")), Item("f", key("gin")),
        )
        assertEquals(listOf("gin×3", "mine", "gin×3"), shape(items))
    }

    @Test
    fun `order is preserved around a group`() {
        val items = listOf(
            Item("first", null),
            Item("a", key("gin")), Item("b", key("gin")), Item("c", key("gin")),
            Item("last", null),
        )
        assertEquals(listOf("first", "gin×3", "last"), shape(items))
    }

    @Test
    fun `E a call whose body is on the map is never groupable`() {
        assertNull(
            "following it is the point",
            LibraryGrouping.keyOf(ResolutionStatus.EXTERNAL, entered = true, languageId = "go", container = "gin"),
        )
    }

    @Test
    fun `a dead end in the reader's own code keeps its own card`() {
        assertNull(
            LibraryGrouping.keyOf(
                ResolutionStatus.PROJECT_LOCAL,
                entered = false,
                languageId = "go",
                container = "handler",
            ),
        )
    }

    @Test
    fun `an unresolved call is not swept into a library`() {
        assertNull(
            LibraryGrouping.keyOf(ResolutionStatus.UNRESOLVED, entered = false, languageId = "go", container = "gin"),
        )
    }

    @Test
    fun `external and built-in are both groupable`() {
        assertEquals(
            key("gin"),
            LibraryGrouping.keyOf(ResolutionStatus.EXTERNAL, false, "go", "gin"),
        )
        assertEquals(
            key("builtin"),
            LibraryGrouping.keyOf(ResolutionStatus.BUILT_IN, false, "go", "builtin"),
        )
    }

    @Test
    fun `a call with no container cannot be named, so it is not grouped`() {
        assertNull(LibraryGrouping.keyOf(ResolutionStatus.EXTERNAL, false, "go", null))
        assertNull(LibraryGrouping.keyOf(ResolutionStatus.EXTERNAL, false, "go", " "))
    }

    @Test
    fun `H a long run is still one group`() {
        val items = (1..47).map { Item("call$it", key("gin")) }
        assertEquals(listOf("gin×47"), shape(items))
    }
}
