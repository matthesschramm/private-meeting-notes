package dev.matthes.privatemeetingnotes.data

import android.content.Context
import android.media.MediaRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class RecordingSource(val audioSource: Int, val label: String) {
    VOICE_RECOGNITION(MediaRecorder.AudioSource.VOICE_RECOGNITION, "Voice recognition (recommended)"),
    MIC(MediaRecorder.AudioSource.MIC, "Standard microphone"),
    UNPROCESSED(MediaRecorder.AudioSource.UNPROCESSED, "Unprocessed"),
}

enum class AudioRetention(val label: String) {
    KEEP("Keep recordings"),
    DELETE_AFTER_PROCESSING("Delete after processing"),
    DELETE_AFTER_DAYS("Delete after a number of days"),
}

enum class LlmBackend(val label: String) {
    CPU("CPU"),
    GPU("GPU"),
}

enum class LlmModel(val label: String) {
    E4B("Gemma 4 E4B"),
    E2B("Gemma 4 E2B"),
}

@Serializable
data class SettingsValues(
    val recordingSource: RecordingSource = RecordingSource.VOICE_RECOGNITION,
    val inputDeviceId: Int = DEFAULT_DEVICE,
    val audioRetention: AudioRetention = AudioRetention.KEEP,
    val retentionDays: Int = 30,
    val processOnlyWhileCharging: Boolean = false,
    val llmBackend: LlmBackend = LlmBackend.GPU,
    /** E4B writes better notes; E2B reads about twice as fast and writes 40% faster. */
    val llmModel: LlmModel = LlmModel.E4B,
    val llmContextTokens: Int = 10_240,
    /** CPU threads for the language model; 0 leaves the choice to LiteRT-LM. */
    val llmCpuThreads: Int = 0,
    val asrThreads: Int = 4,
    /** Learn the voices of speakers the user names, and name them automatically in later meetings. */
    val rememberVoices: Boolean = true,
    /** Names and terms, one per line, as read by [dev.matthes.privatemeetingnotes.asr.Vocabulary]. */
    val vocabulary: String = "",
) {
    companion object {
        const val DEFAULT_DEVICE = -1
    }
}

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val state = MutableStateFlow(load())

    val values: StateFlow<SettingsValues> = state.asStateFlow()
    val current: SettingsValues get() = state.value

    fun update(change: (SettingsValues) -> SettingsValues) {
        state.update(change)
        prefs.edit().putString(KEY, json.encodeToString(SettingsValues.serializer(), state.value)).commit()
    }

    private fun load(): SettingsValues =
        prefs.getString(KEY, null)?.let { runCatching { json.decodeFromString(SettingsValues.serializer(), it) }.getOrNull() }
            ?: SettingsValues()

    private companion object {
        const val KEY = "values"
    }
}
