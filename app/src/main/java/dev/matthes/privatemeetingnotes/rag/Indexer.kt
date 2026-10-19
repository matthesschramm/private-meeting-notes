package dev.matthes.privatemeetingnotes.rag

import android.net.Uri
import dev.matthes.privatemeetingnotes.AppContainer
import dev.matthes.privatemeetingnotes.data.Chunk
import dev.matthes.privatemeetingnotes.data.Document
import dev.matthes.privatemeetingnotes.data.MeetingRepository
import dev.matthes.privatemeetingnotes.data.SourceType
import dev.matthes.privatemeetingnotes.diagnostics.Log
import dev.matthes.privatemeetingnotes.models.ModelId
import dev.matthes.privatemeetingnotes.speakers.Voices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import android.provider.OpenableColumns
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Keeps the search index (chunks with embeddings, plus the FTS4 keyword index) in step with meetings and documents. */
class Indexer(private val c: AppContainer) {
    private val lock = Mutex()
    private val pending = ConcurrentHashMap<String, Job>()

    val available: Boolean get() = c.models.isUsable(ModelId.EMBEDDING_GEMMA)

    /** Indexes a meeting's transcript and notes, replacing what was indexed before. */
    suspend fun indexMeeting(meetingId: String, onProgress: (Float) -> Unit = {}) = lock.withLock {
        withContext(Dispatchers.Default) {
            val meeting = c.database.meetings().get(meetingId) ?: return@withContext
            val speakers = c.database.transcripts().speakers(meetingId).associateBy { it.label }
            val turns = c.database.transcripts().utterances(meetingId).map { u ->
                TurnText(speakers[u.speakerLabel]?.let(MeetingRepository::displayName) ?: MeetingRepository.defaultSpeakerName(u.speakerLabel), u.startMs, u.endMs, u.text)
            }
            val transcript = Chunker.transcript(turns)
            val notes = c.database.summaries().get(meetingId)?.let { summary ->
                val actions = c.database.summaries().actions(meetingId)
                buildString {
                    appendLine("Summary of the meeting \"${meeting.title}\": ${summary.overview}")
                    c.repository.decodeList(summary.keyPointsJson).forEach { appendLine("Key point: $it") }
                    c.repository.decodeList(summary.decisionsJson).forEach { appendLine("Decision: $it") }
                    c.repository.decodeList(summary.openQuestionsJson).forEach { appendLine("Open question: $it") }
                    actions.forEach { a -> appendLine("Action item: ${a.owner?.let { "$it to " } ?: ""}${a.task}${a.due?.let { ", due $it" } ?: ""}") }
                }
            }
            val notesChunks = notes?.let { Chunker.notes(it) }.orEmpty()
            val total = transcript.size + notesChunks.size
            var done = 0
            val rows = mutableListOf<Chunk>()
            for ((i, chunk) in transcript.withIndex()) {
                rows += Chunk(
                    sourceType = SourceType.TRANSCRIPT,
                    sourceId = meetingId,
                    ordinal = i,
                    text = chunk.text,
                    startMs = chunk.startMs,
                    endMs = chunk.endMs,
                    page = null,
                    speakers = chunk.speakers.joinToString(", "),
                    vector = Voices.toBytes(c.embedder.document(meeting.title, chunk.text)),
                )
                onProgress(++done / total.toFloat())
            }
            for ((i, chunk) in notesChunks.withIndex()) {
                rows += Chunk(
                    sourceType = SourceType.SUMMARY,
                    sourceId = meetingId,
                    ordinal = i,
                    text = chunk.text,
                    startMs = null,
                    endMs = null,
                    page = null,
                    speakers = null,
                    vector = Voices.toBytes(c.embedder.document(meeting.title, chunk.text)),
                )
                onProgress(++done / total.toFloat())
            }
            c.database.knowledge().deleteChunks(SourceType.TRANSCRIPT, meetingId)
            c.database.knowledge().deleteChunks(SourceType.SUMMARY, meetingId)
            c.database.knowledge().insertChunks(rows)
            c.retriever.invalidate()
            Log.i("indexed meeting: ${rows.size} chunks")
        }
    }

    /** Re-indexes a meeting a few seconds after its last change, so a burst of renames costs one pass. */
    fun scheduleMeeting(meetingId: String) {
        if (!available) return
        pending.remove(meetingId)?.cancel()
        pending[meetingId] = c.scope.launch {
            delay(3_000)
            runCatching { indexMeeting(meetingId) }.onFailure { Log.w("re-indexing failed", it) }
            pending.remove(meetingId)
        }
    }

    /** Copies a picked file into app storage, then extracts and indexes it. */
    suspend fun addDocument(uri: Uri, onProgress: (Float) -> Unit): Document = withContext(Dispatchers.IO) {
        val resolver = c.context.contentResolver
        val name = if (uri.scheme == "file") {
            uri.lastPathSegment
        } else {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        }
        val extension = DocumentExtractor.extensionFor(resolver.getType(uri), name)
        val id = UUID.randomUUID().toString()
        val file = java.io.File(c.files.documents, "$id.$extension")
        resolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } } ?: throw IllegalArgumentException("The file could not be opened")
        val document = Document(
            id = id,
            title = name?.substringBeforeLast('.') ?: "Document",
            mimeType = resolver.getType(uri) ?: extension,
            fileName = file.name,
            sizeBytes = file.length(),
            pageCount = null,
            addedAt = System.currentTimeMillis(),
            indexedAt = null,
            error = null,
        )
        c.database.knowledge().upsertDocument(document)
        indexDocument(document, onProgress)
        c.database.knowledge().document(id) ?: document
    }

    suspend fun indexDocument(document: Document, onProgress: (Float) -> Unit = {}) = lock.withLock {
        withContext(Dispatchers.Default) {
            try {
                val started = System.nanoTime()
                val extracted = DocumentExtractor.extract(java.io.File(c.files.documents, document.fileName))
                val extractedAt = System.nanoTime()
                val chunks = Chunker.document(extracted.pages)
                val rows = chunks.mapIndexed { i, chunk ->
                    Chunk(
                        sourceType = SourceType.DOCUMENT,
                        sourceId = document.id,
                        ordinal = i,
                        text = chunk.text,
                        startMs = null,
                        endMs = null,
                        page = chunk.page.takeIf { extracted.pageCount != null },
                        speakers = null,
                        vector = Voices.toBytes(c.embedder.document(document.title, chunk.text)),
                    ).also { onProgress((i + 1f) / chunks.size) }
                }
                c.database.knowledge().deleteChunks(SourceType.DOCUMENT, document.id)
                c.database.knowledge().insertChunks(rows)
                c.database.knowledge().upsertDocument(document.copy(pageCount = extracted.pageCount, indexedAt = System.currentTimeMillis(), error = null))
                c.retriever.invalidate()
                Log.i("indexed document: ${extracted.pageCount ?: 1} pages, ${rows.size} chunks; extraction " +
                    "${(extractedAt - started) / 1_000_000} ms, embedding ${(System.nanoTime() - extractedAt) / 1_000_000} ms")
            } catch (e: Exception) {
                Log.e("document indexing failed", e)
                c.database.knowledge().upsertDocument(document.copy(error = e.message ?: "The document could not be read"))
            }
        }
    }

    suspend fun deleteDocument(id: String) {
        val document = c.database.knowledge().document(id) ?: return
        c.database.knowledge().deleteChunks(SourceType.DOCUMENT, id)
        c.database.knowledge().deleteDocument(id)
        java.io.File(c.files.documents, document.fileName).delete()
        c.retriever.invalidate()
    }

    /** Rebuilds the index once after an app update changes how it is built (chunk sizes, prompts or model). */
    suspend fun upgradeIfNeeded() {
        val prefs = c.context.getSharedPreferences("index", android.content.Context.MODE_PRIVATE)
        if (!available || prefs.getInt(VERSION_KEY, VERSION) == VERSION && prefs.contains(VERSION_KEY)) return
        Log.i("search index format changed; rebuilding")
        reindexAll {}
        prefs.edit().putInt(VERSION_KEY, VERSION).commit()
    }

    /** Rebuilds the whole index, for example after the embedding model changes. */
    suspend fun reindexAll(onProgress: (Float) -> Unit) {
        val meetings = c.database.meetings().all().filter { c.database.transcripts().utterances(it.id).isNotEmpty() }
        val documents = c.database.knowledge().documents()
        val total = (meetings.size + documents.size).coerceAtLeast(1)
        var done = 0
        meetings.forEach { indexMeeting(it.id); onProgress(++done / total.toFloat()) }
        documents.forEach { indexDocument(it); onProgress(++done / total.toFloat()) }
    }

    private companion object {
        /** Bump when chunking, embedding prompts or the embedding model change. */
        const val VERSION = 3
        const val VERSION_KEY = "version"
    }
}
