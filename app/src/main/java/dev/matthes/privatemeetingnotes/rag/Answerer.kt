package dev.matthes.privatemeetingnotes.rag

import com.google.ai.edge.litertlm.BenchmarkInfo
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import dev.matthes.privatemeetingnotes.AppContainer
import dev.matthes.privatemeetingnotes.data.ChatMessage
import dev.matthes.privatemeetingnotes.data.ChatThread
import dev.matthes.privatemeetingnotes.data.SourceType
import dev.matthes.privatemeetingnotes.diagnostics.Log
import dev.matthes.privatemeetingnotes.llm.LlmTask
import dev.matthes.privatemeetingnotes.llm.Summarizer
import dev.matthes.privatemeetingnotes.llm.text
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A passage an answer cites, with what is needed to open it at the right place. */
@Serializable
data class Citation(
    val number: Int,
    val type: SourceType,
    val sourceId: String,
    val title: String,
    val startMs: Long? = null,
    val page: Int? = null,
)

/** Answers questions from retrieved passages only, streaming the reply and keeping the chat's memo short. */
class Answerer(private val c: AppContainer) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Speed of the last answer, when LiteRT-LM's benchmark flag is on (debug benchmarks only). */
    var lastBenchmark: BenchmarkInfo? = null
        private set

    /** Answers [question] in [thread], calling [onText] with the reply so far. Saves both messages. */
    @OptIn(ExperimentalApi::class)
    suspend fun ask(thread: ChatThread, question: String, scope: SearchScope, onText: (String) -> Unit): ChatMessage {
        val started = System.nanoTime()
        val history = c.database.knowledge().messages(thread.id).takeLast(HISTORY_MESSAGES)
        c.database.knowledge().insertMessage(ChatMessage(threadId = thread.id, role = USER, text = question, citationsJson = null, createdAt = System.currentTimeMillis()))
        val passages = c.retriever.search(question, c.embedder.query(question), scope, PASSAGES)
        val retrievedMs = (System.nanoTime() - started) / 1_000_000
        var firstTokenMs = -1L
        var conversationMs = -1L
        val reply = c.llm.withEngine(LlmTask.ANSWERS) { engine ->
            val created = System.nanoTime()
            // Greedy decoding: answers quote figures from the passages, and sampling occasionally garbled them.
            c.llm.conversation(engine, ANSWER_SYSTEM, temperature = 0.0, greedy = true).use { conversation ->
                conversationMs = (System.nanoTime() - created) / 1_000_000
                val text = StringBuilder()
                conversation.sendMessageAsync(Message.user(request(thread.memo, history, passages, question))).collect { part ->
                    if (firstTokenMs < 0) firstTokenMs = (System.nanoTime() - started) / 1_000_000
                    text.append(part.text())
                    onText(text.toString())
                }
                if (ExperimentalFlags.enableBenchmark) lastBenchmark = conversation.getBenchmarkInfo()
                text.toString().trim()
            }
        }
        Log.i("answer: ${passages.size} passages, retrieval $retrievedMs ms, conversation $conversationMs ms, first words $firstTokenMs ms, total ${(System.nanoTime() - started) / 1_000_000} ms")
        val cited = CITATION.findAll(reply).flatMap { m -> m.groupValues[1].split(',').map { it.trim().toInt() } }.toSet()
        val citations = passages.mapIndexedNotNull { i, p ->
            if (i + 1 !in cited) return@mapIndexedNotNull null
            Citation(i + 1, p.chunk.sourceType, p.chunk.sourceId, p.sourceTitle, p.chunk.startMs, p.chunk.page)
        }
        val message = ChatMessage(
            threadId = thread.id,
            role = ASSISTANT,
            text = reply,
            citationsJson = json.encodeToString(ListSerializer(Citation.serializer()), citations),
            createdAt = System.currentTimeMillis(),
        )
        val id = c.database.knowledge().insertMessage(message)
        c.database.knowledge().upsertThread(thread.copy(title = thread.title.ifBlank { question.take(60) }, updatedAt = System.currentTimeMillis()))
        updateMemo(thread.id)
        return message.copy(id = id)
    }

    fun citations(message: ChatMessage): List<Citation> = message.citationsJson?.let {
        runCatching { json.decodeFromString(ListSerializer(Citation.serializer()), it) }.getOrNull()
    } ?: emptyList()

    /** Folds messages older than the recent ones into a short memo, so long chats still fit the context window. */
    private suspend fun updateMemo(threadId: String) {
        val thread = c.database.knowledge().thread(threadId) ?: return
        val messages = c.database.knowledge().messages(threadId)
        if (messages.size <= MEMO_AFTER) return
        val older = messages.dropLast(HISTORY_MESSAGES)
        if (older.size % MEMO_EVERY != 0) return
        val transcript = older.joinToString("\n") { "${if (it.role == USER) "Question" else "Answer"}: ${it.text}" }
        val memo = c.llm.withEngine(LlmTask.ANSWERS) { engine ->
            c.llm.conversation(engine, MEMO_SYSTEM).use { conversation ->
                conversation.sendMessage(Message.user("${thread.memo?.let { "Earlier memo: $it\n\n" } ?: ""}$transcript\n\nWrite the memo.")).text().trim()
            }
        }
        c.database.knowledge().upsertThread(thread.copy(memo = memo))
    }

    private fun request(memo: String?, history: List<ChatMessage>, passages: List<Passage>, question: String): String = buildString {
        memo?.let { append("What the conversation covered earlier: $it\n\n") }
        if (history.isNotEmpty()) {
            append("Recent questions and answers:\n")
            history.forEach { append(if (it.role == USER) "Q: " else "A: ").append(it.text.take(600)).append('\n') }
            append('\n')
        }
        if (passages.isEmpty()) {
            append("No passages were found for this question.\n\n")
        } else {
            append("Passages:\n")
            passages.forEachIndexed { i, p -> append("[${i + 1}] ${label(p)}:\n${p.chunk.text}\n\n") }
        }
        append("Question: $question")
    }

    private fun label(p: Passage): String {
        val date = p.sourceDate?.let { DATE.format(Date(it)) }
        return when (p.chunk.sourceType) {
            SourceType.DOCUMENT -> "Document \"${p.sourceTitle}\"" + (p.chunk.page?.let { ", page $it" } ?: "")
            SourceType.SUMMARY -> "Notes of the meeting \"${p.sourceTitle}\"" + (date?.let { " on $it" } ?: "")
            SourceType.TRANSCRIPT -> "Meeting \"${p.sourceTitle}\"" + (date?.let { " on $it" } ?: "") +
                (p.chunk.startMs?.let { " at ${Summarizer.clock(it)}" } ?: "") + (p.chunk.speakers?.let { ", with $it" } ?: "")
        }
    }

    companion object {
        const val USER = "user"
        const val ASSISTANT = "assistant"
        const val PASSAGES = 5
        private const val HISTORY_MESSAGES = 4
        private const val MEMO_AFTER = 8
        private const val MEMO_EVERY = 4
        private val CITATION = Regex("\\[(\\d+(?:\\s*,\\s*\\d+)*)]")
        private val DATE = SimpleDateFormat("d MMM yyyy", Locale.getDefault())

        val ANSWER_SYSTEM = """
            You answer questions about the user's own meetings and documents, using only the numbered passages given.
            Give the specific names, dates and figures the passages contain. Documents and meetings often hold different
            parts of an answer, such as a rule in a document and a decision in a meeting: combine them.
            If the passages don't contain the answer, say that you couldn't find it in the meetings or documents.
            Cite the passages you use by number in square brackets right after the statement they support, such as [2];
            when a statement relies on more than one passage, cite each of them, such as [1][3].
            Keep answers short and direct. Refer to people by the names used in the passages. Use Australian spelling.
        """.trimIndent()

        val MEMO_SYSTEM = """
            You keep a short memo of a conversation about meetings and documents: what was asked, what was found,
            and names, dates and decisions mentioned. At most 120 words. Plain text, no preamble.
        """.trimIndent()
    }
}
