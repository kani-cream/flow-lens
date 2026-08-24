package com.kanicream.flowlens.service

/**
 * Removes external calls from a sequence when the reader asked for a map of
 * their own code only (`V1.1_HIDE_EXTERNAL_SPEC.md` §4).
 *
 * Only a call that was not entered may be hidden — an entered body is on the
 * map, and removing the call that leads to it would orphan a frame. And a call
 * that was handed a callback keeps its card: the model attaches a body to its
 * call by adjacency (`V0.5_SPEC.md` §5.5), so removing the call would hang the
 * reader's own lambda on the wrong owner.
 *
 * Like [LibraryGrouping], this is the rule on its own, away from PSI and the
 * run engine, so what disappears is testable without an IDE.
 */
internal object ExternalCallHiding {

    /** What is left to draw, and how many calls the setting removed. */
    data class Hidden<T>(val visible: List<T>, val hiddenCount: Int)

    /**
     * Splits [items] into what stays and a count of what went, in place and in
     * order. An item is hidden when [hideable] says so and the item after it is
     * not a callback attached to it.
     */
    fun <T> hide(
        items: List<T>,
        hideable: (T) -> Boolean,
        attachedCallback: (T) -> Boolean,
    ): Hidden<T> {
        val visible = mutableListOf<T>()
        var hidden = 0
        items.forEachIndexed { index, item ->
            val ownsCallback = items.getOrNull(index + 1)?.let(attachedCallback) == true
            if (hideable(item) && !ownsCallback) {
                hidden += 1
            } else {
                visible += item
            }
        }
        return Hidden(visible.toList(), hidden)
    }
}
