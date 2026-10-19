package dev.matthes.privatemeetingnotes.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.matthes.privatemeetingnotes.AppContainer
import dev.matthes.privatemeetingnotes.data.Document
import dev.matthes.privatemeetingnotes.diagnostics.Log
import dev.matthes.privatemeetingnotes.models.ModelId
import dev.matthes.privatemeetingnotes.rag.DocumentExtractor
import dev.matthes.privatemeetingnotes.rag.ExtractedText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class LibraryTask(val label: String, val progress: Float, val error: String? = null)

class LibraryViewModel(private val c: AppContainer) : ViewModel() {
    val documents: StateFlow<List<Document>> = c.database.knowledge().observeDocuments()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val task = MutableStateFlow<LibraryTask?>(null)

    val ready: Boolean get() = c.models.isUsable(ModelId.EMBEDDING_GEMMA)

    fun add(uri: Uri) {
        task.value = LibraryTask("Adding document", 0f)
        c.scope.launch {
            try {
                c.indexer.addDocument(uri) { task.value = LibraryTask("Indexing document", it) }
                task.value = null
            } catch (e: Exception) {
                Log.e("document could not be added", e)
                task.value = LibraryTask("Adding document", 0f, e.message ?: "The document could not be added")
            }
        }
    }

    fun remove(id: String) = viewModelScope.launch { c.indexer.deleteDocument(id) }

    fun reindexAll() {
        task.value = LibraryTask("Re-indexing meetings and documents", 0f)
        c.scope.launch {
            runCatching { c.indexer.reindexAll { task.value = LibraryTask("Re-indexing meetings and documents", it) } }
                .onFailure { Log.e("re-indexing failed", it) }
            task.value = null
        }
    }

    fun dismissError() {
        task.value = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(model: LibraryViewModel, padding: PaddingValues, onOpen: (String) -> Unit) {
    val documents by model.documents.collectAsState()
    val task by model.task.collectAsState()
    var removing by remember { mutableStateOf<Document?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(model::add) }

    Scaffold(
        modifier = Modifier.padding(padding).consumeWindowInsets(padding),
        topBar = {
            TopAppBar(
                title = { Text("Library") },
                actions = { IconButton(onClick = model::reindexAll, enabled = task == null && model.ready) { Icon(Icons.Outlined.Refresh, "Re-index everything") } },
            )
        },
        floatingActionButton = {
            if (model.ready) {
                ExtendedFloatingActionButton(
                    onClick = { picker.launch(DocumentExtractor.SUPPORTED_TYPES) },
                    icon = { Icon(Icons.Outlined.Add, null) },
                    text = { Text("Add document") },
                )
            }
        },
    ) { inner ->
        LazyColumn(Modifier.fillMaxSize().padding(inner), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!model.ready) item { Text("Documents need the search model on the phone. See Settings.", color = MaterialTheme.colorScheme.error) }
            task?.let { t ->
                item {
                    Card {
                        Column(Modifier.padding(16.dp)) {
                            Text(t.label, style = MaterialTheme.typography.titleSmall)
                            if (t.error != null) {
                                Text(t.error, color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = model::dismissError) { Text("OK") }
                            } else {
                                LinearProgressIndicator(progress = { t.progress }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                            }
                        }
                    }
                }
            }
            if (documents.isEmpty()) {
                item {
                    Text(
                        "Add PDFs, Word documents, text or Markdown files. Questions in Ask can then draw on them as well as your meetings. Files are copied into the app and stay on the phone.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 32.dp),
                    )
                }
            }
            items(documents, key = { it.id }) { d ->
                Card(Modifier.fillMaxWidth().clickable { onOpen(d.id) }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(d.title, style = MaterialTheme.typography.titleMedium)
                            val pages = d.pageCount?.let { "$it pages · " } ?: ""
                            val status = when {
                                d.error != null -> "Could not be read: ${d.error}"
                                d.indexedAt == null -> "Indexing"
                                else -> "Added ${Format.date(d.addedAt)}"
                            }
                            Text("$pages${Format.size(d.sizeBytes)} · $status", style = MaterialTheme.typography.bodySmall, color = if (d.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { removing = d }) { Icon(Icons.Outlined.Delete, "Remove") }
                    }
                }
            }
        }
    }

    removing?.let { d ->
        ConfirmDialog("Remove \"${d.title}\"?", "Removes the document and its search index from the phone.", "Remove", { removing = null }) { model.remove(d.id) }
    }
}

/** A document's extracted text, opened at a cited page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentScreen(container: AppContainer, documentId: String, page: Int?, onBack: () -> Unit) {
    var document by remember { mutableStateOf<Document?>(null) }
    var text by remember { mutableStateOf<ExtractedText?>(null) }
    var current by remember { mutableIntStateOf((page ?: 1) - 1) }
    androidx.compose.runtime.LaunchedEffect(documentId) {
        withContext(Dispatchers.IO) {
            val d = container.database.knowledge().document(documentId)
            document = d
            text = d?.let { runCatching { DocumentExtractor.extract(File(container.files.documents, it.fileName)) }.getOrNull() }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(document?.title ?: "Document") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { inner ->
        val pages = text?.pages.orEmpty()
        Column(Modifier.fillMaxSize().padding(inner)) {
            if (pages.size > 1) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { current = (current - 1).coerceAtLeast(0) }, enabled = current > 0) { Text("Previous") }
                    Text("Page ${current + 1} of ${pages.size}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { current = (current + 1).coerceAtMost(pages.size - 1) }, enabled = current < pages.size - 1) { Text("Next") }
                }
            }
            LazyColumn(contentPadding = PaddingValues(16.dp)) {
                item { Text(pages.getOrNull(current.coerceIn(0, maxOf(0, pages.size - 1))) ?: "", style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}
