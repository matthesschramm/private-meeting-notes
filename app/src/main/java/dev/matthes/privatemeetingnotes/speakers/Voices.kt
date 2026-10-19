package dev.matthes.privatemeetingnotes.speakers

import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import dev.matthes.privatemeetingnotes.audio.AudioSource
import dev.matthes.privatemeetingnotes.audio.SAMPLE_RATE
import dev.matthes.privatemeetingnotes.diarization.SpeakerActivity
import kotlinx.serialization.Serializable
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

fun interface Embedder {
    /** A unit-length embedding of [samples], or null if they are too short. */
    fun embed(samples: FloatArray): FloatArray?
}

/** WeSpeaker ResNet34 voice embeddings through sherpa-onnx. */
class VoiceEmbedder(model: File, threads: Int) : Embedder, Closeable {
    private val extractor = SpeakerEmbeddingExtractor(
        config = SpeakerEmbeddingExtractorConfig(model = model.path, numThreads = threads),
    )

    override fun embed(samples: FloatArray): FloatArray? {
        val stream = extractor.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            stream.inputFinished()
            if (!extractor.isReady(stream)) return null
            return normalised(extractor.compute(stream))
        } finally {
            stream.release()
        }
    }

    override fun close() = extractor.release()
}

/** A detected speaker's voice: an averaged embedding and how much they spoke. */
@Serializable
data class VoiceProfile(val speaker: Int, val speechMs: Long, val embedding: List<Float>?) {
    fun vector(): FloatArray? = embedding?.toFloatArray()
}

object Voices {
    private const val MIN_SOLO_MS = 1_500L
    private const val FALLBACK_SOLO_MS = 800L
    private const val MAX_PIECE_MS = 10_000L
    private const val PIECES = 8

    /**
     * Averages embeddings of each speaker's longest stretches of speaking alone (up to [PIECES], each 1.5 s or more,
     * else the longest of 0.8 s or more), then normalises.
     */
    fun profiles(audio: AudioSource, activity: SpeakerActivity, embedder: Embedder): List<VoiceProfile> =
        (0 until activity.speakers).map { s ->
            val solo = soloStretches(activity, s)
            val chosen = solo.filter { it.last - it.first >= MIN_SOLO_MS }.sortedByDescending { it.last - it.first }.take(PIECES)
                .ifEmpty { solo.filter { it.last - it.first >= FALLBACK_SOLO_MS }.sortedByDescending { it.last - it.first }.take(1) }
            val embeddings = chosen.mapNotNull { range ->
                val end = minOf(range.last, range.first + MAX_PIECE_MS)
                embedder.embed(audio.read(range.first * SAMPLE_RATE / 1000, end * SAMPLE_RATE / 1000))
            }
            VoiceProfile(s, activity.activeFrames(s) * 10L, if (embeddings.isEmpty()) null else normalised(average(embeddings)).toList())
        }

    /** Stretches, in ms, where [speaker] is active and nobody else is. */
    private fun soloStretches(activity: SpeakerActivity, speaker: Int): List<LongRange> {
        val out = mutableListOf<LongRange>()
        var on = -1
        for (f in 0..activity.frames) {
            val solo = f < activity.frames && activity[f, speaker] > 0.5f &&
                (0 until activity.speakers).none { it != speaker && activity[f, it] > 0.5f }
            if (solo && on < 0) on = f
            if (!solo && on >= 0) {
                out += (on * 10L)..(f * 10L)
                on = -1
            }
        }
        return out
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot
    }

    fun toBytes(vector: FloatArray): ByteArray =
        ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { asFloatBuffer().put(vector) }.array()

    fun fromBytes(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(buffer.remaining()).also { buffer.get(it) }
    }

    private fun average(vectors: List<FloatArray>): FloatArray {
        val out = FloatArray(vectors.first().size)
        for (v in vectors) for (i in v.indices) out[i] += v[i]
        return out
    }
}

internal fun normalised(v: FloatArray): FloatArray {
    var sum = 0f
    for (x in v) sum += x * x
    val norm = sqrt(sum)
    return if (norm == 0f) v else FloatArray(v.size) { v[it] / norm }
}
