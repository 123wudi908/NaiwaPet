package com.naiwa.androidpet.runtime

import android.content.Context
import android.util.Log
import java.io.File

/** App-private, bounded event history. Callers supply state/reasons, never user input. */
object PetDiagnostics {
    private const val TAG = "NaiwaLifecycle"
    private const val LIMIT = 196_608
    private var directory: File? = null

    @Synchronized fun initialize(context: Context) {
        if (directory == null) directory = File(context.applicationContext.filesDir, "diagnostics").apply { mkdirs() }
    }

    @Synchronized fun event(name: String, detail: String = "", error: Throwable? = null) {
        val message = "${System.currentTimeMillis()} $name ${detail.take(1024)}"
        if (error != null) Log.e(TAG, message, error) else Log.i(TAG, message)
        val dir = directory ?: return
        try {
            val file = File(dir, "lifecycle.log")
            if (file.length() > LIMIT) {
                val previous = File(dir, "lifecycle.previous.log")
                previous.delete()
                if (!file.renameTo(previous)) file.writeText("")
            }
            file.appendText(message + "\n" + (error?.let { Log.getStackTraceString(it).take(8192) + "\n" } ?: ""))
        } catch (failure: Exception) { Log.w(TAG, "Cannot write private diagnostics", failure) }
    }

    @Synchronized fun read(): String = directory?.let { dir ->
        listOf("lifecycle.previous.log", "lifecycle.log").map { File(dir, it) }
            .filter(File::exists).joinToString("\n") { it.readText().takeLast(LIMIT + 8192) }
    } ?: ""
}
