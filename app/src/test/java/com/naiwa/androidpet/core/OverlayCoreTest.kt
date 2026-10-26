package com.naiwa.androidpet.core

import org.junit.Assert.*
import org.junit.Test

class OverlayCoreTest {
    private val area = ScreenArea(10, 30, 1010, 1830)

    @Test fun `position clamps against all visible edges`() {
        assertEquals(Position(10, 30), WindowGeometry.clamp(Position(-50, -30), PetSize(120, 160), area))
        assertEquals(Position(890, 1670), WindowGeometry.clamp(Position(5000, 5000), PetSize(120, 160), area))
    }

    @Test fun `oversized window is fitted even on a tiny display`() {
        assertEquals(PetSize(50, 50), WindowGeometry.fit(PetSize(200, 200), ScreenArea(0, 0, 50, 70)))
    }

    @Test fun `permission is required and background start is refused`() {
        assertEquals(StartDecision.REQUEST_OVERLAY, PermissionPolicy.decide(false, true))
        assertEquals(StartDecision.WAIT_FOR_FOREGROUND, PermissionPolicy.decide(true, false))
        assertEquals(StartDecision.START, PermissionPolicy.decide(true, true))
    }

    @Test fun `drag preserves grab offset and ignores taps below slop`() {
        val drag = DragTracker(8f)
        drag.down(100f, 200f, Position(40, 70))
        assertNull(drag.move(104f, 202f))
        assertEquals(Position(60, 100), drag.move(120f, 230f))
        assertTrue(drag.finish())
        drag.down(1f, 1f, Position(0, 0))
        assertFalse(drag.finish())
    }

    @Test fun `restored position and resize cannot leave the screen`() {
        val store = MemoryStore(Position(9999, -2), 120)
        val surface = MemorySurface()
        val session = OverlaySession(surface, store)
        assertTrue(session.start(area, PetSize(120, 120), true))
        assertEquals(Position(890, 30), surface.position)
        session.moveTo(Position(9000, 9000))
        session.resize(PetSize(220, 220), 220)
        assertEquals(Position(790, 1610), surface.position)
        assertEquals(220, store.sizeDp)
        session.close()
        assertEquals(Position(790, 1610), store.position)
    }

    @Test fun `missing permission creates no window and duplicate start adds only one`() {
        val surface = MemorySurface()
        val session = OverlaySession(surface, MemoryStore())
        assertFalse(session.start(area, PetSize(120, 120), false))
        assertEquals(0, surface.openCount)
        assertTrue(session.start(area, PetSize(120, 120), true))
        assertTrue(session.start(area, PetSize(120, 120), true))
        assertEquals(1, surface.openCount)
    }

    @Test fun `close is idempotent and releases the surface once`() {
        val surface = MemorySurface()
        val session = OverlaySession(surface, MemoryStore())
        session.start(area, PetSize(100, 100), true)
        session.close()
        session.close()
        assertEquals(1, surface.closeCount)
        assertFalse(session.isOpen)
    }

    @Test fun `rotation refits and persists position`() {
        val store = MemoryStore(Position(800, 1500))
        val surface = MemorySurface()
        val session = OverlaySession(surface, store)
        session.start(area, PetSize(120, 120), true)
        session.updateArea(ScreenArea(0, 20, 600, 400))
        assertEquals(Position(480, 280), surface.position)
        assertEquals(Position(480, 280), store.position)
    }

    @Test fun `storage failure during close still removes the window`() {
        var failSave = false
        val store = object : OverlayStore {
            override var sizeDp = 120
            override var position: Position?
                get() = null
                set(value) { if (failSave) throw IllegalStateException("storage unavailable") }
        }
        val surface = MemorySurface()
        val session = OverlaySession(surface, store)
        session.start(area, PetSize(120, 120), true)
        failSave = true
        assertThrows(IllegalStateException::class.java) { session.close() }
        assertEquals(1, surface.closeCount)
        assertFalse(session.isOpen)
    }

    @Test fun `failed window creation cleans up partial resources`() {
        var closes = 0
        val surface = object : OverlaySurface {
            override fun open(position: Position, size: PetSize) { throw SecurityException("permission revoked") }
            override fun update(position: Position, size: PetSize) = Unit
            override fun close() { closes++ }
        }
        val session = OverlaySession(surface, MemoryStore())
        assertThrows(SecurityException::class.java) { session.start(area, PetSize(120, 120), true) }
        assertFalse(session.isOpen)
        assertEquals(1, closes)
    }

    @Test fun `initial persistence failure does not leave session marked open`() {
        val store = object : OverlayStore {
            override var sizeDp = 120
            override var position: Position?
                get() = null
                set(value) { throw IllegalStateException("storage unavailable") }
        }
        val surface = MemorySurface()
        val session = OverlaySession(surface, store)
        assertThrows(IllegalStateException::class.java) { session.start(area, PetSize(120, 120), true) }
        assertFalse(session.isOpen)
        assertEquals(1, surface.closeCount)
    }

    private class MemoryStore(override var position: Position? = null, override var sizeDp: Int = 120) : OverlayStore
    private class MemorySurface : OverlaySurface {
        var position = Position(0, 0)
        var openCount = 0
        var closeCount = 0
        override fun open(position: Position, size: PetSize) { openCount++; this.position = position }
        override fun update(position: Position, size: PetSize) { this.position = position }
        override fun close() { closeCount++ }
    }
}
