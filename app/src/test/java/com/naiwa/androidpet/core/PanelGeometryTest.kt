package com.naiwa.androidpet.core

import org.junit.Assert.*
import org.junit.Test

class PanelGeometryTest {
    private fun place(area: ScreenArea, x: Int, y: Int, w: Int = 120, h: Int = 120) =
        PanelGeometry.place(area, Position(x, y), PetSize(w, h), PetSize(288, 520), PetSize(200, 144), 8)

    @Test fun `320 pixel phone keeps panel below pet within safe edges`() {
        assertEquals(PanelPlacement(Position(16, 152), PetSize(288, 464)),
            place(ScreenArea(0, 24, 320, 616), 100, 24))
    }

    @Test fun `landscape uses side region when vertical viewport cannot fit close control`() {
        assertEquals(PanelPlacement(Position(252, 24), PetSize(288, 272)),
            place(ScreenArea(24, 24, 616, 296), 24, 80, 220, 180))
    }

    @Test fun `bottom right pet puts panel above within nonzero insets`() {
        assertEquals(PanelPlacement(Position(76, 92), PetSize(288, 520)),
            place(ScreenArea(12, 32, 364, 740), 244, 620))
    }

    @Test fun `all edge positions stay inside safe area and do not intersect pet`() {
        val area = ScreenArea(16, 30, 336, 670)
        for (p in listOf(Position(16, 30), Position(216, 30), Position(16, 550), Position(216, 550), Position(116, 300))) {
            val panel = checkNotNull(place(area, p.x, p.y))
            val a = panel.position; val s = panel.size
            assertTrue(a.x >= area.left && a.y >= area.top)
            assertTrue(a.x + s.width <= area.right && a.y + s.height <= area.bottom)
            assertTrue(a.x + s.width + 8 <= p.x || a.x >= p.x + 128 ||
                a.y + s.height + 8 <= p.y || a.y >= p.y + 128)
        }
    }

    @Test fun `insufficient space requests activity fallback instead of covering pet`() {
        assertNull(place(ScreenArea(0, 0, 320, 240), 100, 50, 120, 140))
    }

    @Test fun `large font minimum close control height forces safe fallback`() {
        assertNull(PanelGeometry.place(ScreenArea(0, 0, 320, 400), Position(100, 140), PetSize(120, 120),
            PetSize(288, 520), PetSize(260, 240), 8))
    }
}

