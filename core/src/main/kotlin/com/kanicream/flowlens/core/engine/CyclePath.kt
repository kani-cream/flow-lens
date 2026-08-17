package com.kanicream.flowlens.core.engine

/**
 * Immutable callable traversal path used for cycle detection. Detection is
 * path-based, not global symbol deduplication: the same symbol may legitimately
 * appear again on an independent branch (V0.1_SPEC.md section 12).
 */
class CyclePath private constructor(
    private val orderedKeys: List<String>,
    private val keySet: Set<String>,
) {
    fun contains(symbolKey: String): Boolean = symbolKey in keySet

    /** Returns a new path extended with [symbolKey]; this instance is unchanged. */
    fun push(symbolKey: String): CyclePath =
        CyclePath(orderedKeys + symbolKey, keySet + symbolKey)

    val depth: Int get() = orderedKeys.size

    companion object {
        fun root(symbolKey: String): CyclePath =
            CyclePath(listOf(symbolKey), setOf(symbolKey))
    }
}
