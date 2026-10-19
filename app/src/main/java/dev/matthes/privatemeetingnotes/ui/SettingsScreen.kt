package dev.matthes.privatemeetingnotes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.matthes.privatemeetingnotes.AppContainer
import dev.matthes.privatemeetingnotes.BuildConfig
import dev.matthes.privatemeetingnotes.data.AudioRetention
import dev.matthes.privatemeetingnotes.data.JobState
import dev.matthes.privatemeetingnotes.data.LlmBackend
import dev.matthes.privatemeetingnotes.data.LlmModel
import dev.matthes.privatemeetingnotes.data.ProcessingJob
import dev.matthes.privatemeetingnotes.data.RecordingSource
import dev.matthes.privatemeetingnotes.data.SettingsValues
import dev.matthes.privatemeetingnotes.data.Voiceprint
import dev.matthes.privatemeetingnotes.models.FileState
import dev.matthes.privatemeetingnotes.models.ModelId
import dev.matthes.privatemeetingnotes.models.ModelState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    val settings: StateFlow<SettingsValues> = c.settings.values
    val models: StateFlow<Map<String, ModelState>> = c.models.states
    val lastJob: StateFlow<ProcessingJob?> = c.database.jobs().observeAll()
        .map { jobs -> jobs.filter { it.state == JobState.DONE }.maxByOrNull { it.finishedAt ?: 0 } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    var verifying by mutableStateOf(false)
        private set

    fun update(change: (SettingsValues) -> SettingsValues) = c.settings.update(change)

    fun verify() = viewModelScope.launch {
        verifying = true
        c.models.verify()
        verifying = false
    }

    fun refresh() = viewModelScope.launch { c.models.refresh() }

    /** Remembered voices with the number of meetings each has named a speaker in. */
    val voices: StateFlow<List<Pair<Voiceprint, Int>>> = combine(
        c.database.voiceprints().observeAll(),
        c.database.transcripts().observeVoiceprintUse(),
    ) { prints, use ->
        val meetings = use.associate { it.id to it.meetings }
        prints.map { it to (meetings[it.id] ?: 0) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Renames a voice, and the speakers it named in every meeting along with their notes. */
    fun renameVoice(print: Voiceprint, name: String) = viewModelScope.launch {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed == print.name) return@launch
        c.database.voiceprints().upsert(print.copy(name = trimmed, updatedAt = System.currentTimeMillis()))
        for (speaker in c.database.transcripts().speakersWithVoiceprint(print.id)) {
            c.repository.renameSpeaker(speaker.meetingId, speaker.label, trimmed, print.id)
            c.indexer.scheduleMeeting(speaker.meetingId)
        }
    }

    /** Forgets a voice. Past meetings keep the names; new meetings won't be named from it. */
    fun forgetVoice(print: Voiceprint) = viewModelScope.launch {
        c.database.transcripts().unlinkVoiceprint(print.id)
        c.database.voiceprints().delete(print.id)
    }

    fun deleteAllData() = viewModelScope.launch(Dispatchers.IO) {
        for (meeting in c.database.meetings().all()) {
            c.queue.cancel(meeting.id)
            c.repository.delete(meeting.id)
        }
        c.database.knowledge().documents().forEach { c.database.knowledge().deleteDocument(it.id) }
        c.database.voiceprints().all().forEach { c.database.voiceprints().delete(it.id) }
        c.database.clearAllTables()
        c.files.recordings.listFiles()?.forEach { it.delete() }
        c.files.jobs.listFiles()?.forEach { it.deleteRecursively() }
        c.files.documents.listFiles()?.forEach { it.deleteRecursively() }
    }

    fun timings(job: ProcessingJob): Map<String, Long> =
        runCatching { Json.decodeFromString(MapSerializer(String.serializer(), Long.serializer()), job.timingsJson) }.getOrDefault(emptyMap())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(model: SettingsViewModel, padding: PaddingValues, extraSections: @Composable () -> Unit = {}) {
    val settings by model.settings.collectAsState()
    val models by model.models.collectAsState()
    val lastJob by model.lastJob.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(modifier = Modifier.padding(padding).consumeWindowInsets(padding), topBar = { TopAppBar(title = { Text("Settings") }) }) { inner ->
        LazyColumn(Modifier.fillMaxSize().padding(inner), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { SectionTitle("Models on this phone") }
            item {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        models.values.forEach { state -> ModelRow(state) }
                        val missing = models.values.any { !it.usable && !it.model.optional }
                        if (missing) {
                            Text("To add missing models, connect the phone to the Mac and run:", style = MaterialTheme.typography.bodySmall)
                            Text("python3 scripts/fetch-models.py\n./scripts/push-models.sh", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = model::refresh) { Text("Check again") }
                            OutlinedButton(onClick = model::verify, enabled = !model.verifying) { Text(if (model.verifying) "Verifying" else "Verify checksums") }
                        }
                    }
                }
            }
            item { SectionTitle("Recording") }
            item {
                Card {
                    Column(Modifier.padding(vertical = 8.dp)) {
                        RecordingSource.entries.forEach { source ->
                            Choice(source.label, settings.recordingSource == source) { model.update { it.copy(recordingSource = source) } }
                        }
                    }
                }
            }
            item { SectionTitle("Processing") }
            item {
                Card {
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Toggle("Process only while charging", "Processing waits until the phone is on a charger.", settings.processOnlyWhileCharging) { on ->
                            model.update { it.copy(processOnlyWhileCharging = on) }
                        }
                        HorizontalDivider()
                        Text("Language model", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 0.dp))
                        val e4b = settings.llmModel == LlmModel.E4B
                        Choice("Gemma 4 E4B on the GPU (recommended)", e4b && settings.llmBackend != LlmBackend.CPU) {
                            model.update { it.copy(llmModel = LlmModel.E4B, llmBackend = LlmBackend.GPU) }
                        }
                        Choice("Gemma 4 E4B on the CPU (reads transcripts about 7 times slower)", e4b && settings.llmBackend == LlmBackend.CPU) {
                            model.update { it.copy(llmModel = LlmModel.E4B, llmBackend = LlmBackend.CPU) }
                        }
                        if (models[ModelId.GEMMA_E2B.key]?.usable == true) {
                            Choice("Gemma 4 E2B on the GPU (faster, less thorough notes)", !e4b) {
                                model.update { it.copy(llmModel = LlmModel.E2B, llmBackend = LlmBackend.GPU) }
                            }
                            Text(
                                "Questions in Ask always use Gemma 4 E2B: it answers sooner and needs far less memory.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }
            item { SectionTitle("Voices") }
            item { VoicesCard(model, settings) }
            item { SectionTitle("Names and terms") }
            item { VocabularyCard(settings) { text -> model.update { it.copy(vocabulary = text) } } }
            item { SectionTitle("Audio after processing") }
            item {
                Card {
                    Column(Modifier.padding(vertical = 8.dp)) {
                        AudioRetention.entries.forEach { retention ->
                            Choice(
                                if (retention == AudioRetention.DELETE_AFTER_DAYS) "Delete after ${settings.retentionDays} days" else retention.label,
                                settings.audioRetention == retention,
                            ) { model.update { it.copy(audioRetention = retention) } }
                        }
                        Text(
                            "Transcripts and notes are always kept. Without the audio you can't play it back or re-split speakers.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            }
            item { extraSections() }
            item { SectionTitle("Diagnostics") }
            item {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
                        val job = lastJob
                        if (job != null) {
                            Text("Last processing run (timings only):", style = MaterialTheme.typography.bodySmall)
                            model.timings(job).forEach { (stage, ms) ->
                                Text("  $stage: ${"%.1f".format(ms / 1000.0)} s", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            }
                        }
                        Text("The app has no network permission. Logs hold timings and counts only.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item { SectionTitle("Data") }
            item {
                OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete all meetings, documents and voiceprints") }
            }
            item { SectionTitle("About") }
            item {
                Text(ATTRIBUTIONS, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(32.dp))
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            "Delete everything?",
            "Deletes every meeting, recording, transcript, note, document and voiceprint from the phone. Models stay.",
            "Delete everything",
            { confirmDelete = false },
        ) { model.deleteAllData() }
    }
}

@Composable
private fun ModelRow(state: ModelState) {
    val files = state.files.values
    val absent = files.any { it == FileState.MISSING }
    val (icon, label) = when {
        files.isEmpty() -> Icons.Outlined.HourglassEmpty to "Checking"
        files.any { it == FileState.CORRUPT } -> Icons.Outlined.ErrorOutline to "Damaged: push it again"
        state.model.optional && absent -> Icons.Outlined.HourglassEmpty to "Optional, not on the phone"
        absent || files.any { it == FileState.WRONG_SIZE } -> Icons.Outlined.ErrorOutline to "Missing"
        state.verified -> Icons.Filled.CheckCircle to "Ready, checksum verified"
        else -> Icons.Filled.CheckCircle to "Ready"
    }
    val tint = when {
        state.usable -> MaterialTheme.colorScheme.primary
        state.model.optional && absent -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.error
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(state.model.purpose, style = MaterialTheme.typography.bodyMedium)
            Text("${state.model.id} · ${Format.size(state.sizeBytes)} · $label", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun VoicesCard(model: SettingsViewModel, settings: SettingsValues) {
    val voices by model.voices.collectAsState()
    var editing by remember { mutableStateOf<Voiceprint?>(null) }
    var forgetting by remember { mutableStateOf<Voiceprint?>(null) }
    Card {
        Column(Modifier.padding(vertical = 8.dp)) {
            Toggle(
                "Remember voices",
                "When you name a speaker, the app keeps a voiceprint, on this phone only, and names that voice in later meetings.",
                settings.rememberVoices,
            ) { on -> model.update { it.copy(rememberVoices = on) } }
            if (voices.isEmpty()) {
                Text(
                    "No voices yet. Name a speaker in a meeting, and tick \"This is me\" for your own voice.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            voices.forEach { (print, meetings) ->
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().clickable { editing = print }.padding(16.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(print.name + if (print.isSelf) " (you)" else "", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Learned from ${print.sampleCount} ${if (print.sampleCount == 1) "meeting" else "meetings"} · " +
                                "named in $meetings · updated ${Format.date(print.updatedAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { forgetting = print }) { Icon(Icons.Outlined.Delete, "Forget ${print.name}'s voice") }
                }
            }
        }
    }
    editing?.let { print ->
        TextDialog("Rename voice", print.name, label = "Name", supporting = "Also renames this person in every meeting.", onDismiss = { editing = null }) {
            model.renameVoice(print, it)
        }
    }
    forgetting?.let { print ->
        ConfirmDialog(
            "Forget ${print.name}'s voice?",
            "Past meetings keep the name, but new meetings won't be named from this voice.",
            "Forget",
            { forgetting = null },
        ) { model.forgetVoice(print) }
    }
}

@Composable
private fun VocabularyCard(settings: SettingsValues, onSave: (String) -> Unit) {
    var text by remember(settings.vocabulary) { mutableStateOf(settings.vocabulary) }
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "One per line. Notes spell these as written here. To fix a word the transcript keeps getting wrong, " +
                    "add it after a colon, for example \"Niamh: Nive, Neve\". Applies to meetings processed or " +
                    "re-split from now on.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth(), minLines = 3, placeholder = { Text("Thandi\nNiamh: Nive, Neve") })
            OutlinedButton(onClick = { onSave(text) }, enabled = text != settings.vocabulary) { Text("Save") }
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}

@Composable
fun Choice(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun Toggle(label: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(16.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private val ATTRIBUTIONS = """
    Private Meeting Notes runs entirely on this phone and has no network access.

    Speech to text: NVIDIA Parakeet TDT 0.6B v2 (CC-BY-4.0), through sherpa-onnx (Apache-2.0).
    Who spoke when: NVIDIA Nemotron 3 Diarization (OpenMDW-1.1), ONNX conversion by Neal Caren, run with ONNX Runtime (MIT).
    Large meetings: pyannote segmentation 3.0 (MIT) and WeSpeaker ResNet34 (Apache-2.0 code).
    Voice activity: Silero VAD (MIT).
    Notes and questions: Google Gemma 4 E4B and EmbeddingGemma 2 (Apache-2.0), through LiteRT-LM (Apache-2.0).
""".trimIndent()
