package dev.matthes.privatemeetingnotes.diagnostics

/** The app's only log tag. Messages carry timings, counts and states; never transcript text, names or audio. */
object Log {
    const val TAG = "PrivateMeetingNotes"

    fun i(message: String) {
        android.util.Log.i(TAG, message)
    }

    fun w(message: String, error: Throwable? = null) {
        android.util.Log.w(TAG, message + (error?.let { " (${it.javaClass.simpleName})" } ?: ""))
    }

    fun e(message: String, error: Throwable? = null) {
        android.util.Log.e(TAG, message + (error?.let { " (${it.javaClass.simpleName})" } ?: ""))
    }
}

/** Measures a block and logs its duration under [label]. */
inline fun <T> timed(label: String, block: () -> T): T {
    val start = System.nanoTime()
    try {
        return block()
    } finally {
        Log.i("$label took ${(System.nanoTime() - start) / 1_000_000} ms")
    }
}
