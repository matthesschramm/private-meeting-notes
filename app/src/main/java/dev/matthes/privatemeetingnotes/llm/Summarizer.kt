package dev.matthes.privatemeetingnotes.llm

import com.google.ai.edge.litertlm.BenchmarkInfo
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ResponseFormat
import dev.matthes.privatemeetingnotes.diagnostics.Log
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One transcript line as the model sees it. */
data class TranscriptLine(val startMs: Long, val speaker: String, val text: String)

@Serializable
data class MeetingNotes(
    val title: String = "",
    val overview: String = "",
    @SerialName("key_points") val keyPoints: List<String> = emptyList(),
    val decisions: List<String> = emptyList(),
    @SerialName("open_questions") val openQuestions: List<String> = emptyList(),
    @SerialName("action_items") val actionItems: List<NotesAction> = emptyList(),
)

@Serializable
data class NotesAction(
    val owner: String = "",
    val task: String = "",
    val due: String = "",
    @SerialName("evidence_time") val evidenceTime: String = "",
)

class UnreadableNotesException : IllegalStateException("The language model's notes could not be read")

/** Summaries, decisions, open questions, action items and tidy notes from a transcript, with Gemma. */
class Summarizer(private val llm: LlmHost) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Speed of the last request, when LiteRT-LM's benchmark flag is on (debug benchmarks only). */
    var lastBenchmark: BenchmarkInfo? = null
        private set

    /** Every raw reply since the flag was turned on, to diagnose replies that don't parse (debug benchmarks only). */
    val benchmarkReplies = mutableListOf<String>()

    /**
     * Whether to constrain every reply to the notes JSON schema. Constrained decoding is about a quarter slower on
     * this phone, so by default the reply is first generated freely and parsed leniently, and only a reply that
     * fails to parse is generated again under the schema. Either way the result always parses.
     */
    var alwaysConstrained = false

    /**
     * Notes for a transcript. A transcript longer than the context window is summarised in parts, which are then
     * combined. A long meeting still gets notes if one part's notes can't be read (the overview says which minutes
     * are missing) or if the combined notes can't be read (the parts' notes are then joined as they are).
     */
    suspend fun notes(lines: List<TranscriptLine>, terms: List<String> = emptyList(), onProgress: (Float) -> Unit = {}): MeetingNotes = llm.withEngine { engine ->
        val parts = split(lines)
        if (parts.size == 1) return@withEngine ask(engine, Prompts.notesRequest(format(parts[0]), terms = terms))
        val partials = parts.mapIndexed { i, part ->
            try {
                ask(engine, Prompts.notesRequest(format(part), i + 1, parts.size, terms))
            } catch (e: UnreadableNotesException) {
                Log.w("notes for part ${i + 1} of ${parts.size} could not be read")
                null
            }.also { onProgress((i + 1f) / (parts.size + 1)) }
        }
        val written = partials.filterNotNull()
        if (written.isEmpty()) throw UnreadableNotesException()
        val combined = combine(engine, written)
        val missing = parts.filterIndexed { i, _ -> partials[i] == null }
        if (missing.isEmpty()) {
            combined
        } else {
            val gaps = missing.joinToString(" and ") { "${clock(it.first().startMs)} to ${clock(it.last().startMs)}" }
            combined.copy(overview = "${combined.overview} (No notes could be written for $gaps; see the transcript.)".trim())
        }
    }

    suspend fun tidyNotes(lines: List<TranscriptLine>, terms: List<String> = emptyList(), onProgress: (Float) -> Unit = {}): String = llm.withEngine { engine ->
        val parts = split(lines)
        parts.mapIndexed { i, part ->
            llm.conversation(engine, Prompts.TIDY_SYSTEM, maxOutputTokens = 4_096).use { conversation ->
                val request = if (parts.size == 1) {
                    Prompts.tidyRequest(format(part), terms = terms)
                } else {
                    Prompts.tidyRequest(format(part), i + 1, parts.size, terms)
                }
                conversation.sendMessage(Message.user(request)).text().trim()
            }.also { onProgress((i + 1f) / parts.size) }
        }.joinToString("\n\n")
    }

    /**
     * One set of notes from consecutive parts' notes. If they don't fit in one request, as in meetings of several
     * hours, batches that do are combined first and then their results.
     */
    private fun combine(engine: Engine, notes: List<MeetingNotes>): MeetingNotes {
        if (notes.size == 1) return notes[0]
        val encoded = notes.map { json.encodeToString(MeetingNotes.serializer(), it) }
        val request = Prompts.combineRequest(encoded)
        val budget = budgetChars(COMBINED_REPLY_TOKENS)
        if (request.length <= budget) {
            return try {
                ask(engine, request, COMBINED_REPLY_TOKENS)
            } catch (e: UnreadableNotesException) {
                Log.w("combined notes could not be read; joining the parts' notes instead")
                merge(notes)
            }
        }
        val batches = batches(encoded.map { it.length }, budget - Prompts.combineRequest(emptyList()).length)
        if (batches.size == notes.size) return merge(notes)
        Log.i("combining the notes of ${notes.size} parts in ${batches.size} batches")
        return combine(engine, batches.map { batch -> combine(engine, batch.map { notes[it] }) })
    }

    private fun ask(engine: Engine, request: String, replyTokens: Int = LlmHost.MAX_OUTPUT_TOKENS): MeetingNotes {
        if (!alwaysConstrained) {
            parseNotes(generate(engine, request, constrained = false, replyTokens))?.let { return it }
            Log.w("notes reply did not parse; generating again under the JSON schema")
        }
        return parseNotes(generate(engine, request, constrained = true, replyTokens)) ?: throw UnreadableNotesException()
    }

    @OptIn(ExperimentalApi::class)
    private fun generate(engine: Engine, request: String, constrained: Boolean, replyTokens: Int): String {
        val started = System.nanoTime()
        val reply = llm.conversation(engine, Prompts.NOTES_SYSTEM, maxOutputTokens = replyTokens, jsonSchema = constrained).use { conversation ->
            val message = Message.user(request)
            val answer = if (constrained) {
                conversation.sendMessage(message, responseFormat = ResponseFormat.json(Prompts.NOTES_SCHEMA))
            } else {
                conversation.sendMessage(message)
            }
            answer.text().also {
                if (benchmarking()) {
                    lastBenchmark = conversation.getBenchmarkInfo()
                    benchmarkReplies += it
                }
            }
        }
        Log.i("notes request of ${request.length} chars answered in ${(System.nanoTime() - started) / 1_000_000} ms (constrained: $constrained)")
        return reply
    }

    @OptIn(ExperimentalApi::class)
    private fun benchmarking() = ExperimentalFlags.enableBenchmark

    /** Characters of request that leave room in the context for the instructions and a reply of [replyTokens]. */
    private fun budgetChars(replyTokens: Int = LlmHost.MAX_OUTPUT_TOKENS) =
        ((llm.contextTokens - RESERVED_TOKENS - (replyTokens - LlmHost.MAX_OUTPUT_TOKENS)) * CHARS_PER_TOKEN).toInt()

    /** Splits the transcript at line boundaries so each part fits the context window with room for the reply. */
    private fun split(lines: List<TranscriptLine>): List<List<TranscriptLine>> {
        val budgetChars = budgetChars()
        val parts = mutableListOf<List<TranscriptLine>>()
        var current = mutableListOf<TranscriptLine>()
        var size = 0
        for (line in lines) {
            val length = formatLine(line).length + 1
            if (current.isNotEmpty() && size + length > budgetChars) {
                parts += current
                current = mutableListOf()
                size = 0
            }
            current += line
            size += length
        }
        if (current.isNotEmpty() || parts.isEmpty()) parts += current
        return parts
    }

    companion object {
        /** Prompt instructions plus the longest expected reply. */
        private const val RESERVED_TOKENS = 3_000
        /**
         * Notes for a whole meeting of several hours, combined from its parts', ran past the usual 2,048 tokens and
         * were cut off. This many take about 8 minutes on the phone's GPU, inside LiteRT-LM's 10 minutes per reply.
         */
        private const val COMBINED_REPLY_TOKENS = 3_072
        /** English text averages about four characters per token; this errs on the side of shorter parts. */
        private const val CHARS_PER_TOKEN = 3.5

        private val strict = Json { ignoreUnknownKeys = true }
        @OptIn(ExperimentalSerializationApi::class)
        private val forgiving = Json { ignoreUnknownKeys = true; allowTrailingComma = true }
        /** A line holding only a quote, and perhaps a comma, is never valid JSON; Gemma sometimes writes one. */
        private val STRAY_QUOTE_LINE = Regex("""(?m)^\s*"\s*,?\s*$\n?""")

        /**
         * The notes in a reply, ignoring any text or code fences around the JSON; null if they can't be read.
         * A reply that isn't valid JSON is read again without stray-quote lines and with trailing commas allowed.
         */
        fun parseNotes(reply: String): MeetingNotes? {
            val start = reply.indexOf('{')
            val end = reply.lastIndexOf('}')
            if (start < 0 || end < start) return null
            val text = reply.substring(start, end + 1)
            return runCatching { strict.decodeFromString(MeetingNotes.serializer(), text) }.getOrNull()
                ?: runCatching { forgiving.decodeFromString(MeetingNotes.serializer(), text.replace(STRAY_QUOTE_LINE, "")) }.getOrNull()
        }

        /** The part tags around each part's notes in a combine request. */
        private const val PART_TAG_CHARS = 32

        /** Runs of consecutive items, by index, whose [sizes] with their part tags add up to at most [budget] each. */
        internal fun batches(sizes: List<Int>, budget: Int): List<List<Int>> {
            val batches = mutableListOf<List<Int>>()
            var current = mutableListOf<Int>()
            var used = 0
            for ((i, size) in sizes.withIndex()) {
                if (current.isNotEmpty() && used + size + PART_TAG_CHARS > budget) {
                    batches += current
                    current = mutableListOf()
                    used = 0
                }
                current += i
                used += size + PART_TAG_CHARS
            }
            if (current.isNotEmpty()) batches += current
            return batches
        }

        /** Joins the notes of consecutive parts without the language model, dropping repeated items. */
        fun merge(parts: List<MeetingNotes>): MeetingNotes = MeetingNotes(
            title = parts.firstOrNull { it.title.isNotBlank() }?.title.orEmpty(),
            overview = parts.joinToString(" ") { it.overview.trim() }.trim(),
            keyPoints = parts.flatMap { it.keyPoints }.distinctBy { it.trim().lowercase() },
            decisions = parts.flatMap { it.decisions }.distinctBy { it.trim().lowercase() },
            openQuestions = parts.flatMap { it.openQuestions }.distinctBy { it.trim().lowercase() },
            actionItems = parts.flatMap { it.actionItems }.distinctBy { "${it.owner.trim().lowercase()}|${it.task.trim().lowercase()}" },
        )

        fun format(lines: List<TranscriptLine>): String = lines.joinToString("\n") { formatLine(it) }

        fun formatLine(line: TranscriptLine) = "[${clock(line.startMs)}] ${line.speaker}: ${line.text}"

        fun clock(ms: Long): String {
            val seconds = ms / 1000
            return if (seconds >= 3600) {
                "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
            } else {
                "%02d:%02d".format(seconds / 60, seconds % 60)
            }
        }

        /** Parses "mm:ss" or "h:mm:ss", with or without brackets, to milliseconds. */
        fun parseClock(text: String): Long? {
            val parts = text.trim().trim('[', ']').split(":").map { it.trim().toLongOrNull() ?: return null }
            return when (parts.size) {
                2 -> (parts[0] * 60 + parts[1]) * 1000
                3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000
                else -> null
            }
        }
    }
}
