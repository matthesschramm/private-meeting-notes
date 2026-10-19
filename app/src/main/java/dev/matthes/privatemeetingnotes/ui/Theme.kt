package dev.matthes.privatemeetingnotes.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun PrivateMeetingNotesTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialTheme(colorScheme = colors, content = content)
}

/** Distinct colours for up to twelve speakers, then repeating. */
private val SPEAKER_COLOURS_LIGHT = listOf(
    Color(0xFF1565C0), Color(0xFFC62828), Color(0xFF2E7D32), Color(0xFF6A1B9A),
    Color(0xFFEF6C00), Color(0xFF00838F), Color(0xFFAD1457), Color(0xFF4E342E),
    Color(0xFF827717), Color(0xFF455A64), Color(0xFF283593), Color(0xFF004D40),
)
private val SPEAKER_COLOURS_DARK = listOf(
    Color(0xFF90CAF9), Color(0xFFEF9A9A), Color(0xFFA5D6A7), Color(0xFFCE93D8),
    Color(0xFFFFCC80), Color(0xFF80DEEA), Color(0xFFF48FB1), Color(0xFFBCAAA4),
    Color(0xFFE6EE9C), Color(0xFFB0BEC5), Color(0xFF9FA8DA), Color(0xFF80CBC4),
)

@Composable
fun speakerColour(label: Int): Color {
    val palette = if (isSystemInDarkTheme()) SPEAKER_COLOURS_DARK else SPEAKER_COLOURS_LIGHT
    return palette[label.mod(palette.size)]
}

object Format {
    /** 1:02:03 or 12:34. */
    fun duration(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    fun date(at: Long): String {
        val then = Calendar.getInstance().apply { timeInMillis = at }
        val now = Calendar.getInstance()
        val sameDay = then.get(Calendar.YEAR) == now.get(Calendar.YEAR) && then.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
        val pattern = when {
            sameDay -> "'Today,' HH:mm"
            then.get(Calendar.YEAR) == now.get(Calendar.YEAR) -> "EEE d MMM, HH:mm"
            else -> "d MMM yyyy, HH:mm"
        }
        return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(at))
    }

    fun size(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> "%.2f GB".format(bytes / 1e9)
        bytes >= 1_000_000 -> "%.0f MB".format(bytes / 1e6)
        else -> "%.0f KB".format(bytes / 1e3)
    }
}
