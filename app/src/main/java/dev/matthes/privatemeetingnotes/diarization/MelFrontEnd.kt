package dev.matthes.privatemeetingnotes.diarization

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Constants of NVIDIA Nemotron 3 Diarization in offline mode (transformers' Nemotron3Diarization defaults). */
internal object Nemotron {
    const val HOP = 160
    const val N_FFT = 512
    const val WINDOW = 400
    const val MELS = 128
    const val PRE_EMPHASIS = 0.97
    const val SUBSAMPLING = 8
    const val HIDDEN = 512
    const val SPEAKERS = 8
    const val CHUNK = 340
    const val RIGHT_CONTEXT = 40
    const val FIFO = 40
    const val UPDATE_PERIOD = 300
    const val CACHE = 264
    const val SILENCE_FRAMES = 1
    const val PRED_THRESHOLD = 0.25
    const val LATEST_BOOST = 0.05
    const val MIN_POS_RATE = 0.5
    const val STRONG_RATE = 0.75
    const val WEAK_RATE = 1.5
    const val FRAME_MS = 10
}

/** Iterative radix-2 FFT for one fixed power-of-two size. */
internal class Fft(private val n: Int) {
    private val levels = Integer.numberOfTrailingZeros(n)
    private val reverse = IntArray(n) { Integer.reverse(it) ushr (32 - levels) }
    private val cos = DoubleArray(n / 2) { cos(2 * PI * it / n) }
    private val sin = DoubleArray(n / 2) { sin(2 * PI * it / n) }

    init {
        require(n > 1 && n and (n - 1) == 0) { "FFT size must be a power of two" }
    }

    /** Forward transform of (re, im) in place. */
    fun transform(re: DoubleArray, im: DoubleArray) {
        for (i in 0 until n) {
            val j = reverse[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var i = 0
            while (i < n) {
                var k = 0
                for (j in 0 until half) {
                    val a = i + j
                    val b = a + half
                    val tr = re[b] * cos[k] + im[b] * sin[k]
                    val ti = -re[b] * sin[k] + im[b] * cos[k]
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                    k += step
                }
                i += size
            }
            size *= 2
        }
    }
}

/**
 * The model's log-mel front end, computed for any range of 10 ms frames straight from the samples it needs:
 * pre-emphasis 0.97, a centred STFT (n_fft 512, symmetric Hann window of 400 centred in 512, hop 160, zero padding),
 * power spectrum, Slaney mel filterbank, log(x + 2^-24), no normalisation.
 */
internal class MelFrontEnd(private val filters: FloatArray) {
    private val bins = Nemotron.N_FFT / 2 + 1
    private val window = DoubleArray(Nemotron.N_FFT).also { w ->
        val offset = (Nemotron.N_FFT - Nemotron.WINDOW) / 2
        for (i in 0 until Nemotron.WINDOW) w[offset + i] = 0.5 - 0.5 * cos(2 * PI * i / (Nemotron.WINDOW - 1))
    }
    private val fft = Fft(Nemotron.N_FFT)
    private val re = DoubleArray(Nemotron.N_FFT)
    private val im = DoubleArray(Nemotron.N_FFT)
    private val power = DoubleArray(bins)
    private val firstBin = IntArray(Nemotron.MELS)
    private val endBin = IntArray(Nemotron.MELS)

    init {
        require(filters.size == Nemotron.MELS * bins) { "Expected a ${Nemotron.MELS} x $bins mel filterbank" }
        for (m in 0 until Nemotron.MELS) {
            firstBin[m] = bins
            for (k in 0 until bins) {
                if (filters[m * bins + k] != 0f) {
                    firstBin[m] = min(firstBin[m], k)
                    endBin[m] = k + 1
                }
            }
        }
    }

    /**
     * Features for frames [f0, f1) of a recording [length] samples long, as [frames x 128] floats.
     * [audio] holds that recording's samples from [base] on, covering at least [span].
     */
    fun frames(audio: FloatArray, base: Long, length: Long, f0: Int, f1: Int): FloatArray {
        val out = FloatArray((f1 - f0) * Nemotron.MELS)
        for (f in f0 until f1) {
            val start = f.toLong() * Nemotron.HOP - Nemotron.N_FFT / 2
            for (i in 0 until Nemotron.N_FFT) {
                re[i] = emphasized(audio, base, length, start + i) * window[i]
                im[i] = 0.0
            }
            fft.transform(re, im)
            for (k in 0 until bins) power[k] = re[k] * re[k] + im[k] * im[k]
            val row = (f - f0) * Nemotron.MELS
            for (m in 0 until Nemotron.MELS) {
                var sum = 0.0
                val filterRow = m * bins
                for (k in firstBin[m] until endBin[m]) sum += filters[filterRow + k] * power[k]
                out[row + m] = ln(sum + GUARD).toFloat()
            }
        }
        return out
    }

    // The reference stores pre-emphasised audio as float32, so each value is rounded to float before use.
    private fun emphasized(audio: FloatArray, base: Long, length: Long, j: Long): Double = when {
        j < 0 -> 0.0
        j == 0L -> audio[(j - base).toInt()].toDouble()
        j < length -> (audio[(j - base).toInt()] - Nemotron.PRE_EMPHASIS * audio[(j - 1 - base).toInt()]).toFloat().toDouble()
        else -> 0.0
    }

    companion object {
        private const val GUARD = 5.9604644775390625E-8

        /** Samples [first, last) that frames [f0, f1) read, in a recording [length] samples long. */
        fun span(f0: Int, f1: Int, length: Long): Pair<Long, Long> = Pair(
            max(0L, f0.toLong() * Nemotron.HOP - Nemotron.N_FFT / 2 - 1),
            min(length, (f1 - 1).toLong() * Nemotron.HOP + Nemotron.N_FFT / 2),
        )
    }
}
