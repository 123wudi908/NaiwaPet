package com.naiwa.androidpet.animation

import org.junit.Assert.*
import org.junit.Test

class SpriteSizingTest {
    @Test fun `transparent margins do not change the requested visible character height`() {
        val layout = SpriteLayout(640,640,0,0,640,640)
        val viewport = layout.scaledViewport(120,480)
        assertEquals(160,viewport.width)
        assertEquals(160,viewport.height)
    }

    @Test fun `non square source stays proportional and scaling never crops it`() {
        val layout = SpriteLayout(512,640,0,0,512,640)
        val viewport = layout.scaledViewport(240,480)
        assertEquals(256,viewport.width)
        assertEquals(320,viewport.height)
    }
}
