package dev.matthes.privatemeetingnotes.recording

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class RecordingPhase { IDLE, STARTING, RECORDING, PAUSED, PAUSED_BY_CALL, STOPPING }

data class RecordingStatus(
    val phase: RecordingPhase = RecordingPhase.IDLE,
    val meetingId: String? = null,
    val elapsedMs: Long = 0,
    /** Input level, 0 to 1 on a 60 dB scale. */
    val level: Float = 0f,
    val device: String? = null,
    val message: String? = null,
) {
    val isActive: Boolean get() = phase != RecordingPhase.IDLE
}

/** The current recording's state, shared by the recording service and the UI. */
class Recorder {
    private val state = MutableStateFlow(RecordingStatus())
    val status: StateFlow<RecordingStatus> = state.asStateFlow()

    internal fun update(change: (RecordingStatus) -> RecordingStatus) = state.update(change)

    fun start(context: Context, meetingId: String) {
        state.value = RecordingStatus(RecordingPhase.STARTING, meetingId)
        context.startForegroundService(RecordingService.intent(context, RecordingService.ACTION_START, meetingId))
    }

    fun pause(context: Context) = context.startService(RecordingService.intent(context, RecordingService.ACTION_PAUSE))

    fun resume(context: Context) = context.startService(RecordingService.intent(context, RecordingService.ACTION_RESUME))

    fun stop(context: Context) = context.startService(RecordingService.intent(context, RecordingService.ACTION_STOP))
}

/** A microphone the user can pick: built-in, USB-C or Bluetooth. */
data class InputDevice(val id: Int, val name: String, val type: Int) {
    val isBluetooth: Boolean get() = type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || type == AudioDeviceInfo.TYPE_BLE_HEADSET

    companion object {
        fun available(context: Context): List<InputDevice> =
            context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_INPUTS)
                .filter { it.type in SUPPORTED }
                .map { InputDevice(it.id, label(it), it.type) }
                .distinctBy { it.id }

        private val SUPPORTED = setOf(
            AudioDeviceInfo.TYPE_BUILTIN_MIC,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
        )

        private fun label(device: AudioDeviceInfo): String = when (device.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Phone microphone"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB: ${device.productName}"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Headset: ${device.productName}"
            else -> "Bluetooth: ${device.productName}"
        }
    }
}

internal fun Intent.meetingId(): String? = getStringExtra(RecordingService.EXTRA_MEETING)
