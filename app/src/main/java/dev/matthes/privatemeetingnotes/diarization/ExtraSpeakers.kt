package dev.matthes.privatemeetingnotes.diarization

import dev.matthes.privatemeetingnotes.audio.AudioSource
import dev.matthes.privatemeetingnotes.audio.SAMPLE_RATE
import dev.matthes.privatemeetingnotes.audio.Spliced
import dev.matthes.privatemeetingnotes.diagnostics.Log
import dev.matthes.privatemeetingnotes.speakers.Embedder
import dev.matthes.privatemeetingnotes.speakers.Voices

/**
 * Nemotron follows at most eight voices, so in a bigger meeting some people share one of its speakers. Each speaker's
 * speech is diarised again on its own, where the few voices sharing it come apart, and the speakers whose parts sound
 * least alike are split until the head count is reached.
 */
object ExtraSpeakers {
    /** A speaker with less speech than this isn't diarised again. */
    private const val MIN_SPEAKER_MS = 20_000L
    /** Each part of a split needs this much speech of its own. */
    private const val MIN_PART_MS = 5_000L
    /** Silence between the stretches played back to back, so each starts like a new turn. */
    private const val GAP_SAMPLES = 3L * SAMPLE_RATE / 10
    private const val FRAME_SAMPLES = 160
    private const val GHOST_MS = 1_000L

    private class Candidate(val speaker: Int, val spliced: Spliced, val parts: SpeakerActivity, val order: List<Int>, val similarity: Float)

    fun split(
        activity: SpeakerActivity,
        audio: AudioSource,
        headCount: Int,
        diarize: (AudioSource) -> SpeakerActivity,
        embedder: Embedder,
        onProgress: (Float) -> Unit = {},
    ): SpeakerActivity {
        val turns = activity.turns().groupBy { it.speaker }
        val speech = turns.mapValues { (_, own) -> own.sumOf { it.endMs - it.startMs } }
        var missing = headCount - speech.count { it.value >= GHOST_MS }
        if (missing <= 0) return activity
        val worth = turns.filterKeys { speech.getValue(it) >= MIN_SPEAKER_MS }
        val candidates = mutableListOf<Candidate>()
        for ((i, entry) in worth.entries.withIndex()) {
            val (speaker, own) = entry
            val spliced = Spliced(audio, own.map { it.startMs * SAMPLE_RATE / 1000 until it.endMs * SAMPLE_RATE / 1000 }, GAP_SAMPLES)
            val parts = diarize(spliced)
            val profiles = Voices.profiles(spliced, parts, embedder)
            val voices = profiles.filter { it.speechMs >= MIN_PART_MS && it.embedding != null }.sortedByDescending { it.speechMs }
            Log.i("speaker $speaker: ${speech.getValue(speaker) / 1000} s of speech in parts of ${profiles.filter { it.speechMs > 0 }.map { "${it.speechMs / 1000} s" + if (it.embedding == null) " (no voice)" else "" }}")
            if (voices.size >= 2) {
                candidates += Candidate(speaker, spliced, parts, voices.map { it.speaker }, Voices.cosine(voices[0].vector()!!, voices[1].vector()!!))
            }
            onProgress((i + 1f) / worth.size)
        }

        // The first part keeps the speaker's column; the others get new ones, most distinct voices first.
        val columnOf = mutableMapOf<Pair<Int, Int>, Int>()
        val accepted = mutableListOf<Pair<Candidate, List<Int>>>()
        var columns = activity.speakers
        for (candidate in candidates.sortedBy { it.similarity }) {
            if (missing <= 0) break
            val kept = candidate.order.take(1 + minOf(missing, candidate.order.size - 1))
            kept.drop(1).forEach { columnOf[candidate.speaker to it] = columns++ }
            missing -= kept.size - 1
            accepted += candidate to kept
        }
        Log.i("${candidates.size} of ${activity.speakers} speakers held more than one voice; ${accepted.size} split for ${columns - activity.speakers} more")
        if (accepted.isEmpty()) return activity

        val probs = FloatArray(activity.frames * columns)
        for (f in 0 until activity.frames) for (s in 0 until activity.speakers) probs[f * columns + s] = activity[f, s]
        for ((candidate, kept) in accepted) {
            for (t in 0 until candidate.parts.frames) {
                val part = kept.maxBy { candidate.parts[t, it] }
                if (candidate.parts[t, part] <= 0.5f) continue
                val column = columnOf[candidate.speaker to part] ?: continue
                val origin = candidate.spliced.original(t.toLong() * FRAME_SAMPLES)
                if (origin < 0) continue
                val f = (origin / FRAME_SAMPLES).toInt()
                if (f >= activity.frames) continue
                probs[f * columns + column] = activity[f, candidate.speaker]
                probs[f * columns + candidate.speaker] = 0f
            }
        }
        return SpeakerActivity(activity.frames, columns, probs)
    }
}
