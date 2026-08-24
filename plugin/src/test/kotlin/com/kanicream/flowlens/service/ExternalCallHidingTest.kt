package com.kanicream.flowlens.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The hiding rule on its own (`V1.1_HIDE_EXTERNAL_SPEC.md` §4), away from PSI
 * and the run engine, in the same spirit as [LibraryGroupingTest]: which calls
 * disappear decides whether the map is still honest, so the rule gets a test of
 * its own.
 */
class ExternalCallHidingTest {

    /** An item is a hideable call, an attached callback, or opaque. */
    private data class Item(
        val name: String,
        val hideable: Boolean = false,
        val attachedCallback: Boolean = false,
    )

    private fun hide(items: List<Item>): ExternalCallHiding.Hidden<Item> =
        ExternalCallHiding.hide(
            items = items,
            hideable = { it.hideable },
            attachedCallback = { it.attachedCallback },
        )

    @Test
    fun `a hideable call disappears and is counted`() {
        val result = hide(listOf(Item("append", hideable = true), Item("mine")))
        assertEquals(listOf(Item("mine")), result.visible)
        assertEquals(1, result.hiddenCount)
    }

    @Test
    fun `everything else passes through in place and in order`() {
        val items = listOf(Item("first"), Item("append", hideable = true), Item("last"))
        val result = hide(items)
        assertEquals(listOf(Item("first"), Item("last")), result.visible)
        assertEquals(1, result.hiddenCount)
    }

    @Test
    fun `a call handed a callback keeps its card`() {
        // The body after it belongs to it, and the model attaches the two by
        // adjacency; removing the call would hang the reader's own lambda on
        // the wrong owner.
        val forEach = Item("forEach", hideable = true)
        val lambda = Item("{ }", attachedCallback = true)
        val result = hide(listOf(forEach, lambda, Item("mine")))
        assertEquals(listOf(forEach, lambda, Item("mine")), result.visible)
        assertEquals(0, result.hiddenCount)
    }

    @Test
    fun `an unattached body does not rescue the call before it`() {
        // A body invoked where it is written names no call; adjacency to one is
        // coincidence, not ownership (`V0.5_SPEC.md` §5.5).
        val result = hide(
            listOf(Item("append", hideable = true), Item("{ }", attachedCallback = false)),
        )
        assertEquals(listOf(Item("{ }")), result.visible)
        assertEquals(1, result.hiddenCount)
    }

    @Test
    fun `a whole run disappears without leaving a group behind`() {
        val result = hide(
            listOf(
                Item("append1", hideable = true),
                Item("append2", hideable = true),
                Item("append3", hideable = true),
                Item("mine"),
            ),
        )
        assertEquals(listOf(Item("mine")), result.visible)
        assertEquals(3, result.hiddenCount)
    }

    @Test
    fun `an empty sequence stays empty`() {
        val result = hide(emptyList())
        assertEquals(emptyList<Item>(), result.visible)
        assertEquals(0, result.hiddenCount)
    }

    @Test
    fun `a hideable call at the end of the sequence is hidden`() {
        val result = hide(listOf(Item("mine"), Item("append", hideable = true)))
        assertEquals(listOf(Item("mine")), result.visible)
        assertEquals(1, result.hiddenCount)
    }
}
