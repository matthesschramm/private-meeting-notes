package dev.matthes.privatemeetingnotes.diarization

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Nemotron's arrival-order speaker cache (transformers' Nemotron3DiarizationSpeakerCache, offline settings, batch 1).
 *
 * Each chunk is encoded together with a long-term cache of representative frames per speaker and a short FIFO of
 * recent frames. Frames leave the FIFO into the cache; when the cache outgrows its budget, it is compressed by
 * keeping each speaker's highest-scoring frames plus one learned silence frame per speaker.
 */
internal class SpeakerCache(private val silence: FloatArray) {
    private var embeds: List<FloatArray> = emptyList()
    private var probs: List<FloatArray> = emptyList()
    private var fifo: List<FloatArray> = emptyList()
    private var compressed = false

    private val budget = Nemotron.CACHE / Nemotron.SPEAKERS - Nemotron.SILENCE_FRAMES
    private val minPositive = (budget * Nemotron.MIN_POS_RATE).toInt()
    private val strongCount = (budget * Nemotron.STRONG_RATE).toInt()
    private val weakCount = (budget * Nemotron.WEAK_RATE).toInt()

    /** Frames that go in front of each new chunk: the cache, then the FIFO. */
    fun context(): List<FloatArray> = embeds + fifo

    /**
     * [input] is what the encoder saw (context, chunk, right context), [pooled] the speaker probabilities per input
     * frame, and [chunkFrames] the chunk's length without its right context.
     */
    fun update(input: List<FloatArray>, pooled: List<FloatArray>, chunkFrames: Int) {
        val cached = embeds.size
        val chunkStart = cached + fifo.size
        var fifoEmbeds = fifo + input.subList(chunkStart, chunkStart + chunkFrames)
        val fifoLength = fifoEmbeds.size
        val popped = if (fifoLength > Nemotron.FIFO) {
            min(max(Nemotron.UPDATE_PERIOD, fifoLength - Nemotron.FIFO), fifoLength)
        } else {
            0
        }
        if (popped > 0) {
            val fifoProbs = pooled.subList(cached, cached + fifoLength)
            // Until the first compression, the cache's probabilities are re-read from this step's output.
            val stored = if (compressed) probs else pooled.subList(0, cached)
            var cacheEmbeds = embeds + fifoEmbeds.subList(0, popped)
            var cacheProbs = stored + fifoProbs.subList(0, popped)
            fifoEmbeds = fifoEmbeds.subList(popped, fifoLength)
            if (cacheEmbeds.size > Nemotron.CACHE) {
                val kept = compress(cacheEmbeds, cacheProbs)
                cacheEmbeds = kept.first
                cacheProbs = kept.second
                compressed = true
            }
            embeds = cacheEmbeds.toList()
            probs = cacheProbs.toList()
        }
        fifo = fifoEmbeds.toList()
    }

    private fun compress(embeds: List<FloatArray>, probs: List<FloatArray>): Pair<List<FloatArray>, List<FloatArray>> {
        val frames = probs.size
        val scores = frameScores(probs)
        for (t in Nemotron.CACHE until frames) {
            for (s in 0 until Nemotron.SPEAKERS) scores[t][s] += Nemotron.LATEST_BOOST
        }
        boost(scores, strongCount, -2 * LOG_HALF)
        boost(scores, weakCount, -LOG_HALF)

        // Rank every (speaker, frame) pair, with each speaker's silence slot (frame index == frames) always kept.
        val scored = frames + Nemotron.SILENCE_FRAMES
        val candidates = ArrayList<Pair<Int, Double>>(Nemotron.SPEAKERS * scored)
        for (s in 0 until Nemotron.SPEAKERS) {
            for (t in 0 until scored) {
                candidates += Pair(s * scored + t, if (t < frames) scores[t][s] else Double.POSITIVE_INFINITY)
            }
        }
        candidates.sortWith(compareByDescending<Pair<Int, Double>> { it.second }.thenBy { it.first })
        val unused = scored * Nemotron.SPEAKERS
        val picked = candidates.take(Nemotron.CACHE)
            .map { if (it.second == Double.NEGATIVE_INFINITY) unused else it.first }
            .sorted()

        val zero = FloatArray(Nemotron.SPEAKERS)
        val keptEmbeds = ArrayList<FloatArray>(picked.size)
        val keptProbs = ArrayList<FloatArray>(picked.size)
        for (index in picked) {
            val frame = if (index == unused) frames else min(index % scored, frames)
            keptEmbeds += if (frame == frames) silence else embeds[frame]
            keptProbs += if (frame == frames) zero else probs[frame]
        }
        return Pair(keptEmbeds, keptProbs)
    }

    /** How well each frame represents each speaker; -infinity where it must not be kept for that speaker. */
    private fun frameScores(probs: List<FloatArray>): Array<DoubleArray> {
        val frames = probs.size
        val scores = Array(frames) { DoubleArray(Nemotron.SPEAKERS) }
        val positive = IntArray(Nemotron.SPEAKERS)
        val logComplement = DoubleArray(Nemotron.SPEAKERS)
        for (t in 0 until frames) {
            val p = probs[t]
            var sumLogComplement = 0.0
            for (s in 0 until Nemotron.SPEAKERS) {
                logComplement[s] = ln(max(1 - p[s].toDouble(), Nemotron.PRED_THRESHOLD))
                sumLogComplement += logComplement[s]
            }
            for (s in 0 until Nemotron.SPEAKERS) {
                var score = ln(max(p[s].toDouble(), Nemotron.PRED_THRESHOLD)) - logComplement[s] + sumLogComplement - LOG_HALF
                if (!(p[s] > 0.5f)) score = Double.NEGATIVE_INFINITY
                scores[t][s] = score
                if (score > 0) positive[s]++
            }
        }
        for (t in 0 until frames) {
            for (s in 0 until Nemotron.SPEAKERS) {
                if (!(scores[t][s] > 0) && probs[t][s] > 0.5f && positive[s] >= minPositive) {
                    scores[t][s] = Double.NEGATIVE_INFINITY
                }
            }
        }
        return scores
    }

    /** Adds [amount] to each speaker's [count] best frames. */
    private fun boost(scores: Array<DoubleArray>, count: Int, amount: Double) {
        val frames = scores.size
        for (s in 0 until Nemotron.SPEAKERS) {
            val order = (0 until frames).sortedWith(compareByDescending<Int> { scores[it][s] }.thenBy { it })
            for (i in 0 until min(count, frames)) scores[order[i]][s] += amount
        }
    }

    private companion object {
        val LOG_HALF = ln(0.5)
    }
}
