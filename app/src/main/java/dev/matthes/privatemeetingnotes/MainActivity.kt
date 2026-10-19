package dev.matthes.privatemeetingnotes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import dev.matthes.privatemeetingnotes.ui.App
import dev.matthes.privatemeetingnotes.ui.PrivateMeetingNotesTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { PrivateMeetingNotesTheme { App(container) } }
    }

    override fun onStart() {
        super.onStart()
        // A job left behind when the process was killed resumes once the app is visible again, which is when
        // Android allows the processing service to start.
        lifecycleScope.launch { if (container.database.jobs().pending().isNotEmpty()) container.queue.start() }
    }
}
