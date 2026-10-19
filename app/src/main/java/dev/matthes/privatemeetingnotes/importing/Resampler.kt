package dev.matthes.privatemeetingnotes.importing

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Streaming windowed-sinc (Blackman) resampler for mono audio. When downsampling, the cutoff drops below the new
 * Nyquist frequency so higher frequencies are filtered out instead of aliasing.
 *
 * Output sample n sits at input position n * [down] / [up]. Its fractional part takes only [up] values, so the
 * filter taps for each are computed once, and every output sample is a single dot product.
 */
class Resampler(private val inputRate: Int, private val outputRate: Int) {
    private val cutoff = min(1.0, outputRate.toDouble() / inputRate) * 0.95
    private val halfWidth = ceil(ZERO_CROSSINGS / cutoff).toInt()
    private val taps = 2 * halfWidth
    private val up = outputRate / gcd(inputRate, outputRate)
    private val down = inputRate / gcd(inputRate, outputRate)
    private val filters = Array(up) { phase ->
        FloatArray(taps) { j -> (kernel((phase.toDouble() / up + halfWidth - 1 - j) * cutoff) * cutoff).toFloat() }
    }

    // Starts as if preceded by silence, so the first samples are filtered like the rest.
    private var buffer = FloatArray(halfWidth)
    /** Whole input samples from the start of [buffer] to the next output sample's position. */
    private var base = halfWidth
    /** The rest of that position, in steps of 1 / [up]. */
    private var phase = 0

    fun process(input: FloatArray): FloatArray {
        if (inputRate == outputRate) return input
        buffer += input
        return drain(Int.MAX_VALUE)
    }

    /** Output for the samples still buffered, padding the end with silence. */
    fun flush(): FloatArray {
        if (inputRate == outputRate) return FloatArray(0)
        val inputEnd = buffer.size
        buffer += FloatArray(halfWidth + 1)
        return drain(inputEnd)
    }

    /** Every output sample whose taps are all buffered and whose position is before [limit]. */
    private fun drain(limit: Int): FloatArray {
        var count = 0
        var b = base
        var p = phase
        while (b < limit && b + halfWidth < buffer.size) {
            count++
            p += down
            b += p / up
            p %= up
        }
        val out = FloatArray(count)
        for (i in 0 until count) {
            val filter = filters[phase]
            val start = base - halfWidth + 1
            var sum = 0f
            for (j in 0 until taps) sum += buffer[start + j] * filter[j]
            out[i] = sum
            phase += down
            base += phase / up
            phase %= up
        }
        val keepFrom = base - halfWidth
        if (keepFrom > 0) {
            buffer = buffer.copyOfRange(keepFrom, buffer.size)
            base -= keepFrom
        }
        return out
    }

    private companion object {
        const val ZERO_CROSSINGS = 8

        fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

        fun kernel(t: Double): Double {
            val a = abs(t)
            if (a >= ZERO_CROSSINGS) return 0.0
            val sinc = if (a == 0.0) 1.0 else sin(PI * a) / (PI * a)
            val n = (a / ZERO_CROSSINGS + 1) / 2
            return sinc * (0.42 - 0.5 * cos(2 * PI * n) + 0.08 * cos(4 * PI * n))
        }
    }
}
