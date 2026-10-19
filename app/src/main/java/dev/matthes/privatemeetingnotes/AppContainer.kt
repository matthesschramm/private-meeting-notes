package dev.matthes.privatemeetingnotes

import android.content.Context
import dev.matthes.privatemeetingnotes.audio.SAMPLE_RATE
import dev.matthes.privatemeetingnotes.audio.WavWriter
import dev.matthes.privatemeetingnotes.data.AppDatabase
import dev.matthes.privatemeetingnotes.data.AppFiles
import dev.matthes.privatemeetingnotes.data.AudioRetention
import dev.matthes.privatemeetingnotes.data.MeetingRepository
import dev.matthes.privatemeetingnotes.data.MeetingStatus
import dev.matthes.privatemeetingnotes.data.Settings
import dev.matthes.privatemeetingnotes.diagnostics.Log
import dev.matthes.privatemeetingnotes.llm.LlmHost
import dev.matthes.privatemeetingnotes.llm.Summarizer
import dev.matthes.privatemeetingnotes.models.ModelStore
import dev.matthes.privatemeetingnotes.processing.Pipeline
import dev.matthes.privatemeetingnotes.processing.ProcessingQueue
import dev.matthes.privatemeetingnotes.rag.Answerer
import dev.matthes.privatemeetingnotes.rag.Embedder
import dev.matthes.privatemeetingnotes.rag.Indexer
import dev.matthes.privatemeetingnotes.rag.Retriever
import dev.matthes.privatemeetingnotes.recording.Recorder
import dev.matthes.privatemeetingnotes.speakers.Voiceprints
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** The app's long-lived objects, created once in [PrivateMeetingNotesApp]. */
class AppContainer(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val files = AppFiles(context)
    val settings = Settings(context)
    val database = AppDatabase.build(context)
    val models = ModelStore(context, files)
    val repository = MeetingRepository(database, files)
    val voiceprints = Voiceprints(database, settings)
    val llm = LlmHost(context, models, settings, scope)
    val summarizer = Summarizer(llm)
    val embedder = Embedder(context, models)
    val retriever = Retriever(database)
    val indexer = Indexer(this)
    val answerer = Answerer(this)
    val pipeline = Pipeline(this)
    val queue = ProcessingQueue(this)
    val recorder = Recorder()

    init {
        // Folders in app-specific external storage must be created by the app: ones made over adb belong to the
        // shell user, and the app can't read them.
        models.ensureDirectories()
        if (BuildConfig.DEBUG) files.bench.mkdirs()
        scope.launch {
            models.refresh()
            recoverInterruptedRecordings()
            applyAudioRetention()
            runCatching { indexer.upgradeIfNeeded() }.onFailure { Log.w("index upgrade failed", it) }
            models.verify()
        }
    }

    /** A recording cut off by a crash keeps everything up to its last sync; fix its header and keep it. */
    private suspend fun recoverInterruptedRecordings() {
        if (recorder.status.value.isActive) return
        for (meeting in database.meetings().withStatus(MeetingStatus.RECORDING)) {
            val file = files.recording(meeting.id)
            val samples = if (file.exists()) WavWriter.repair(file) else 0
            database.meetings().setDuration(meeting.id, samples * 1000 / SAMPLE_RATE)
            database.meetings().setStatus(meeting.id, MeetingStatus.RECORDED)
            Log.w("recovered an interrupted recording of ${samples / SAMPLE_RATE} s")
        }
    }

    private suspend fun applyAudioRetention() {
        val values = settings.current
        if (values.audioRetention != AudioRetention.DELETE_AFTER_DAYS) return
        val cutoff = System.currentTimeMillis() - values.retentionDays * 86_400_000L
        for (meeting in database.meetings().all()) {
            if (meeting.status == MeetingStatus.READY && meeting.audioFile != null && meeting.startedAt < cutoff) {
                repository.deleteAudio(meeting.id)
            }
        }
    }
}
