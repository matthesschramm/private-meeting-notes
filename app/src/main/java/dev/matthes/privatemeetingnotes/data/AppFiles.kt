package dev.matthes.privatemeetingnotes.data

import android.content.Context
import java.io.File

/** Where the app keeps its files. Everything except models lives in internal storage. */
class AppFiles(context: Context) {
    val recordings = dir(context.filesDir, "recordings")
    val jobs = dir(context.filesDir, "jobs")
    val documents = dir(context.filesDir, "documents")
    val exports = dir(context.cacheDir, "exports")

    /** Models are pushed over USB, so they live in app-specific external storage that adb can write to. */
    val models: File = File(context.getExternalFilesDir(null), "models")
    val bench: File = File(context.getExternalFilesDir(null), "bench")

    fun recording(meetingId: String) = File(recordings, "$meetingId.wav")

    fun jobDir(meetingId: String) = dir(jobs, meetingId)

    fun model(path: String) = File(models, path)

    private fun dir(parent: File, name: String) = File(parent, name).apply { mkdirs() }
}
