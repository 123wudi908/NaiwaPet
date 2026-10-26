package com.naiwa.androidpet.core

import kotlin.math.min

data class Position(val x: Int, val y: Int)
data class PetSize(val width: Int, val height: Int) {
    init { require(width > 0 && height > 0) }
}
data class ScreenArea(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    init { require(right > left && bottom > top) }
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

object WindowGeometry {
    fun fit(size: PetSize, area: ScreenArea): PetSize {
        val scale = min(1.0, min(area.width.toDouble() / size.width, area.height.toDouble() / size.height))
        return PetSize((size.width * scale).toInt().coerceAtLeast(1), (size.height * scale).toInt().coerceAtLeast(1))
    }

    fun clamp(position: Position, size: PetSize, area: ScreenArea): Position {
        val fitted = fit(size, area)
        return Position(position.x.coerceIn(area.left, area.right - fitted.width),
            position.y.coerceIn(area.top, area.bottom - fitted.height))
    }
}
