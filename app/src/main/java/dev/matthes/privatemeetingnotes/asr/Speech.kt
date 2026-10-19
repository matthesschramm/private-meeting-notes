package dev.matthes.privatemeetingnotes.asr

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import dev.matthes.privatemeetingnotes.audio.AudioSource
import dev.matthes.privatemeetingnotes.audio.SAMPLE_RATE
import kotlinx.serialization.Serializable
import java.io.Closeable
import java.io.File

/** A word with its time in the recording. */
@Serializable
data class Word(val text: String, val startMs: Long, val endMs: Long)

/** A stretch of speech found by voice activity detection, in samples. */
@Serializable
data class SpeechRegion(val start: Long, val end: Long)

/** Silero voice activity detection over a whole recording. */
class SpeechDetector(model: File) : Closeable {
    private val vad = Vad(
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = model.path,
                threshold = 0.5f,
                minSilenceDuration = 0.5f,
                minSpeechDuration = 0.25f,
                windowSize = 512,
                maxSpeechDuration = 25f,
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = 1,
        ),
    )

    fun detect(audio: AudioSource, onProgress: (Float) -> Unit = {}): List<SpeechRegion> {
        val regions = mutableListOf<SpeechRegion>()
        val window = FloatArray(WINDOW)
        var position = 0L
        while (position < audio.sampleCount) {
            val end = minOf(position + BLOCK, audio.sampleCount)
            val block = audio.read(position, end)
            // sherpa-onnx treats each call as one unit: a call containing any speech marks speech as starting
            // near its end. One model window per call keeps the start of each region accurate.
            var offset = 0
            while (offset < block.size) {
                val count = minOf(WINDOW, block.size - offset)
                if (count == WINDOW) {
                    block.copyInto(window, 0, offset, offset + WINDOW)
                    vad.acceptWaveform(window)
                } else {
                    vad.acceptWaveform(block.copyOfRange(offset, offset + count))
                }
                offset += count
            }
            drain(regions)
            position = end
            onProgress(position.toFloat() / audio.sampleCount)
        }
        vad.flush()
        drain(regions)
        vad.reset()
        return regions.flatMap { splitLong(it, audio) }
    }

    /**
     * Splits regions longer than 20 s at their quietest point between 8 and 20 s in, which is a pause between
     * words. sherpa-onnx's maximum speech duration is only a hint, and on long inputs Parakeet's duration
     * predictions can skip whole sentences.
     */
    private fun splitLong(region: SpeechRegion, audio: AudioSource): List<SpeechRegion> {
        val out = mutableListOf<SpeechRegion>()
        var start = region.start
        while (region.end - start > MAX_REGION) {
            val from = start + MIN_PIECE
            val samples = audio.read(from, start + MAX_REGION)
            var quietest = 0
            var lowest = Double.MAX_VALUE
            var frame = 0
            while (frame + ENERGY_FRAME <= samples.size) {
                var energy = 0.0
                for (i in frame until frame + ENERGY_FRAME) energy += samples[i] * samples[i]
                if (energy < lowest) {
                    lowest = energy
                    quietest = frame
                }
                frame += ENERGY_HOP
            }
            val split = from + quietest + ENERGY_FRAME / 2
            out += SpeechRegion(start, split)
            start = split
        }
        out += SpeechRegion(start, region.end)
        return out
    }

    private fun drain(into: MutableList<SpeechRegion>) {
        while (!vad.empty()) {
            val segment = vad.front()
            into += SpeechRegion(segment.start.toLong(), segment.start.toLong() + segment.samples.size)
            vad.pop()
        }
    }

    override fun close() = vad.release()

    private companion object {
        const val WINDOW = 512
        const val BLOCK = 4L * SAMPLE_RATE
        const val MAX_REGION = 20L * SAMPLE_RATE
        const val MIN_PIECE = 8L * SAMPLE_RATE
        const val ENERGY_FRAME = SAMPLE_RATE * 3 / 100
        const val ENERGY_HOP = SAMPLE_RATE / 100
    }
}

/**
 * Parakeet TDT 0.6B v2 through the sherpa-onnx offline recogniser, with word timings from token timestamps.
 * Greedy search only: sherpa-onnx's beam search for this model, which its hotwords need, dropped whole sentences.
 */
class Transcriber(modelDir: File, threads: Int) : Closeable {
    private val recognizer = OfflineRecognizer(
        config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 128, dither = 0f),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = File(modelDir, "encoder.int8.onnx").path,
                    decoder = File(modelDir, "decoder.int8.onnx").path,
                    joiner = File(modelDir, "joiner.int8.onnx").path,
                ),
                tokens = File(modelDir, "tokens.txt").path,
                numThreads = threads,
                modelType = "nemo_transducer",
            ),
            decodingMethod = "greedy_search",
        ),
    )

    /** Transcribes [samples], which start [offsetMs] into the recording. */
    fun transcribe(samples: FloatArray, offsetMs: Long): List<Word> {
        val result = decode(samples)
        return wordsFromTokens(result.tokens, result.timestamps, result.durations, offsetMs, samples.size * 1000L / SAMPLE_RATE)
    }

    fun decode(samples: FloatArray): OfflineRecognizerResult {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            return recognizer.getResult(stream)
        } finally {
            stream.release()
        }
    }

    override fun close() = recognizer.release()

    companion object {
        private const val FALLBACK_TOKEN_MS = 80L

        /**
         * Joins SentencePiece tokens into words. A token that starts with a space or "▁" begins a word; anything
         * else, punctuation included, continues the current one.
         */
        fun wordsFromTokens(
            tokens: Array<String>,
            timestamps: FloatArray,
            durations: FloatArray?,
            offsetMs: Long,
            lengthMs: Long,
        ): List<Word> {
            val words = mutableListOf<Word>()
            val text = StringBuilder()
            var start = 0L
            var end = 0L
            var pendingBreak = false
            fun finish() {
                if (text.isNotBlank()) words += Word(text.toString(), offsetMs + start, offsetMs + maxOf(end, start + 10))
                text.clear()
            }
            for (i in tokens.indices) {
                val token = tokens[i]
                val startsWord = token.startsWith(" ") || token.startsWith("\u2581")
                val piece = token.trimStart(' ', '\u2581')
                if (piece.isEmpty()) {
                    pendingBreak = pendingBreak || startsWord
                    continue
                }
                val tokenStart = (timestamps.getOrElse(i) { 0f } * 1000).toLong()
                val tokenLength = durations?.getOrNull(i)?.takeIf { it > 0f }?.let { (it * 1000).toLong() } ?: FALLBACK_TOKEN_MS
                if ((startsWord || pendingBreak) && !isPunctuation(piece)) finish()
                if (text.isEmpty()) start = tokenStart
                pendingBreak = false
                text.append(piece)
                end = minOf(tokenStart + tokenLength, lengthMs)
            }
            finish()
            return words
        }

        private fun isPunctuation(piece: String) = piece.all { !it.isLetterOrDigit() }
    }
}
