package dev.matthes.privatemeetingnotes.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.matthes.privatemeetingnotes.data.ActionItem
import dev.matthes.privatemeetingnotes.data.Meeting
import dev.matthes.privatemeetingnotes.data.MeetingRepository
import dev.matthes.privatemeetingnotes.data.MeetingStatus
import dev.matthes.privatemeetingnotes.data.ProcessingJob
import dev.matthes.privatemeetingnotes.data.Speaker
import dev.matthes.privatemeetingnotes.data.Stage
import dev.matthes.privatemeetingnotes.data.Summary
import dev.matthes.privatemeetingnotes.data.Utterance
import dev.matthes.privatemeetingnotes.export.MarkdownExport
import dev.matthes.privatemeetingnotes.llm.Summarizer
import dev.matthes.privatemeetingnotes.processing.LiveProgress
import kotlinx.coroutines.delay
import kotlin.math.ceil

private val TABS = listOf("Summary", "Transcript", "Actions")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeetingScreen(
    model: MeetingViewModel,
    onBack: () -> Unit,
    openAtMs: Long? = null,
    extraTabs: List<Pair<String, @Composable () -> Unit>> = emptyList(),
) {
    val meeting by model.meeting.collectAsState()
    val job by model.job.collectAsState()
    val live by model.live.collectAsState()
    val utterances by model.utterances.collectAsState()
    val speakers by model.speakers.collectAsState()
    val summary by model.summary.collectAsState()
    val actions by model.actions.collectAsState()
    val context = LocalContext.current
    var tab by remember { mutableIntStateOf(if (openAtMs != null) 1 else 0) }
    var menu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var seekTo by remember { mutableStateOf(openAtMs) }
    val current = meeting ?: return
    val tabs = TABS + extraTabs.map { it.first }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(current.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable { dialog = "title" })
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { model.share(context, includeTranscript = false) }) { Icon(Icons.Outlined.Share, "Share notes") }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Share notes with transcript") }, onClick = { menu = false; model.share(context, includeTranscript = true) })
                        DropdownMenuItem(
                            text = { Text("Copy transcript") },
                            onClick = {
                                menu = false
                                val copied = model.copyTranscript(context)
                                val problem = when {
                                    copied == null -> "The transcript is too long to copy. Share notes with transcript instead; it sends a file."
                                    copied > MarkdownExport.MAX_TEXT_CHARS -> "Copied. Many apps cut text this long short when it's pasted; Share notes with transcript sends it as a file."
                                    else -> null
                                }
                                problem?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                            },
                            enabled = utterances.isNotEmpty(),
                        )
                        DropdownMenuItem(text = { Text("Rename meeting") }, onClick = { menu = false; dialog = "title" })
                        DropdownMenuItem(text = { Text("Re-split speakers") }, onClick = { menu = false; dialog = "resplit" }, enabled = current.status == MeetingStatus.READY)
                        DropdownMenuItem(text = { Text("Process again from the start") }, onClick = { menu = false; dialog = "reprocess" }, enabled = current.audioFile != null)
                        DropdownMenuItem(text = { Text("Delete the recording's audio") }, onClick = { menu = false; dialog = "audio" }, enabled = current.audioFile != null)
                        DropdownMenuItem(text = { Text("Delete meeting") }, onClick = { menu = false; dialog = "delete" })
                    }
                },
            )
        },
        bottomBar = { PlayerBar(model) },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            if (current.status != MeetingStatus.READY) ProcessingPanel(current, job, live, model)
            PrimaryTabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { i, title -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) }) }
            }
            when (tab) {
                0 -> SummaryTab(current, summary, model)
                1 -> TranscriptTab(utterances, speakers, model, seekTo) { seekTo = null }
                2 -> ActionsTab(actions, model) { ms ->
                    seekTo = ms
                    tab = 1
                }
                else -> extraTabs[tab - TABS.size].second()
            }
        }
    }

    when (dialog) {
        "title" -> TextDialog("Rename meeting", current.title, onDismiss = { dialog = null }) { model.rename(it) }
        "resplit" -> TextDialog(
            "Re-split speakers",
            current.headCount?.toString() ?: "",
            label = "Number of people",
            supporting = "Keeps the transcription and separates the speakers again. Names follow the voices where possible.",
            number = true,
            onDismiss = { dialog = null },
        ) { model.resplit(it.toIntOrNull()?.takeIf { n -> n > 0 }) }
        "reprocess" -> ConfirmDialog("Process again?", "Transcribes the recording again and replaces the transcript and notes. Speaker names follow the voices where they can; lines you moved are not kept.", "Process", { dialog = null }) { model.reprocess() }
        "audio" -> ConfirmDialog("Delete the audio?", "The transcript and notes stay. You won't be able to play it back, re-split speakers or process it again.", "Delete audio", { dialog = null }) { model.deleteAudio() }
        "delete" -> ConfirmDialog("Delete this meeting?", "Deletes its recording, transcript, notes and action items from the phone.", "Delete", { dialog = null }) { model.delete(onBack) }
    }
}

@Composable
private fun ProcessingPanel(meeting: Meeting, job: ProcessingJob?, live: LiveProgress?, model: MeetingViewModel) {
    Card(Modifier.fillMaxWidth().padding(12.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (meeting.status) {
                MeetingStatus.RECORDING -> Text("Recording in progress")
                MeetingStatus.RECORDED -> {
                    Text("Not processed yet", style = MaterialTheme.typography.titleSmall)
                    Button(onClick = model::reprocess, enabled = meeting.audioFile != null) { Text("Process now") }
                }
                MeetingStatus.FAILED -> {
                    Text("Processing failed", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                    job?.error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Button(onClick = model::retry) { Text("Try again") }
                }
                MeetingStatus.QUEUED, MeetingStatus.PROCESSING -> {
                    val stages = Stage.entries.filter { it != Stage.DONE }
                    val currentStage = live?.stage ?: job?.stage
                    // Stages such as the summary report no progress for minutes, so the estimate counts down by the clock.
                    val now by produceState(System.currentTimeMillis()) {
                        while (true) {
                            delay(5_000)
                            value = System.currentTimeMillis()
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (meeting.status == MeetingStatus.QUEUED) "Waiting to process" else "Processing", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        live?.let { p -> Eta.text(meeting, p, now)?.let { Text(it, style = MaterialTheme.typography.bodySmall) } }
                    }
                    for (stage in stages) {
                        val done = currentStage != null && stage.ordinal < currentStage.ordinal
                        val active = stage == currentStage && meeting.status == MeetingStatus.PROCESSING
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(24.dp)) {
                                when {
                                    done -> Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
                                    active -> CircularProgressIndicator(Modifier.height(16.dp).width(16.dp), strokeWidth = 2.dp)
                                }
                            }
                            Text(stage.label, style = MaterialTheme.typography.bodyMedium, color = if (done || active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (active && live != null) {
                            if (live.progress < 0) {
                                Text("Waiting for the charger", style = MaterialTheme.typography.bodySmall)
                            } else {
                                LinearProgressIndicator(progress = { live.progress }, modifier = Modifier.fillMaxWidth().padding(start = 24.dp))
                            }
                        }
                    }
                    TextButton(onClick = model::cancel) { Text("Cancel") }
                }
                MeetingStatus.READY -> Unit
            }
        }
    }
}

/** Rough time left, from this phone's measured speeds: speech-to-text is the bulk, then the summary. */
private object Eta {
    private val realTimeFactor = mapOf(
        Stage.VAD to 0.004, Stage.ASR to 0.07, Stage.DIARIZE to 0.025, Stage.SPEAKERS to 0.01, Stage.ALIGN to 0.001,
        Stage.INDEX to 0.005,
    )

    fun text(meeting: Meeting, live: LiveProgress, now: Long): String? {
        if (live.progress < 0) return null
        val audioS = meeting.durationMs / 1000.0
        val elapsed = (now - live.stageStartedAt) / 1000.0
        val stageLeft = if (live.progress > 0.05) {
            elapsed / live.progress * (1 - live.progress)
        } else {
            maxOf(0.0, seconds(live.stage, audioS) - elapsed)
        }
        val later = Stage.entries.filter { it.ordinal > live.stage.ordinal }.sumOf { seconds(it, audioS) }
        val left = (stageLeft + later).toInt()
        return if (left < 60) "Under a minute left" else "About ${(left + 30) / 60} min left"
    }

    /** Gemma reads about 23 minutes of meeting per part; each part takes about 2 minutes, combining them 3 more. */
    private fun seconds(stage: Stage, audioS: Double): Double {
        if (stage != Stage.SUMMARY) return (realTimeFactor[stage] ?: 0.0) * audioS
        val minutes = audioS / 60
        val parts = ceil(minutes / 23).coerceAtLeast(1.0)
        return parts * 110 + minutes * 1.4 + if (parts > 1) 170 else 0
    }
}

@Composable
private fun SummaryTab(meeting: Meeting, summary: Summary?, model: MeetingViewModel) {
    val tidy by model.tidy.collectAsState()
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (summary == null) {
            item {
                if (meeting.status == MeetingStatus.READY) {
                    Text(if (model.llmAvailable) "No summary yet." else "The language model is not on the phone, so there is no summary. See Settings.")
                    if (model.llmAvailable) Button(onClick = model::regenerateSummary) { Text("Write summary") }
                } else {
                    Text("The summary appears here once processing finishes.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            return@LazyColumn
        }
        item {
            Text(Format.date(meeting.startedAt) + " · " + Format.duration(meeting.durationMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(summary.overview, style = MaterialTheme.typography.bodyLarge)
        }
        bulletSection("Key points", model.repository.decodeList(summary.keyPointsJson))
        bulletSection("Decisions", model.repository.decodeList(summary.decisionsJson))
        bulletSection("Open questions", model.repository.decodeList(summary.openQuestionsJson))
        item {
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text("Tidy notes", style = MaterialTheme.typography.titleMedium)
            when (val state = tidy) {
                is TidyState.Working -> {
                    Text("Writing tidy notes from the transcript. This takes a few minutes.", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
                is TidyState.Failed -> Text(state.message, color = MaterialTheme.colorScheme.error)
                TidyState.Idle -> Unit
            }
        }
        summary.tidyNotes?.let { notes -> item { MarkdownText(notes) } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = model::writeTidyNotes, enabled = tidy !is TidyState.Working && model.llmAvailable) {
                    Text(if (summary.tidyNotes == null) "Write tidy notes" else "Rewrite tidy notes")
                }
                OutlinedButton(onClick = model::regenerateSummary, enabled = model.llmAvailable) { Text("Regenerate summary") }
            }
            Spacer(Modifier.height(8.dp))
            Text("Written by ${summary.model} on the phone.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.bulletSection(title: String, items: List<String>) {
    if (items.isEmpty()) return
    item {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        items.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 4.dp)) }
    }
}

/** Renders the small Markdown subset the tidy notes use: ## headings, bullets and **bold**. */
@Composable
fun MarkdownText(markdown: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (raw in markdown.lines()) {
            val line = raw.trimEnd()
            when {
                line.isBlank() -> Spacer(Modifier.height(4.dp))
                line.startsWith("#") -> Text(line.trimStart('#', ' '), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") ->
                    Text(bold("• " + line.trimStart().drop(2)), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = if (raw.startsWith(" ")) 16.dp else 0.dp))
                else -> Text(bold(line), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private fun bold(text: String) = buildAnnotatedString {
    val parts = text.split("**")
    parts.forEachIndexed { i, part -> if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(part) } else append(part) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TranscriptTab(utterances: List<Utterance>, speakers: List<Speaker>, model: MeetingViewModel, seekTo: Long?, onSeekHandled: () -> Unit) {
    val names = speakers.associate { it.label to MeetingRepository.displayName(it) }
    var renaming by remember { mutableStateOf<Speaker?>(null) }
    var moving by remember { mutableStateOf<Utterance?>(null) }
    var query by remember { mutableStateOf("") }
    val player = remember { model.player() }
    val position by (player?.position ?: remember { kotlinx.coroutines.flow.MutableStateFlow(-1L) }).collectAsState()
    val playing by (player?.playing ?: remember { kotlinx.coroutines.flow.MutableStateFlow(false) }).collectAsState()
    val list = rememberLazyListState()
    val shown = if (query.isBlank()) utterances else utterances.filter { it.text.contains(query, ignoreCase = true) }

    LaunchedEffect(seekTo, utterances.isNotEmpty()) {
        val target = seekTo ?: return@LaunchedEffect
        // The transcript loads after the first frame; keep the jump until there is something to jump to.
        if (utterances.isEmpty()) return@LaunchedEffect
        val index = utterances.indexOfLast { it.startMs <= target }.coerceAtLeast(0)
        list.scrollToItem(index + 1)
        player?.playFrom(utterances[index].startMs)
        onSeekHandled()
    }

    if (utterances.isEmpty()) {
        Text("The transcript appears here once processing finishes.", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    LazyColumn(state = list, contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(speakers, key = { it.label }) { speaker ->
                        AssistChip(
                            onClick = { renaming = speaker },
                            label = { Text(MeetingRepository.displayName(speaker)) },
                            leadingIcon = if (speaker.autoNamed) {
                                { Icon(Icons.Outlined.RecordVoiceOver, "Recognised voice", Modifier.size(AssistChipDefaults.IconSize)) }
                            } else {
                                null
                            },
                            colors = AssistChipDefaults.assistChipColors(labelColor = speakerColour(speaker.label)),
                        )
                    }
                }
                val hint = "Tap a name to rename that speaker. Long-press a line to move it to someone else." +
                    if (speakers.any { it.autoNamed }) " Names with a voice icon were recognised from earlier meetings." else ""
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(query, { query = it }, placeholder = { Text("Search the transcript") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        }
        itemsIndexed(shown, key = { _, u -> u.id }) { _, u ->
            val current = playing && position in u.startMs until u.endMs
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(if (current) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                    .combinedClickable(onClick = { player?.playFrom(u.startMs) }, onLongClick = { moving = u })
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(names[u.speakerLabel] ?: MeetingRepository.defaultSpeakerName(u.speakerLabel), color = speakerColour(u.speakerLabel), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(8.dp))
                    Text(Summarizer.clock(u.startMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(u.text, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }

    renaming?.let { speaker -> SpeakerNameDialog(speaker, model) { renaming = null } }
    moving?.let { u ->
        AlertDialog(
            onDismissRequest = { moving = null },
            title = { Text("Who said this?") },
            text = {
                Column {
                    Text(u.text, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    speakers.forEach { s ->
                        TextButton(onClick = {
                            model.moveUtterance(u.id, s.label)
                            moving = null
                        }) { Text(MeetingRepository.displayName(s), color = speakerColour(s.label)) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { moving = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PlayerBar(model: MeetingViewModel) {
    val player = remember { model.player() } ?: return
    val playing by player.playing.collectAsState()
    val position by player.position.collectAsState()
    if (!playing && position == 0L) return
    BottomAppBar {
        IconButton(onClick = { player.skip(-10_000) }) { Icon(Icons.Outlined.Replay10, "Back 10 seconds") }
        IconButton(onClick = player::toggle) { Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Pause" else "Play") }
        IconButton(onClick = { player.skip(10_000) }) { Icon(Icons.Outlined.Forward10, "Forward 10 seconds") }
        Text("${Format.duration(position)} / ${Format.duration(player.durationMs)}", modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun ActionsTab(actions: List<ActionItem>, model: MeetingViewModel, onJump: (Long) -> Unit) {
    val context = LocalContext.current
    if (actions.isEmpty()) {
        Text("No action items.", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(actions, key = { it.id }) { a ->
            Row(verticalAlignment = Alignment.Top) {
                Checkbox(checked = a.done, onCheckedChange = { model.setActionDone(a.id, it) })
                Column(Modifier.weight(1f).padding(top = 12.dp)) {
                    Text(a.task, style = MaterialTheme.typography.bodyLarge)
                    val details = listOfNotNull(a.owner, a.due?.let { "due $it" }).joinToString(" · ")
                    if (details.isNotEmpty()) Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    a.evidenceMs?.let { ms ->
                        Text(
                            "Said at ${Summarizer.clock(ms)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp).clickable { onJump(ms) },
                        )
                    }
                }
            }
        }
        item {
            OutlinedButton(onClick = {
                val text = actions.joinToString("\n") { a -> "- ${a.owner?.let { "$it: " } ?: ""}${a.task}${a.due?.let { " (due $it)" } ?: ""}" }
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Action items", text))
            }) {
                Icon(Icons.Outlined.ContentCopy, null)
                Spacer(Modifier.width(8.dp))
                Text("Copy all")
            }
        }
    }
}

@Composable
private fun SpeakerNameDialog(speaker: Speaker, model: MeetingViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(speaker.displayName ?: "") }
    var self by remember { mutableStateOf(false) }
    LaunchedEffect(speaker) { self = model.isSelf(speaker) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Name this speaker") },
        text = {
            Column {
                if (speaker.autoNamed) {
                    Text(
                        "Recognised from a voice you named in an earlier meeting. If this is someone else, enter their name.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                Row(Modifier.clickable { self = !self }.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = self, onCheckedChange = { self = it })
                    Text("This is me")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                model.renameSpeaker(speaker.label, name, self)
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun TextDialog(
    title: String,
    initial: String,
    label: String? = null,
    supporting: String? = null,
    number: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value,
                { value = if (number) it.filter(Char::isDigit).take(2) else it },
                label = label?.let { { Text(it) } },
                supportingText = supporting?.let { { Text(it) } },
                singleLine = true,
                keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
            )
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(value)
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = {
                onConfirm()
                onDismiss()
            }) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
