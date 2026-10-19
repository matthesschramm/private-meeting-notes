package dev.matthes.privatemeetingnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.matthes.privatemeetingnotes.AppContainer
import dev.matthes.privatemeetingnotes.data.ChatMessage
import dev.matthes.privatemeetingnotes.data.ChatThread
import dev.matthes.privatemeetingnotes.data.SourceType
import dev.matthes.privatemeetingnotes.diagnostics.Log
import dev.matthes.privatemeetingnotes.llm.LlmTask
import dev.matthes.privatemeetingnotes.llm.Summarizer
import dev.matthes.privatemeetingnotes.models.ModelId
import dev.matthes.privatemeetingnotes.rag.Answerer
import dev.matthes.privatemeetingnotes.rag.Citation
import dev.matthes.privatemeetingnotes.rag.SearchScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

enum class TimeRange(val label: String, val days: Int?) { ANY("Any time", null), WEEK("Last 7 days", 7), MONTH("Last 30 days", 30) }

/** Questions about one meeting ([meetingId]) or about everything (null). */
@OptIn(ExperimentalCoroutinesApi::class)
class AskViewModel(private val c: AppContainer, private val meetingId: String?) : ViewModel() {
    val threads: StateFlow<List<ChatThread>> = c.database.knowledge().observeThreads(meetingId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val threadId = MutableStateFlow<String?>(null)
    val messages: StateFlow<List<ChatMessage>> = threadId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else c.database.knowledge().observeMessages(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val partial = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val range = MutableStateFlow(TimeRange.ANY)
    val includeDocuments = MutableStateFlow(true)
    val speaker = MutableStateFlow("")
    private var job: Job? = null

    val ready: Boolean get() = c.llm.available(LlmTask.ANSWERS) && c.models.isUsable(ModelId.EMBEDDING_GEMMA)

    /** Loads the language model while the user types, so the first answer starts sooner. */
    fun warmUp() {
        if (!ready) return
        c.scope.launch { runCatching { c.llm.warmUp() } }
    }

    fun ask(question: String) {
        val text = question.trim()
        if (text.isEmpty() || busy.value) return
        busy.value = true
        error.value = null
        partial.value = ""
        job = c.scope.launch {
            try {
                val thread = threadId.value?.let { c.database.knowledge().thread(it) } ?: ChatThread(
                    id = UUID.randomUUID().toString(),
                    meetingId = meetingId,
                    title = text.take(60),
                    memo = null,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                ).also {
                    c.database.knowledge().upsertThread(it)
                    threadId.value = it.id
                }
                val days = range.value.days
                val scope = SearchScope(
                    meetingId = meetingId,
                    fromMs = days?.let { System.currentTimeMillis() - it * 86_400_000L },
                    speaker = speaker.value.trim().ifEmpty { null },
                    includeDocuments = includeDocuments.value,
                )
                c.answerer.ask(thread, text, scope) { partial.value = it }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    Log.e("answer failed", e)
                    error.value = e.message ?: "The question could not be answered"
                }
            } finally {
                partial.value = null
                busy.value = false
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    fun newThread() {
        threadId.value = null
    }

    fun openThread(id: String) {
        threadId.value = id
    }

    fun citations(message: ChatMessage): List<Citation> = c.answerer.citations(message)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AskScreen(model: AskViewModel, padding: PaddingValues, onOpenMeeting: (String, Long?) -> Unit, onOpenDocument: (String, Int?) -> Unit) {
    val threads by model.threads.collectAsState()
    var history by remember { mutableStateOf(false) }
    Scaffold(
        modifier = Modifier.padding(padding).consumeWindowInsets(padding),
        topBar = {
            TopAppBar(
                title = { Text("Ask") },
                actions = {
                    IconButton(onClick = model::newThread) { Icon(Icons.Outlined.Add, "New chat") }
                    IconButton(onClick = { history = true }) { Icon(Icons.Outlined.History, "Earlier chats") }
                    DropdownMenu(expanded = history, onDismissRequest = { history = false }) {
                        if (threads.isEmpty()) DropdownMenuItem(text = { Text("No earlier chats") }, onClick = { history = false })
                        threads.forEach { thread ->
                            DropdownMenuItem(text = { Text(thread.title) }, onClick = {
                                model.openThread(thread.id)
                                history = false
                            })
                        }
                    }
                },
            )
        },
    ) { inner ->
        Box(Modifier.padding(inner)) {
            AskPanel(model, global = true, onOpenMeeting, onOpenDocument)
        }
    }
}

/** The chat itself, shared by the Ask tab and a meeting's Ask tab. */
@Composable
fun AskPanel(model: AskViewModel, global: Boolean, onOpenMeeting: (String, Long?) -> Unit, onOpenDocument: (String, Int?) -> Unit) {
    val messages by model.messages.collectAsState()
    val partial by model.partial.collectAsState()
    val busy by model.busy.collectAsState()
    val error by model.error.collectAsState()
    val range by model.range.collectAsState()
    val includeDocuments by model.includeDocuments.collectAsState()
    var question by remember { mutableStateOf("") }
    val list = rememberLazyListState()

    LaunchedEffect(Unit) { model.warmUp() }
    LaunchedEffect(messages.size, partial) {
        val count = messages.size + (if (partial != null) 1 else 0)
        if (count > 0) list.scrollToItem(count - 1)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        if (global) {
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(TimeRange.entries) { r -> FilterChip(selected = range == r, onClick = { model.range.value = r }, label = { Text(r.label) }) }
                item { FilterChip(selected = includeDocuments, onClick = { model.includeDocuments.value = !includeDocuments }, label = { Text("Documents") }) }
            }
        }
        if (!model.ready) {
            Text("Questions need the language and search models on the phone. See Settings.", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
        }
        LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (messages.isEmpty() && partial == null) {
                item {
                    Text(
                        if (global) "Ask about any meeting or document on the phone, for example \"What did we decide about the launch date?\""
                        else "Ask about this meeting, for example \"Who is following up with finance?\"",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(messages, key = { it.id }) { m ->
                if (m.role == Answerer.USER) Bubble(m.text, mine = true) else {
                    Column {
                        Bubble(m.text, mine = false)
                        val citations = model.citations(m)
                        if (citations.isNotEmpty()) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                                items(citations) { c -> CitationChip(c, onOpenMeeting, onOpenDocument) }
                            }
                        }
                    }
                }
            }
            partial?.let { text ->
                item {
                    if (text.isEmpty()) {
                        Column {
                            Text("Searching your meetings and documents", style = MaterialTheme.typography.bodySmall)
                            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                        }
                    } else {
                        Bubble(text, mine = false)
                    }
                }
            }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                question,
                { question = it },
                placeholder = { Text("Ask a question") },
                modifier = Modifier.weight(1f),
                maxLines = 4,
                enabled = model.ready,
            )
            if (busy) {
                IconButton(onClick = model::stop) { Icon(Icons.Outlined.Stop, "Stop") }
            } else {
                IconButton(onClick = {
                    model.ask(question)
                    question = ""
                }, enabled = question.isNotBlank() && model.ready) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
            }
        }
    }
}

@Composable
private fun Bubble(text: String, mine: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Box(
            Modifier
                .widthIn(max = 320.dp)
                .background(
                    if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(16.dp),
                )
                .padding(12.dp),
        ) {
            if (mine) Text(text, style = MaterialTheme.typography.bodyLarge) else MarkdownText(text)
        }
    }
}

@Composable
private fun CitationChip(c: Citation, onOpenMeeting: (String, Long?) -> Unit, onOpenDocument: (String, Int?) -> Unit) {
    val where = when (c.type) {
        SourceType.DOCUMENT -> c.page?.let { "p. $it" }
        SourceType.TRANSCRIPT -> c.startMs?.let { Summarizer.clock(it) }
        SourceType.SUMMARY -> "notes"
    }
    AssistChip(
        onClick = { if (c.type == SourceType.DOCUMENT) onOpenDocument(c.sourceId, c.page) else onOpenMeeting(c.sourceId, c.startMs) },
        label = { Text("[${c.number}] ${c.title.take(28)}${where?.let { " · $it" } ?: ""}") },
    )
}
