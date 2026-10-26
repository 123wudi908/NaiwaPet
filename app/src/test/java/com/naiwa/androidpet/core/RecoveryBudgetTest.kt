package com.naiwa.androidpet.core

import org.junit.Assert.*
import org.junit.Test

class RecoveryBudgetTest {
    @Test fun `attached window permission loss and explicit close never schedule another add`() {
        val budget = RecoveryBudget(2)
        assertFalse(budget.claim(attached = true, permitted = true, wanted = true))
        assertFalse(budget.claim(attached = false, permitted = false, wanted = true))
        assertFalse(budget.claim(attached = false, permitted = true, wanted = false))
        assertTrue(budget.claim(false, true, true))
    }
    @Test fun `continuous failure stops after two attempts and does not replenish itself`() {
        val budget = RecoveryBudget(2)
        repeat(2) { assertTrue(budget.claim(false, true, true)) }
        repeat(20) { assertFalse(budget.claim(false, true, true)) }
    }
}
