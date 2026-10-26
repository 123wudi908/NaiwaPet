package com.naiwa.androidpet.core

enum class StartDecision { REQUEST_OVERLAY, WAIT_FOR_FOREGROUND, START }

object PermissionPolicy {
    fun decide(hasOverlay: Boolean, isForeground: Boolean): StartDecision = when {
        !isForeground -> StartDecision.WAIT_FOR_FOREGROUND
        !hasOverlay -> StartDecision.REQUEST_OVERLAY
        else -> StartDecision.START
    }
}
