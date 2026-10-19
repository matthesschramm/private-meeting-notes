package dev.matthes.privatemeetingnotes.alignment

import dev.matthes.privatemeetingnotes.asr.Word
import dev.matthes.privatemeetingnotes.diarization.SpeakerActivity
import kotlin.math.ceil
import kotlin.math.floor

/** A word with the speaker it was assigned to and how sure that assignment is (mean probability over the word). */
data class SpokenWord(val word: Word, val speaker: Int, val confidence: Float)

/** One speaker's consecutive sentences. */
data class AlignedUtterance(val speaker: Int, val words: List<Word>) {
    val startMs: Long get() = words.first().startMs
    val endMs: Long get() = words.last().endMs
    val text: String get() = words.joinToString(" ") { it.text }
}

/**
 * Turns words and speaker activity into a speaker-labelled transcript:
 * 1. each word goes to the speaker with the most probability mass over its span (widened if the span is silent);
 * 2. each sentence goes to its majority speaker, so labels don't flip mid-sentence, unless it contains a long,
 *    confident run by someone else, which happens when the recogniser doesn't punctuate a change of speaker;
 * 3. consecutive sentences by the same speaker become one utterance, broken at long pauses.
 */
class Aligner(
    private val switchMinWords: Int = 4,
    private val switchMinMs: Long = 1_200,
    private val switchMinConfidence: Float = 0.5f,
    private val sentencePauseMs: Long = 1_500,
    private val utterancePauseMs: Long = 2_000,
    private val utteranceMaxWords: Int = 150,
) {
    fun align(words: List<Word>, activity: SpeakerActivity): List<AlignedUtterance> {
        if (words.isEmpty()) return emptyList()
        val spoken = assignWords(words, activity)
        val sentences = sentences(spoken).flatMap { smooth(it) }
        return utterances(sentences)
    }

    fun assignWords(words: List<Word>, activity: SpeakerActivity): List<SpokenWord> {
        var previous = -1
        return words.map { word ->
            var best = mass(activity, word.startMs, word.endMs)
            var pad = 250L
            while (best.second < 0.2f && pad <= 1_000) {
                best = mass(activity, word.startMs - pad, word.endMs + pad)
                pad *= 2
            }
            val speaker = when {
                best.second >= 0.05f -> best.first
                previous >= 0 -> previous
                else -> nearestActive(activity, word.startMs) ?: best.first
            }
            previous = speaker
            SpokenWord(word, speaker, best.second)
        }
    }

    /** The speaker with the highest mean probability over [startMs, endMs), and that mean. */
    private fun mass(activity: SpeakerActivity, startMs: Long, endMs: Long): Pair<Int, Float> {
        if (activity.frames == 0 || activity.speakers == 0) return Pair(0, 0f)
        val f0 = floor(startMs / FRAME_MS).toInt().coerceIn(0, activity.frames - 1)
        val f1 = ceil(endMs / FRAME_MS).toInt().coerceIn(f0 + 1, activity.frames)
        var bestSpeaker = 0
        var bestSum = -1.0
        for (s in 0 until activity.speakers) {
            var sum = 0.0
            for (f in f0 until f1) sum += activity[f, s]
            if (sum > bestSum) {
                bestSum = sum
                bestSpeaker = s
            }
        }
        return Pair(bestSpeaker, (bestSum / (f1 - f0)).toFloat())
    }

    private fun nearestActive(activity: SpeakerActivity, ms: Long): Int? {
        val centre = (ms / FRAME_MS).toInt().coerceIn(0, maxOf(0, activity.frames - 1))
        for (distance in 0 until NEAREST_SEARCH_FRAMES) {
            for (f in intArrayOf(centre - distance, centre + distance)) {
                if (f !in 0 until activity.frames) continue
                for (s in 0 until activity.speakers) if (activity[f, s] > 0.5f) return s
            }
        }
        return null
    }

    private fun sentences(words: List<SpokenWord>): List<List<SpokenWord>> {
        val out = mutableListOf<List<SpokenWord>>()
        var current = mutableListOf<SpokenWord>()
        for ((i, w) in words.withIndex()) {
            current += w
            val next = words.getOrNull(i + 1)
            val pause = next != null && next.word.startMs - w.word.endMs > sentencePauseMs
            if (endsSentence(w.word.text) || pause || next == null) {
                out += current
                current = mutableListOf()
            }
        }
        return out
    }

    /** One sentence, relabelled to its majority speaker; split where another speaker clearly takes over. */
    private fun smooth(sentence: List<SpokenWord>): List<List<SpokenWord>> {
        val parts = mutableListOf<List<SpokenWord>>()
        var start = 0
        for (run in runs(sentence)) {
            val words = sentence.subList(run.first, run.last + 1)
            val strong = words.size >= switchMinWords &&
                words.last().word.endMs - words.first().word.startMs >= switchMinMs &&
                words.map { it.confidence }.average() >= switchMinConfidence
            if (strong && run.first > start) {
                parts += sentence.subList(start, run.first)
                start = run.first
            }
        }
        parts += sentence.subList(start, sentence.size)
        // Each part keeps the speaker that a run of its own decided; within a part, the majority wins.
        return parts.filter { it.isNotEmpty() }.map { part ->
            val speaker = majority(part)
            part.map { it.copy(speaker = speaker) }
        }
    }

    private fun runs(words: List<SpokenWord>): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var from = 0
        for (i in 1..words.size) {
            if (i == words.size || words[i].speaker != words[from].speaker) {
                out += from until i
                from = i
            }
        }
        return out
    }

    private fun majority(words: List<SpokenWord>): Int = words
        .groupBy { it.speaker }
        .maxBy { (_, group) -> group.sumOf { maxOf(1L, it.word.endMs - it.word.startMs) * (0.5 + it.confidence) } }
        .key

    private fun utterances(sentences: List<List<SpokenWord>>): List<AlignedUtterance> {
        val out = mutableListOf<AlignedUtterance>()
        var speaker = -1
        var words = mutableListOf<Word>()
        for (sentence in sentences) {
            val s = sentence.first().speaker
            val pause = words.isNotEmpty() && sentence.first().word.startMs - words.last().endMs > utterancePauseMs
            if (s != speaker || pause || words.size + sentence.size > utteranceMaxWords) {
                if (words.isNotEmpty()) out += AlignedUtterance(speaker, words)
                speaker = s
                words = mutableListOf()
            }
            sentence.mapTo(words) { it.word }
        }
        if (words.isNotEmpty()) out += AlignedUtterance(speaker, words)
        return out
    }

    companion object {
        private const val FRAME_MS = 10.0
        private const val NEAREST_SEARCH_FRAMES = 300
        private val SENTENCE_END = Regex("""[.?!]["')\]]*$""")

        fun endsSentence(text: String) = SENTENCE_END.containsMatchIn(text)
    }
}
