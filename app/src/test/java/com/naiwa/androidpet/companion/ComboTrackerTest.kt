package com.naiwa.androidpet.companion

import org.junit.Assert.*
import org.junit.Test

class ComboTrackerTest {
    @Test fun `combo has two thresholds and resets after its time window`() {
        val combo = ComboTracker()
        assertEquals(1,combo.tap(1000).count)
        combo.tap(1200)
        assertEquals(1,combo.tap(1400).level)
        combo.tap(1600); combo.tap(1800)
        assertEquals(2,combo.tap(2000).level)
        assertEquals(1,combo.tap(3000).count)
    }
    @Test fun `duplicate and very rapid taps cannot flood animation requests`() {
        val combo = ComboTracker()
        assertTrue(combo.tap(1000).animate)
        assertFalse(combo.tap(1000).accepted)
        assertFalse(combo.tap(1030).accepted)
        assertFalse(combo.tap(1100).animate)
        assertTrue(combo.tap(1700).animate)
    }
    @Test fun `ordinary drag and vertical movement do not count as a quick horizontal swipe`() {
        assertTrue(SwipeClassifier.isSwipe(180f,20f,180,1f))
        assertFalse(SwipeClassifier.isSwipe(180f,20f,600,1f))
        assertFalse(SwipeClassifier.isSwipe(20f,180f,180,1f))
        assertFalse(SwipeClassifier.isSwipe(15f,2f,100,1f))
    }
}
