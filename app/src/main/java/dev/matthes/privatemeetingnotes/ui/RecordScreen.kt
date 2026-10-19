package dev.matthes.privatemeetingnotes.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import dev.matthes.privatemeetingnotes.AppContainer
import dev.matthes.privatemeetingnotes.data.MeetingSource
import dev.matthes.privatemeetingnotes.data.SettingsValues
import dev.matthes.privatemeetingnotes.recording.InputDevice
import dev.matthes.privatemeetingnotes.recording.RecordingPhase
import dev.matthes.privatemeetingnotes.recording.RecordingStatus
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class RecordViewModel(private val c: AppContainer) : ViewModel() {
    val status: StateFlow<RecordingStatus> = c.recorder.status

    fun devices(): List<InputDevice> = InputDevice.available(c.context)

    val selectedDevice: Int get() = c.settings.current.inputDeviceId

    fun selectDevice(id: Int) = c.settings.update { it.copy(inputDeviceId = id) }

    suspend fun start(context: Context, title: String, headCount: Int?) {
        val device = devices().firstOrNull { it.id == selectedDevice }?.name ?: "Phone microphone"
        val meeting = c.repository.create(MeetingSource.RECORDED, title, headCount, device)
        c.recorder.start(context, meeting.id)
    }

    fun pause(context: Context) = c.recorder.pause(context)
    fun resume(context: Context) = c.recorder.resume(context)
    fun stop(context: Context) = c.recorder.stop(context)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordScreen(model: RecordViewModel, onBack: () -> Unit, onFinished: (String) -> Unit) {
    val context = LocalContext.current
    val status by model.status.collectAsState()
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var headCount by remember { mutableStateOf("") }
    var finishedMeeting by remember { mutableStateOf<String?>(null) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true) {
            scope.launch { model.start(context, title, headCount.toIntOrNull()?.takeIf { it > 0 }) }
        }
    }

    LaunchedEffect(status.phase, status.meetingId) {
        if (status.phase == RecordingPhase.RECORDING) finishedMeeting = status.meetingId
        if (status.phase == RecordingPhase.IDLE) finishedMeeting?.let { onFinished(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (status.isActive) "Recording" else "New recording") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier.fillMaxSize().padding(inner).padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!status.isActive) {
                OutlinedTextField(title, { title = it }, label = { Text("Title (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    headCount,
                    { value -> headCount = value.filter { it.isDigit() }.take(2) },
                    label = { Text("Number of people (optional)") },
                    supportingText = { Text("Helps separate speakers. Above 8, a model for large meetings is used.") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                DevicePicker(model)
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Text(
                        CONSENT_REMINDER,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                status.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(
                    onClick = {
                        val needed = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
                        if (needed.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) {
                            scope.launch { model.start(context, title, headCount.toIntOrNull()?.takeIf { it > 0 }) }
                        } else {
                            permissions.launch(needed.toTypedArray())
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                ) {
                    Icon(Icons.Filled.Mic, null)
                    Spacer(Modifier.size(8.dp))
                    Text("Start recording", fontSize = 18.sp)
                }
            } else {
                ActiveRecording(status, onPause = { model.pause(context) }, onResume = { model.resume(context) }, onStop = { model.stop(context) })
            }
        }
    }
}

@Composable
private fun ActiveRecording(status: RecordingStatus, onPause: () -> Unit, onResume: () -> Unit, onStop: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Spacer(Modifier.height(24.dp))
        Text(Format.duration(status.elapsedMs), fontSize = 64.sp, style = MaterialTheme.typography.displayLarge)
        Text(
            when (status.phase) {
                RecordingPhase.PAUSED -> "Paused"
                RecordingPhase.PAUSED_BY_CALL -> "Paused while the microphone is used for a call"
                RecordingPhase.STOPPING -> "Saving"
                RecordingPhase.STARTING -> "Starting"
                else -> "Recording. You can turn the screen off."
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        LinearProgressIndicator(progress = { if (status.phase == RecordingPhase.RECORDING) status.level else 0f }, modifier = Modifier.fillMaxWidth().height(8.dp))
        status.device?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (status.phase == RecordingPhase.PAUSED) {
                FilledTonalButton(onClick = onResume, modifier = Modifier.height(56.dp)) {
                    Icon(Icons.Filled.PlayArrow, null)
                    Text("Resume")
                }
            } else {
                FilledTonalButton(onClick = onPause, enabled = status.phase == RecordingPhase.RECORDING, modifier = Modifier.height(56.dp)) {
                    Icon(Icons.Filled.Pause, null)
                    Text("Pause")
                }
            }
            Button(onClick = onStop, enabled = status.phase != RecordingPhase.STOPPING, modifier = Modifier.height(56.dp)) {
                Icon(Icons.Filled.Stop, null)
                Text("Stop and process")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DevicePicker(model: RecordViewModel) {
    val devices = remember { model.devices() }
    var expanded by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(model.selectedDevice) }
    val label = devices.firstOrNull { it.id == selected }?.name ?: "Phone microphone (default)"
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Microphone") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Phone microphone (default)") }, onClick = {
                selected = SettingsValues.DEFAULT_DEVICE
                model.selectDevice(selected)
                expanded = false
            })
            devices.filter { it.type != android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC }.forEach { device ->
                DropdownMenuItem(text = { Text(device.name) }, onClick = {
                    selected = device.id
                    model.selectDevice(device.id)
                    expanded = false
                })
            }
        }
    }
}

const val CONSENT_REMINDER =
    "Before recording, ask everyone present. In Victoria, sharing a recording or a report of a private conversation " +
        "needs the consent of everyone in it."
