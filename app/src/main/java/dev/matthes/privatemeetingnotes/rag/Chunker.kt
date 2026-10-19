package dev.matthes.privatemeetingnotes.rag

/** A piece of a source small enough to embed and to quote in a prompt. */
data class TextChunk(
    val text: String,
    val startMs: Long? = null,
    val endMs: Long? = null,
    val page: Int? = null,
    val speakers: List<String> = emptyList(),
)

/** One speaker turn of a transcript. */
data class TurnText(val speaker: String, val startMs: Long, val endMs: Long, val text: String)

object Chunker {
    /**
     * 200 words rather than the plan's 350: five passages of this size are what Gemma can read on this phone's GPU
     * within the target of about 8 seconds to the first words of an answer.
     */
    const val TARGET_WORDS = 200
    const val OVERLAP_WORDS = 40

    /**
     * Documents: windows of about 200 words overlapping by 40, within each page, so a citation opens the page that
     * holds the passage. A page's first window starts with the previous page's last 40 words, keeping sentences
     * across a page break readable. A tail shorter than the overlap joins the window before it.
     */
    fun document(pages: List<String>): List<TextChunk> {
        val chunks = mutableListOf<TextChunk>()
        var carry = emptyList<String>()
        pages.forEachIndexed { index, page ->
            val own = page.split(WHITESPACE).filter { it.isNotEmpty() }
            if (own.isEmpty()) return@forEachIndexed
            val words = if (own.size >= OVERLAP_WORDS) carry + own else own
            var start = 0
            while (true) {
                var end = minOf(words.size, start + TARGET_WORDS)
                if (words.size - end < OVERLAP_WORDS) end = words.size
                chunks += TextChunk(words.subList(start, end).joinToString(" "), page = index + 1)
                if (end == words.size) break
                start = end - OVERLAP_WORDS
            }
            carry = (carry + own).takeLast(OVERLAP_WORDS)
        }
        return chunks
    }

    /**
     * Transcripts: consecutive turns up to about 200 words, broken between turns so a chunk reads as dialogue.
     * A single turn longer than that is split on its own, with overlap, like a document.
     */
    fun transcript(turns: List<TurnText>): List<TextChunk> {
        val chunks = mutableListOf<TextChunk>()
        var current = mutableListOf<TurnText>()
        var words = 0
        fun flush() {
            if (current.isEmpty()) return
            chunks += TextChunk(
                text = current.joinToString("\n") { "${it.speaker}: ${it.text}" },
                startMs = current.first().startMs,
                endMs = current.last().endMs,
                speakers = current.map { it.speaker }.distinct(),
            )
            current = mutableListOf()
            words = 0
        }
        for (turn in turns) {
            val count = turn.text.split(WHITESPACE).count { it.isNotEmpty() }
            if (count > TARGET_WORDS) {
                flush()
                document(listOf(turn.text)).forEach { piece ->
                    chunks += TextChunk("${turn.speaker}: ${piece.text}", turn.startMs, turn.endMs, speakers = listOf(turn.speaker))
                }
                continue
            }
            if (words + count > TARGET_WORDS) flush()
            current += turn
            words += count
        }
        flush()
        return chunks
    }

    /**
     * Notes: whole lines (the overview, each key point, decision and action item) up to about 200 words, so the notes
     * of a long meeting fit the embedding model. A line longer than that is split on its own, like a document.
     */
    fun notes(text: String): List<TextChunk> {
        val chunks = mutableListOf<TextChunk>()
        val current = mutableListOf<String>()
        var words = 0
        fun flush() {
            if (current.isEmpty()) return
            chunks += TextChunk(current.joinToString("\n"))
            current.clear()
            words = 0
        }
        for (line in text.lines().filter { it.isNotBlank() }) {
            val count = line.split(WHITESPACE).count { it.isNotEmpty() }
            if (count > TARGET_WORDS) {
                flush()
                document(listOf(line)).forEach { chunks += TextChunk(it.text) }
                continue
            }
            if (words + count > TARGET_WORDS) flush()
            current += line
            words += count
        }
        flush()
        return chunks
    }

    private val WHITESPACE = Regex("\\s+")
}
