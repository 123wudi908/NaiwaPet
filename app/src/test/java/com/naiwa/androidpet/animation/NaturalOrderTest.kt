package com.naiwa.androidpet.animation

import org.junit.Assert.assertEquals
import org.junit.Test

class NaturalOrderTest {
    @Test fun `numeric frame names are sorted numerically`() {
        assertEquals(listOf("frame1.png", "frame2.png", "frame10.png"),
            listOf("frame10.png", "frame2.png", "frame1.png").sortedWith(NaturalOrder))
    }

    @Test fun `long numbers do not overflow`() {
        assertEquals(listOf("f9.png", "f999999999999999999999.png", "f1000000000000000000000.png"),
            listOf("f1000000000000000000000.png", "f9.png", "f999999999999999999999.png").sortedWith(NaturalOrder))
    }

    @Test fun `leading zeros have a deterministic tie break`() {
        assertEquals(listOf("f1.png", "f01.png", "f001.png", "f2.png"),
            listOf("f001.png", "f2.png", "f01.png", "f1.png").sortedWith(NaturalOrder))
    }
}
