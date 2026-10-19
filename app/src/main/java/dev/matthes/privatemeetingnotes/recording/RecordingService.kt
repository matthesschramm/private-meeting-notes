package dev.matthes.privatemeetingnotes.recording

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.matthes.privatemeetingnotes.Notifications
import dev.matthes.privatemeetingnotes.R
import dev.matthes.privatemeetingnotes.audio.SAMPLE_RATE
import dev.matthes.privatemeetingnotes.audio.WavWriter
import dev.matthes.privatemeetingnotes.container
import dev.matthes.privatemeetingnotes.data.MeetingStatus
import dev.matthes.privatemeetingnotes.data.SettingsValues
import dev.matthes.privatemeetingnotes.diagnostics.Log
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Records a meeting as 16 kHz mono PCM16 WAV, as a foreground service of type microphone so it continues with the
 * screen off. Pauses by itself while a phone call silences the microphone.
 */
class RecordingService : LifecycleService() {
    private var record: AudioRecord? = null
    private var writer: WavWriter? = null
    private var thread: Thread? = null
    private var meetingId: String? = null
    private var deviceName: String? = null
    private var usedCommunicationDevice = false
    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var silenced = false
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private val wakeLock by lazy {
        getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PrivateMeetingNotes:recording")
    }

    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
            val session = record?.audioSessionId ?: return
            val mine = configs.firstOrNull { it.clientAudioSessionId == session } ?: return
            if (mine.isClientSilenced != silenced) {
                silenced = mine.isClientSilenced
                Log.i(if (silenced) "recording silenced by the system, pausing" else "recording no longer silenced")
                publish()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> intent.meetingId()?.let { start(it) }
            ACTION_PAUSE -> if (running) {
                paused = true
                writer?.sync()
                publish()
            }
            ACTION_RESUME -> if (running) {
                paused = false
                publish()
            }
            ACTION_STOP -> stop()
        }
        if (!running && intent?.action != ACTION_START && intent?.action != ACTION_STOP) stopSelf()
        return START_NOT_STICKY
    }

    private fun start(id: String) {
        Notifications.ensureChannels(this)
        startForeground(Notifications.RECORDING_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        if (running) return
        val settings = container.settings.current
        try {
            check(filesDir.usableSpace > MIN_FREE_TO_START) { "Not enough free storage to record" }
            val created = createRecord(settings)
            val file = container.files.recording(id)
            writer = WavWriter(file)
            record = created
            meetingId = id
            paused = false
            silenced = false
            audioManager.registerAudioRecordingCallback(recordingCallback, Handler(Looper.getMainLooper()))
            created.startRecording()
            check(created.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "The microphone is in use by another app" }
            wakeLock.acquire(MAX_RECORDING_MS)
            running = true
            thread = Thread({ loop(created) }, "recording").apply { start() }
            lifecycleScope.launch { container.database.meetings().setStatus(id, MeetingStatus.RECORDING) }
            Log.i("recording started (${settings.recordingSource})")
            publish()
        } catch (e: Exception) {
            Log.e("recording could not start", e)
            release()
            container.recorder.update { RecordingStatus(message = e.message ?: "Recording could not start") }
            lifecycleScope.launch {
                withContext(NonCancellable) { container.repository.delete(id) }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun createRecord(settings: SettingsValues): AudioRecord {
        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val created = AudioRecord.Builder()
            .setAudioSource(settings.recordingSource.audioSource)
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minimum * 4, SAMPLE_RATE * 2))
            .build()
        check(created.state == AudioRecord.STATE_INITIALIZED) { "The microphone could not be opened" }
        val device = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == settings.inputDeviceId }
        if (device != null) {
            // Bluetooth microphones only carry audio once they are the communication device.
            if (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || device.type == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                usedCommunicationDevice = audioManager.availableCommunicationDevices
                    .firstOrNull { it.productName == device.productName && it.type == device.type }
                    ?.let { audioManager.setCommunicationDevice(it) } == true
            }
            created.preferredDevice = device
            deviceName = device.productName.toString()
        } else {
            deviceName = "Phone microphone"
        }
        return created
    }

    private fun loop(source: AudioRecord) {
        val buffer = ShortArray(SAMPLE_RATE / 10)
        var lastPublish = 0L
        var lastSpaceCheck = System.currentTimeMillis()
        while (running) {
            val read = source.read(buffer, 0, buffer.size)
            if (read < 0) {
                Log.e("microphone read failed: $read")
                container.recorder.update { it.copy(message = "The microphone stopped working") }
                break
            }
            if (read == 0) continue
            if (!paused && !silenced) writer?.write(buffer, read)
            val now = System.currentTimeMillis()
            if (now - lastPublish >= 100) {
                lastPublish = now
                val level = level(buffer, read)
                container.recorder.update { it.copy(elapsedMs = elapsedMs(), level = level) }
            }
            if (now - lastSpaceCheck > 15_000) {
                lastSpaceCheck = now
                if (filesDir.usableSpace < MIN_FREE_TO_CONTINUE) {
                    Log.w("recording stopped: storage almost full")
                    container.recorder.update { it.copy(message = "Recording stopped because storage is almost full") }
                    break
                }
            }
        }
        if (running) Handler(Looper.getMainLooper()).post { stop() }
    }

    private fun stop() {
        val id = meetingId
        if (!running || id == null) {
            stopSelf()
            return
        }
        running = false
        container.recorder.update { it.copy(phase = RecordingPhase.STOPPING) }
        thread?.join(2_000)
        val samples = writer?.samplesWritten ?: 0
        release()
        Log.i("recording stopped after ${samples / SAMPLE_RATE} s")
        lifecycleScope.launch {
            withContext(NonCancellable) {
                val db = container.database.meetings()
                db.setDuration(id, samples * 1000 / SAMPLE_RATE)
                db.setStatus(id, MeetingStatus.RECORDED)
                if (samples >= MIN_SAMPLES_TO_PROCESS) container.queue.enqueue(id)
            }
            container.recorder.update { status -> RecordingStatus(message = status.message) }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun release() {
        runCatching { audioManager.unregisterAudioRecordingCallback(recordingCallback) }
        record?.let { r ->
            runCatching { r.stop() }
            r.release()
        }
        record = null
        writer?.let { runCatching { it.close() } }
        writer = null
        if (usedCommunicationDevice) audioManager.clearCommunicationDevice()
        usedCommunicationDevice = false
        if (wakeLock.isHeld) wakeLock.release()
    }

    override fun onDestroy() {
        if (running) {
            running = false
            thread?.join(2_000)
            release()
        }
        super.onDestroy()
    }

    private fun elapsedMs(): Long = (writer?.samplesWritten ?: 0) * 1000 / SAMPLE_RATE

    private fun publish() {
        val phase = when {
            !running -> RecordingPhase.STARTING
            silenced -> RecordingPhase.PAUSED_BY_CALL
            paused -> RecordingPhase.PAUSED
            else -> RecordingPhase.RECORDING
        }
        container.recorder.update { it.copy(phase = phase, meetingId = meetingId, elapsedMs = elapsedMs(), device = deviceName) }
        Notifications.update(this, Notifications.RECORDING_ID, notification())
    }

    private fun notification(): Notification {
        val text = when {
            silenced -> "Paused while the microphone is used for a call"
            paused -> "Paused"
            else -> "Recording. You can turn the screen off."
        }
        val actions = buildList {
            if (running && paused) add(action("Resume", ACTION_RESUME)) else if (running) add(action("Pause", ACTION_PAUSE))
            if (running) add(action("Stop", ACTION_STOP))
        }
        val since = if (running && !paused && !silenced) System.currentTimeMillis() - elapsedMs() else null
        return Notifications.recording(this, text, since, actions)
    }

    private fun action(label: String, action: String): Notification.Action = Notification.Action.Builder(
        Icon.createWithResource(this, R.drawable.ic_notification),
        label,
        PendingIntent.getService(this, action.hashCode(), intent(this, action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
    ).build()

    companion object {
        const val ACTION_START = "dev.matthes.privatemeetingnotes.record.START"
        const val ACTION_PAUSE = "dev.matthes.privatemeetingnotes.record.PAUSE"
        const val ACTION_RESUME = "dev.matthes.privatemeetingnotes.record.RESUME"
        const val ACTION_STOP = "dev.matthes.privatemeetingnotes.record.STOP"
        const val EXTRA_MEETING = "meeting"
        private const val MIN_FREE_TO_START = 500L * 1024 * 1024
        private const val MIN_FREE_TO_CONTINUE = 150L * 1024 * 1024
        private const val MAX_RECORDING_MS = 12L * 60 * 60 * 1000
        private const val MIN_SAMPLES_TO_PROCESS = 2L * SAMPLE_RATE

        fun intent(context: Context, action: String, meetingId: String? = null): Intent =
            Intent(context, RecordingService::class.java).setAction(action).apply { meetingId?.let { putExtra(EXTRA_MEETING, it) } }

        /** Input level on a 0 to 1 scale covering -60 to 0 dBFS. */
        private fun level(samples: ShortArray, count: Int): Float {
            var sum = 0.0
            for (i in 0 until count) sum += samples[i].toDouble() * samples[i]
            val rms = sqrt(sum / count) / 32768.0
            return ((20 * log10(rms.coerceAtLeast(1e-6)) + 60) / 60).toFloat().coerceIn(0f, 1f)
        }
    }
}
