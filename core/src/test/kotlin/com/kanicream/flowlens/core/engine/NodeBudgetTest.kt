package com.kanicream.flowlens.core.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NodeBudgetTest {

    @Test
    fun `ordinary nodes may use all but the reserved final slot`() {
        val budget = NodeBudget(5)
        repeat(4) { assertTrue(budget.tryClaimOrdinary()) }
        assertFalse(budget.tryClaimOrdinary())
        assertEquals(4, budget.used)
    }

    @Test
    fun `limit marker consumes the reserved slot exactly once`() {
        val budget = NodeBudget(3)
        assertTrue(budget.tryClaimOrdinary())
        assertTrue(budget.tryClaimOrdinary())
        assertTrue(budget.tryClaimLimitSlot())
        assertEquals(3, budget.used)
        assertFalse(budget.tryClaimLimitSlot())
        assertFalse(budget.tryClaimOrdinary())
    }

    @Test
    fun `total nodes never exceed the configured budget`() {
        val budget = NodeBudget(10)
        var total = 0
        while (budget.tryClaimOrdinary()) total += 1
        if (budget.tryClaimLimitSlot()) total += 1
        assertEquals(10, total)
        assertEquals(10, budget.used)
    }

    @Test
    fun `limit slot is available even when no ordinary node was claimed`() {
        val budget = NodeBudget(2)
        assertTrue(budget.tryClaimLimitSlot())
        assertEquals(1, budget.used)
    }
}
