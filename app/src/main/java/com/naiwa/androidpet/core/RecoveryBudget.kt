package com.naiwa.androidpet.core

/** A lifetime budget, consumed only by confirmed missing-window recovery, never by polling. */
class RecoveryBudget(private val limit: Int = 2) {
    private var attempts = 0
    init { require(limit in 0..5) }
    fun claim(attached: Boolean, permitted: Boolean, wanted: Boolean): Boolean {
        if (attached || !permitted || !wanted || attempts >= limit) return false
        attempts++
        return true
    }
}
