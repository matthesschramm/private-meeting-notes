package dev.matthes.privatemeetingnotes.processing

import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.PowerManager
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.matthes.privatemeetingnotes.AppContainer
import dev.matthes.privatemeetingnotes.Notifications
import dev.matthes.privatemeetingnotes.container
import dev.matthes.privatemeetingnotes.data.AudioRetention
import dev.matthes.privatemeetingnotes.data.JobState
import dev.matthes.privatemeetingnotes.data.MeetingStatus
import dev.matthes.privatemeetingnotes.data.ProcessingJob
import dev.matthes.privatemeetingnotes.data.Stage
import dev.matthes.privatemeetingnotes.diagnostics.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap

/** Live progress of the job being processed. [progress] is -1 while waiting for a charger. */
data class LiveProgress(val meetingId: String, val stage: Stage, val progress: Float, val stageStartedAt: Long)

/** Queues meetings for processing and starts the service that works through them. */
class ProcessingQueue(private val c: AppContainer) {
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    private val live = MutableStateFlow<LiveProgress?>(null)

    val progress: StateFlow<LiveProgress?> = live.asStateFlow()

    /** Queues a meeting to be processed from [firstStage] on, redoing that stage and every later one. */
    suspend fun enqueue(meetingId: String, firstStage: Stage = Stage.VAD) = queue(meetingId, firstStage, firstStage)

    /** Queues a failed meeting again. It carries on where it stopped, keeping every stage it finished. */
    suspend fun retry(meetingId: String) = queue(meetingId, c.pipeline.firstUnfinished(meetingId), RESUME)

    private suspend fun queue(meetingId: String, stage: Stage, clearFrom: Stage) {
        cancelled -= meetingId
        c.database.jobs().upsert(
            ProcessingJob(
                meetingId = meetingId,
                state = JobState.QUEUED,
                stage = stage,
                progress = 0f,
                firstStage = clearFrom,
                requestedAt = System.currentTimeMillis(),
                startedAt = null,
                finishedAt = null,
                error = null,
                timingsJson = "{}",
            ),
        )
        c.database.meetings().setStatus(meetingId, MeetingStatus.QUEUED)
        start()
    }

    /** Starts the service. Android refuses while the app is in the background; queued jobs then wait for the app. */
    fun start(): Boolean = try {
        c.context.startForegroundService(Intent(c.context, ProcessingService::class.java))
        true
    } catch (e: IllegalStateException) {
        Log.w("processing could not start now", e)
        false
    }

    suspend fun cancel(meetingId: String) {
        cancelled += meetingId
        val job = c.database.jobs().get(meetingId) ?: return
        if (job.state == JobState.QUEUED) {
            c.database.jobs().upsert(job.copy(state = JobState.CANCELLED))
            c.database.meetings().setStatus(meetingId, MeetingStatus.RECORDED)
        }
    }

    fun isCancelled(meetingId: String) = meetingId in cancelled

    internal fun report(meetingId: String, stage: Stage, progress: Float) {
        val current = live.value
        val startedAt = if (current?.meetingId == meetingId && current.stage == stage) current.stageStartedAt else System.currentTimeMillis()
        live.value = LiveProgress(meetingId, stage, progress, startedAt)
    }

    internal fun clear() {
        live.value = null
    }

    companion object {
        /** A job's firstStage once it has started, or when retried: nothing to clear, only stages still to finish. */
        val RESUME = Stage.DONE
    }
}

/** Works through queued meetings one at a time, as a foreground service of type mediaProcessing. */
class ProcessingService : LifecycleService() {
    private var worker: Job? = null
    @Volatile private var timedOut = false
    private val json = Json
    private val wakeLock by lazy {
        getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PrivateMeetingNotes:processing")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Notifications.ensureChannels(this)
        startForeground(
            Notifications.PROCESSING_ID,
            Notifications.processing(this, "Starting", null),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
        )
        if (worker?.isActive != true) worker = lifecycleScope.launch(Dispatchers.Default) { runQueue(container) }
        return START_NOT_STICKY
    }

    @OptIn(FlowPreview::class)
    private suspend fun runQueue(c: AppContainer) {
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
        val updates = lifecycleScope.launch {
            c.queue.progress.filterNotNull().sample(1_000).collect { p ->
                val title = c.database.meetings().get(p.meetingId)?.title ?: ""
                val text = if (p.progress < 0) "$title: waiting for the charger" else "$title: ${p.stage.label}"
                Notifications.update(this@ProcessingService, Notifications.PROCESSING_ID, Notifications.processing(this@ProcessingService, text, p.progress.takeIf { it >= 0 }))
                c.database.jobs().setProgress(p.meetingId, p.stage, p.progress)
            }
        }
        try {
            c.models.refresh()
            c.database.jobs().requeueInterrupted()
            while (!timedOut) {
                val job = c.database.jobs().nextQueued() ?: break
                process(c, job)
            }
        } finally {
            updates.cancel()
            c.queue.clear()
            if (wakeLock.isHeld) wakeLock.release()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun process(c: AppContainer, job: ProcessingJob) {
        val id = job.meetingId
        val started = System.currentTimeMillis()
        // Clear the stages to redo once, then record that from now on this job only resumes. A job interrupted
        // later (process killed, daily limit reached) must keep the checkpoints it has made.
        c.pipeline.restartFrom(id, job.firstStage)
        val running = job.copy(state = JobState.RUNNING, firstStage = ProcessingQueue.RESUME, startedAt = started, error = null)
        c.database.jobs().upsert(running)
        c.database.meetings().setStatus(id, MeetingStatus.PROCESSING)
        Log.i("processing started")
        var current = job.stage
        try {
            val timings = c.pipeline.run(id, { stage, p -> current = stage; c.queue.report(id, stage, p) }, { c.queue.isCancelled(id) || timedOut })
            val timingsJson = json.encodeToString(MapSerializer(String.serializer(), Long.serializer()), timings.mapKeys { it.key.name })
            c.database.jobs().upsert(
                running.copy(state = JobState.DONE, stage = Stage.DONE, progress = 1f, finishedAt = System.currentTimeMillis(), timingsJson = timingsJson),
            )
            c.database.meetings().setStatus(id, MeetingStatus.READY)
            if (c.settings.current.audioRetention == AudioRetention.DELETE_AFTER_PROCESSING) c.repository.deleteAudio(id)
            Log.i("processing finished in ${(System.currentTimeMillis() - started) / 1000} s")
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                if (timedOut) {
                    c.database.jobs().upsert(running.copy(state = JobState.QUEUED, stage = current))
                    c.database.meetings().setStatus(id, MeetingStatus.QUEUED)
                    Log.w("processing paused: daily time limit reached")
                } else {
                    c.database.jobs().upsert(running.copy(state = JobState.CANCELLED, stage = current, finishedAt = System.currentTimeMillis()))
                    c.database.meetings().setStatus(id, MeetingStatus.RECORDED)
                    Log.i("processing cancelled")
                }
            }
        } catch (e: Throwable) {
            Log.e("processing failed in $current", e)
            c.database.jobs().upsert(
                running.copy(state = JobState.FAILED, stage = current, finishedAt = System.currentTimeMillis(), error = e.message ?: e.javaClass.simpleName),
            )
            c.database.meetings().setStatus(id, MeetingStatus.FAILED)
        }
    }

    /** Android allows mediaProcessing services six hours a day. Stop cleanly; the job resumes from its checkpoints. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        timedOut = true
        worker?.cancel()
        stopSelf()
    }

    private companion object {
        const val WAKE_LOCK_TIMEOUT_MS = 6L * 60 * 60 * 1000
    }
}
