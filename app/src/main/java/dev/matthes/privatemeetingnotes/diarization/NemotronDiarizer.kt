package dev.matthes.privatemeetingnotes.diarization

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import dev.matthes.privatemeetingnotes.audio.AudioSource
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CancellationException
import kotlin.math.exp
import kotlin.math.min

/** The two ONNX graphs of the Nemotron conversion. */
interface DiarizationNetwork : Closeable {
    /** Log-mel features [frames x 128] to embeddings, one per 8 frames, each [512]. */
    fun embed(features: FloatArray, frames: Int): List<FloatArray>

    /** Embeddings [T][512] to speaker logits [8T x 8], row-major. */
    fun step(embeds: List<FloatArray>): FloatArray
}

/**
 * NVIDIA Nemotron 3 Diarization in offline mode: who speaks when, for up to 8 speakers, numbered in order of first
 * appearance. Audio is read one chunk at a time, so recordings of any length run in constant memory.
 */
class NemotronDiarizer(private val network: DiarizationNetwork, melFilters: FloatArray, private val silence: FloatArray) {
    private val mel = MelFrontEnd(melFilters)

    fun run(audio: AudioSource, onProgress: (Float) -> Unit = {}, isCancelled: () -> Boolean = { false }): SpeakerActivity {
        val length = audio.sampleCount
        val frames = (length / Nemotron.HOP).toInt()
        val embedCount = (frames + Nemotron.SUBSAMPLING - 1) / Nemotron.SUBSAMPLING
        val perEmbed = Nemotron.SUBSAMPLING * Nemotron.SPEAKERS
        val cache = SpeakerCache(silence)
        val out = FloatArray(embedCount * perEmbed)
        var written = 0
        var start = 0
        while (start < embedCount) {
            if (isCancelled()) throw CancellationException("Diarization cancelled")
            val end = min(start + Nemotron.CHUNK, embedCount)
            val chunk = embedRange(audio, start, min(end + Nemotron.RIGHT_CONTEXT, embedCount), frames)
            val context = cache.context()
            val input = context + chunk
            val logits = network.step(input)
            cache.update(input, pool(logits, input.size), end - start)
            for (i in context.size * perEmbed until (context.size + end - start) * perEmbed) {
                out[written++] = sigmoid(logits[i]).toFloat()
            }
            start = end
            onProgress(end.toFloat() / embedCount)
        }
        return SpeakerActivity(frames, Nemotron.SPEAKERS, out.copyOf(frames * Nemotron.SPEAKERS))
    }

    // Each embedding depends only on its own 8 frames, so embedding a chunk at a time matches embedding the file.
    private fun embedRange(audio: AudioSource, e0: Int, e1: Int, frames: Int): List<FloatArray> {
        val f0 = e0 * Nemotron.SUBSAMPLING
        val f1 = min(e1 * Nemotron.SUBSAMPLING, frames)
        val (s0, s1) = MelFrontEnd.span(f0, f1, audio.sampleCount)
        val features = mel.frames(audio.read(s0, s1), s0, audio.sampleCount, f0, f1)
        return network.embed(features, f1 - f0)
    }

    /** Sigmoid, averaged over each embedding's 8 frames, accumulated in float32 as the reference does. */
    private fun pool(logits: FloatArray, count: Int): List<FloatArray> = List(count) { t ->
        val p = FloatArray(Nemotron.SPEAKERS)
        for (k in 0 until Nemotron.SUBSAMPLING) {
            for (s in 0 until Nemotron.SPEAKERS) {
                p[s] = (p[s] + sigmoid(logits[(t * Nemotron.SUBSAMPLING + k) * Nemotron.SPEAKERS + s])).toFloat()
            }
        }
        for (s in 0 until Nemotron.SPEAKERS) p[s] = p[s] / Nemotron.SUBSAMPLING
        p
    }

    private fun sigmoid(x: Float): Double = 1.0 / (1.0 + exp(-x.toDouble()))

    companion object {
        fun open(modelDir: File, threads: Int, stepModel: String = "step_int8.onnx"): Pair<NemotronDiarizer, DiarizationNetwork> {
            val network = OrtDiarizationNetwork(File(modelDir, "embed.onnx"), File(modelDir, stepModel), threads)
            val diarizer = NemotronDiarizer(
                network,
                readFloats(File(modelDir, "mel_filters.bin")),
                readFloats(File(modelDir, "silence_embeds.bin")),
            )
            return Pair(diarizer, network)
        }

        fun readFloats(file: File): FloatArray {
            val buffer = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            return FloatArray(buffer.remaining()).also { buffer.get(it) }
        }
    }
}

class OrtDiarizationNetwork(embedModel: File, stepModel: File, threads: Int) : DiarizationNetwork {
    private val env = OrtEnvironment.getEnvironment().apply { setTelemetry(false) }
    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads)
        setInterOpNumThreads(1)
    }
    private val embedSession = env.createSession(embedModel.path, options)
    private val stepSession = env.createSession(stepModel.path, options)

    override fun embed(features: FloatArray, frames: Int): List<FloatArray> {
        val shape = longArrayOf(1, frames.toLong(), Nemotron.MELS.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(features), shape).use { input ->
            embedSession.run(mapOf("features" to input)).use { result ->
                val tensor = result.get(0) as OnnxTensor
                val count = tensor.info.shape[1].toInt()
                val data = tensor.floatBuffer
                return List(count) { FloatArray(Nemotron.HIDDEN).also { data.get(it) } }
            }
        }
    }

    override fun step(embeds: List<FloatArray>): FloatArray {
        val data = FloatBuffer.allocate(embeds.size * Nemotron.HIDDEN)
        embeds.forEach { data.put(it) }
        data.flip()
        val shape = longArrayOf(1, embeds.size.toLong(), Nemotron.HIDDEN.toLong())
        OnnxTensor.createTensor(env, data, shape).use { input ->
            stepSession.run(mapOf("embeds" to input)).use { result ->
                val logits = (result.get(0) as OnnxTensor).floatBuffer
                return FloatArray(logits.remaining()).also { logits.get(it) }
            }
        }
    }

    override fun close() {
        embedSession.close()
        stepSession.close()
        options.close()
    }
}
