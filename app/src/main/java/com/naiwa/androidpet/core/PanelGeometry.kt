package com.naiwa.androidpet.core

data class PanelPlacement(val position: Position, val size: PetSize)

object PanelGeometry {
    fun place(area: ScreenArea, petPosition: Position, petSize: PetSize,
        preferred: PetSize, minimum: PetSize, gap: Int): PanelPlacement? {
        require(gap >= 0)
        val pet = WindowGeometry.fit(petSize, area)
        val p = WindowGeometry.clamp(petPosition, pet, area)
        // Each candidate is a disjoint strip. Never grow a panel across the pet's rectangle.
        val strips = listOf(
            intArrayOf(area.left, p.y + pet.height + gap, area.right, area.bottom),
            intArrayOf(area.left, area.top, area.right, p.y - gap),
            intArrayOf(p.x + pet.width + gap, area.top, area.right, area.bottom),
            intArrayOf(area.left, area.top, p.x - gap, area.bottom),
        )
        val candidates = strips.mapIndexedNotNull { side, r ->
            val w = minOf(preferred.width, r[2] - r[0])
            val h = minOf(preferred.height, r[3] - r[1])
            if (w < minimum.width || h < minimum.height) return@mapIndexedNotNull null
            val x = when (side) {
                2 -> r[0]
                3 -> r[2] - w
                else -> (p.x + (pet.width - w) / 2).coerceIn(r[0], r[2] - w)
            }
            val y = when (side) {
                0 -> r[1]
                1 -> r[3] - h
                else -> p.y.coerceIn(r[1], r[3] - h)
            }
            PanelPlacement(Position(x, y), PetSize(w, h))
        }
        return candidates.firstOrNull { it.size == preferred }
            ?: candidates.maxByOrNull { it.size.width.toLong() * it.size.height }
    }
}
