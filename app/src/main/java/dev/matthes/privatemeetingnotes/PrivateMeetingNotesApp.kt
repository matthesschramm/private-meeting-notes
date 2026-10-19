package dev.matthes.privatemeetingnotes

import android.app.Application
import android.content.Context
import android.system.Os

class PrivateMeetingNotesApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // ONNX Runtime ships a Microsoft telemetry client. The app has no network permission, so nothing could be
        // sent anyway; this stops the client from starting at all. It must be set before ONNX Runtime loads.
        Os.setenv("ORT_DISABLE_TELEMETRY", "1", true)
        container = AppContainer(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_BACKGROUND) {
            container.llm.trim()
            container.embedder.close()
        }
    }
}

val Context.container: AppContainer get() = (applicationContext as PrivateMeetingNotesApp).container
