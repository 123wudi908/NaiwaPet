package com.naiwa.androidpet.runtime

/** Process-local truth: a killed process never restores a stale 'running' preference. */
object PetRuntime {
    private var gameResult: String? = null
    fun completedGame(message: String) { gameResult=message }
    fun takeGameResult(): String? = gameResult.also { gameResult=null }
    private val screens = mutableSetOf<String>()
    val appVisible: Boolean get() = screens.isNotEmpty()
    fun screenVisible(screen: String, visible: Boolean) {
        val changed = if (visible) screens.add(screen) else screens.remove(screen)
        if (changed) update()
    }
    var running: Boolean = false
        private set
    var diagnostic: String? = null
        private set
    var materialInfo: String? = null
        private set
    private val listeners = linkedSetOf<() -> Unit>()

    fun update(running: Boolean = this.running, diagnostic: String? = this.diagnostic, materialInfo: String? = this.materialInfo) {
        this.running = running
        this.diagnostic = diagnostic
        this.materialInfo = materialInfo
        listeners.toList().forEach { it() }
    }

    fun subscribe(listener: () -> Unit) { listeners.add(listener) }
    fun unsubscribe(listener: () -> Unit) { listeners.remove(listener) }
}
