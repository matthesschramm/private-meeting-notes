package dev.matthes.privatemeetingnotes.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.matthes.privatemeetingnotes.AppContainer
import dev.matthes.privatemeetingnotes.data.JobState
import dev.matthes.privatemeetingnotes.data.Meeting
import dev.matthes.privatemeetingnotes.data.MeetingSource
import dev.matthes.privatemeetingnotes.data.MeetingStatus
import dev.matthes.privatemeetingnotes.data.ProcessingJob
import dev.matthes.privatemeetingnotes.diagnostics.Log
import dev.matthes.privatemeetingnotes.importing.AudioImporter
import dev.matthes.privatemeetingnotes.models.ModelId
import dev.matthes.privatemeetingnotes.processing.LiveProgress
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** An import in progress; [meetingId] is the meeting its audio is being written for. */
data class ImportState(val name: String, val progress: Float, val error: String? = null, val meetingId: String? = null)

@OptIn(ExperimentalCoroutinesApi::class)
class MeetingsViewModel(private val c: AppContainer) : ViewModel() {
    val query = MutableStateFlow("")
    val meetings: StateFlow<List<Meeting>> = query
        .flatMapLatest { q -> if (q.isBlank()) c.database.meetings().observeAll() else c.database.meetings().search(q.trim()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val jobs: StateFlow<Map<String, ProcessingJob>> = c.database.jobs().observeAll()
        .map { list -> list.associateBy { it.meetingId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
    val live: StateFlow<LiveProgress?> = c.queue.progress
    val missingModels: StateFlow<Boolean> = c.models.states
        .map { states -> REQUIRED.any { states[it.key]?.usable != true } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val importing = MutableStateFlow<ImportState?>(null)

    fun import(uri: Uri) {
        val importer = AudioImporter(c.context)
        val name = importer.displayName(uri)?.substringBeforeLast('.') ?: "Imported recording"
        importing.value = ImportState(name, 0f)
        c.scope.launch {
            val meeting = c.repository.create(MeetingSource.IMPORTED, name, null, null)
            importing.value = ImportState(name, 0f, meetingId = meeting.id)
            try {
                val samples = importer.import(uri, c.files.recording(meeting.id)) { p -> importing.value = ImportState(name, p, meetingId = meeting.id) }
                c.database.meetings().setDuration(meeting.id, samples * 1000 / 16_000)
                importing.value = null
                c.queue.enqueue(meeting.id)
            } catch (e: Exception) {
                Log.e("import failed", e)
                c.repository.delete(meeting.id)
                importing.value = ImportState(name, 0f, e.message ?: "The file could not be imported")
            }
        }
    }

    fun process(meetingId: String) = viewModelScope.launch { c.queue.enqueue(meetingId) }

    fun retry(meetingId: String) = viewModelScope.launch { c.queue.retry(meetingId) }

    fun dismissImportError() {
        importing.value = null
    }

    companion object {
        val REQUIRED = listOf(ModelId.SILERO_VAD, ModelId.PARAKEET, ModelId.NEMOTRON, ModelId.WESPEAKER, ModelId.GEMMA)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeetingsScreen(model: MeetingsViewModel, onOpen: (String) -> Unit, onRecord: () -> Unit, onSettings: () -> Unit, padding: PaddingValues) {
    val meetings by model.meetings.collectAsState()
    val jobs by model.jobs.collectAsState()
    val live by model.live.collectAsState()
    val missing by model.missingModels.collectAsState()
    val importing by model.importing.collectAsState()
    val query by model.query.collectAsState()
    var searching by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(model::import) }

    Scaffold(
        modifier = Modifier.padding(padding).consumeWindowInsets(padding),
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { model.query.value = it },
                            placeholder = { Text("Search meetings") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text("Private Meeting Notes")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        if (searching) model.query.value = ""
                        searching = !searching
                    }) { Icon(if (searching) Icons.Outlined.Close else Icons.Outlined.Search, "Search") }
                    IconButton(onClick = { picker.launch(arrayOf("audio/*")) }) { Icon(Icons.Outlined.FileUpload, "Import a recording") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onRecord, icon = { Icon(Icons.Filled.Mic, null) }, text = { Text("Record") })
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (missing) {
                item {
                    Card(
                        onClick = onSettings,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Warning, null)
                            Spacer(Modifier.width(12.dp))
                            Text("Some models are not on the phone yet. Open Settings for setup instructions.")
                        }
                    }
                }
            }
            importing?.let { state ->
                item {
                    Card {
                        Column(Modifier.padding(16.dp)) {
                            Text("Importing ${state.name}", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.padding(4.dp))
                            if (state.error == null) LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
            if (meetings.isEmpty()) {
                item {
                    Text(
                        if (query.isBlank()) "No meetings yet. Tap Record to capture one, or import an audio file." else "No meetings match \"$query\".",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 48.dp),
                    )
                }
            }
            items(meetings, key = { it.id }) { meeting ->
                MeetingCard(
                    meeting,
                    jobs[meeting.id],
                    live?.takeIf { it.meetingId == meeting.id },
                    importing = importing?.meetingId == meeting.id,
                    onOpen = { onOpen(meeting.id) },
                    onProcess = { model.process(meeting.id) },
                    onRetry = { model.retry(meeting.id) },
                )
            }
        }
    }

    importing?.error?.let { error ->
        AlertDialog(
            onDismissRequest = model::dismissImportError,
            confirmButton = { TextButton(onClick = model::dismissImportError) { Text("OK") } },
            title = { Text("Import failed") },
            text = { Text(error) },
        )
    }
}

@Composable
private fun MeetingCard(
    meeting: Meeting,
    job: ProcessingJob?,
    live: LiveProgress?,
    importing: Boolean,
    onOpen: () -> Unit,
    onProcess: () -> Unit,
    onRetry: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(Modifier.padding(16.dp)) {
            Text(meeting.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.padding(2.dp))
            val source = if (meeting.source == MeetingSource.IMPORTED) " · imported" else ""
            Text(
                "${Format.date(meeting.startedAt)} · ${Format.duration(meeting.durationMs)}$source",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (importing) Badge("Importing") else StatusLine(meeting, job, live, onProcess, onRetry)
        }
    }
}

@Composable
private fun StatusLine(meeting: Meeting, job: ProcessingJob?, live: LiveProgress?, onProcess: () -> Unit, onRetry: () -> Unit) {
    when (meeting.status) {
        MeetingStatus.READY -> Unit
        MeetingStatus.RECORDING -> Badge("Recording")
        MeetingStatus.QUEUED -> Badge("Waiting to process")
        MeetingStatus.PROCESSING -> {
            val p = live
            Spacer(Modifier.padding(4.dp))
            if (p != null && p.progress < 0) {
                Text("Waiting for the charger", style = MaterialTheme.typography.bodySmall)
            } else {
                Text(p?.stage?.label ?: "Processing", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.padding(2.dp))
                LinearProgressIndicator(progress = { p?.progress ?: 0f }, modifier = Modifier.fillMaxWidth())
            }
        }
        MeetingStatus.FAILED -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Processing failed" + (job?.error?.let { ": $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry) { Text("Retry") }
        }
        MeetingStatus.RECORDED -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (job?.state == JobState.CANCELLED) "Processing cancelled" else "Not processed yet",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onProcess) { Text("Process") }
        }
    }
}

@Composable
private fun Badge(text: String) {
    Spacer(Modifier.padding(2.dp))
    AssistChip(onClick = {}, label = { Text(text) })
}
