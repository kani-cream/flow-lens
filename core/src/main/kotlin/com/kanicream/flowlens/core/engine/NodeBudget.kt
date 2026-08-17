package com.kanicream.flowlens.core.engine

/**
 * Semantic-node budget accounting. The final slot is reserved so a LIMIT marker can
 * always be placed; the result never silently truncates (V0.1_SPEC.md section 10).
 *
 * Not thread-safe; owned by a single analysis run.
 */
class NodeBudget(private val maxNodes: Int) {
    init {
        require(maxNodes >= 2) { "maxNodes must be >= 2" }
    }

    var used: Int = 0
        private set

    private var limitSlotUsed = false

    /** True when no ordinary semantic node may be added anymore. */
    val isExhausted: Boolean get() = used >= maxNodes - 1

    /** Claims one ordinary semantic slot. Returns false when only the reserved slot remains. */
    fun tryClaimOrdinary(): Boolean {
        if (isExhausted) return false
        used += 1
        return true
    }

    /** Claims the reserved final slot for a LIMIT marker. Only one may ever exist. */
    fun tryClaimLimitSlot(): Boolean {
        if (limitSlotUsed || used >= maxNodes) return false
        limitSlotUsed = true
        used += 1
        return true
    }
}
