package com.kanicream.flowlens.service

import com.kanicream.flowlens.core.model.ResolutionStatus

/**
 * Collapses runs of library calls that were not entered into single groups
 * (`V1.0_GROUPING_SPEC.md` §3).
 *
 * The rule is about **repetition, not about libraries**. Forty-seven
 * `RouterGroup.GET()` cards say nothing the first one did not, while ten
 * different library calls in a row each say something — and the first real
 * project analyzed spent ninety of its hundred nodes on the former, leaving the
 * reader's own code off the map entirely.
 *
 * So a run collapses only when its members are interchangeable to a reader:
 * the same library, none of them entered, none of them the reader's own code.
 */
internal object LibraryGrouping {

    /**
     * Two cards are not yet noise, and a group of two costs a concept to save a
     * line. Three is a judgement rather than a measurement (§8.2).
     */
    const val MINIMUM_RUN = 3

    /** What the members of one group have in common, and are named after. */
    data class GroupingKey(val languageId: String, val container: String)

    /**
     * Whether a resolved call may join a group: outside the project, and with no
     * body on the map.
     *
     * A dead end in the reader's own code keeps a card of its own — it is
     * theirs, and the reason it stopped is something they may want to act on.
     * An entered call keeps one too: following it is the point.
     */
    fun keyOf(
        resolution: ResolutionStatus?,
        entered: Boolean,
        languageId: String?,
        container: String?,
    ): GroupingKey? {
        if (entered) return null
        if (resolution != ResolutionStatus.EXTERNAL && resolution != ResolutionStatus.BUILT_IN) {
            return null
        }
        if (languageId == null || container.isNullOrBlank()) return null
        return GroupingKey(languageId, container)
    }

    /**
     * Splits [items] into runs of equal key and hands each run of [MINIMUM_RUN]
     * or more to [group]; everything else is passed through [single], in place
     * and in order.
     *
     * Adjacency is the whole rule: anything that is not groupable ends the run,
     * which is what stops a group from spanning code the reader wanted to see.
     */
    fun <T, R> collapse(
        items: List<T>,
        keyOf: (T) -> GroupingKey?,
        group: (GroupingKey, List<T>) -> R,
        single: (T) -> R,
    ): List<R> {
        val out = mutableListOf<R>()
        var index = 0
        while (index < items.size) {
            val key = keyOf(items[index])
            if (key == null) {
                out += single(items[index])
                index += 1
                continue
            }
            var end = index + 1
            while (end < items.size && keyOf(items[end]) == key) end += 1
            val run = items.subList(index, end)
            if (run.size >= MINIMUM_RUN) {
                out += group(key, run.toList())
            } else {
                run.forEach { out += single(it) }
            }
            index = end
        }
        return out
    }
}
