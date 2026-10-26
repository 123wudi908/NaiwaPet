package com.naiwa.androidpet.core

interface OverlayStore {
    var position: Position?
    var sizeDp: Int
}

interface OverlaySurface {
    fun open(position: Position, size: PetSize)
    fun update(position: Position, size: PetSize)
    fun close()
}

/** Window policy, independent of Android and of the artwork renderer. Main-thread confined. */
class OverlaySession(private val surface: OverlaySurface, private val store: OverlayStore) {
    var isOpen = false
        private set
    var position = Position(0, 0)
        private set
    private var area = ScreenArea(0, 0, 1, 1)
    private var requestedSize = PetSize(1, 1)
    private var size = requestedSize

    fun start(area: ScreenArea, requestedSize: PetSize, permission: Boolean): Boolean {
        if (!permission) { close(); return false }
        if (isOpen) return true
        this.area = area
        this.requestedSize = requestedSize
        size = WindowGeometry.fit(requestedSize, area)
        position = WindowGeometry.clamp(store.position ?: Position(area.left, area.top + area.height / 3), size, area)
        try {
            surface.open(position, size)
            isOpen = true
            savePosition()
        } catch (error: RuntimeException) {
            isOpen = false
            try { surface.close() } catch (cleanup: RuntimeException) { error.addSuppressed(cleanup) }
            throw error
        }
        return true
    }

    fun moveTo(target: Position) {
        if (!isOpen) return
        position = WindowGeometry.clamp(target, size, area)
        surface.update(position, size)
    }

    fun savePosition() { if (isOpen) store.position = position }

    fun resize(requestedSize: PetSize, sizeDp: Int) {
        if (!isOpen) return
        this.requestedSize = requestedSize
        store.sizeDp = sizeDp
        updateArea(area)
    }

    fun updateArea(area: ScreenArea) {
        this.area = area
        if (!isOpen) return
        size = WindowGeometry.fit(requestedSize, area)
        moveTo(position)
        savePosition()
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        var failure: RuntimeException? = null
        try { store.position = position } catch (error: RuntimeException) { failure = error }
        try { surface.close() } catch (error: RuntimeException) {
            if (failure == null) failure = error else failure.addSuppressed(error)
        }
        failure?.let { throw it }
    }
}
