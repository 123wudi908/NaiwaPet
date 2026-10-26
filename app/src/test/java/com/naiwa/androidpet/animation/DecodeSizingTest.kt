package com.naiwa.androidpet.animation

import org.junit.Assert.*
import org.junit.Test

class DecodeSizingTest {
    @Test fun `decoder never undersamples a frame then enlarges it for display`() {
        val plan = DecodeSizing.plan(640,640,480)
        assertEquals(1,plan.sample)
        assertEquals(480,plan.width)
        assertEquals(480,plan.height)
    }

    @Test fun `small pets use bounded decode memory without distorting wide poses`() {
        val plan = DecodeSizing.plan(4096,2048,320)
        assertEquals(8,plan.sample)
        assertEquals(320,plan.width)
        assertEquals(160,plan.height)
    }

    @Test fun `larger viewports keep native pixels and do not allocate upscaled bitmaps`() {
        val plan = DecodeSizing.plan(256,192,1024)
        assertEquals(1,plan.sample)
        assertEquals(256,plan.width)
        assertEquals(192,plan.height)
    }
}
