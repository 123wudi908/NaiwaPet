package com.naiwa.androidpet.animation

import org.junit.Assert.*
import org.junit.Test

class PetDirectionTest {
    @Test fun `screen movement selects all eight literal directions`() {
        val cases = listOf(
            Triple(0.0, 20.0, PetDirection.FRONT), Triple(-20.0, 20.0, PetDirection.FRONT_LEFT),
            Triple(-20.0, 0.0, PetDirection.LEFT), Triple(-20.0, -20.0, PetDirection.BACK_LEFT),
            Triple(0.0, -20.0, PetDirection.BACK), Triple(20.0, -20.0, PetDirection.BACK_RIGHT),
            Triple(20.0, 0.0, PetDirection.RIGHT), Triple(20.0, 20.0, PetDirection.FRONT_RIGHT))
        for ((dx, dy, expected) in cases) assertEquals(expected, DirectionState().moveBy(dx, dy))
    }

    @Test fun `slow automatic one pixel steps accumulate into a turn`() {
        val state = DirectionState()
        assertEquals(PetDirection.FRONT, state.moveBy(-1.0, 0.0))
        assertEquals(PetDirection.LEFT, state.moveBy(-1.0, 0.0))
        assertEquals(PetDirection.LEFT, state.moveBy(0.0, 0.0))
    }

    @Test fun `opposite subthreshold jitter does not turn the pet`() {
        val state = DirectionState()
        repeat(50) { state.moveBy(1.0, 0.0); state.moveBy(-1.0, 0.0) }
        assertEquals(PetDirection.FRONT, state.direction)
    }

    @Test fun `adjacent sector boundary jitter keeps previous direction until a clear turn`() {
        val state = DirectionState()
        assertEquals(PetDirection.FRONT, state.moveBy(-4.0, 10.0))
        assertEquals(PetDirection.FRONT, state.moveBy(-5.0, 10.0))
        assertEquals(PetDirection.FRONT_LEFT, state.moveBy(-8.0, 10.0))
        assertEquals(PetDirection.FRONT_LEFT, state.moveBy(-4.0, 10.0))
        assertEquals(PetDirection.FRONT, state.moveBy(0.0, 10.0))
    }

    @Test fun `invalid vectors cannot corrupt the direction or accumulation`() {
        val state = DirectionState()
        state.moveBy(20.0, 0.0)
        state.moveBy(Double.NaN, 10.0); state.moveBy(0.0, Double.POSITIVE_INFINITY)
        assertEquals(PetDirection.RIGHT, state.moveBy(0.0, 0.0))
        assertEquals(PetDirection.BACK, state.moveBy(0.0, -10.0))
    }

    @Test fun `new gesture clears small motion without resetting facing`() {
        val state = DirectionState()
        state.moveBy(-10.0, 0.0); state.moveBy(0.0, 1.0); state.resetMotion()
        assertEquals(PetDirection.LEFT, state.moveBy(0.0, 1.0))
        assertEquals(PetDirection.FRONT, state.moveBy(0.0, 1.0))
    }
}
