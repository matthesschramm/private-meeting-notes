package dev.matthes.privatemeetingnotes.importing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class ResamplerTest {
    private fun tone(rate: Int, hz: Double, seconds: Double) =
        FloatArray((rate * seconds).toInt()) { (0.5 * sin(2 * PI * hz * it / rate)).toFloat() }

    /** Resamples in uneven blocks, as decoders deliver audio. */
    private fun resample(input: FloatArray, from: Int, to: Int): FloatArray {
        val r = Resampler(from, to)
        val out = ArrayList<Float>()
        var i = 0
        var block = 1000
        while (i < input.size) {
            val end = minOf(input.size, i + block)
            r.process(input.copyOfRange(i, end)).forEach { out += it }
            i = end
            block = if (block == 1000) 4093 else 1000
        }
        r.flush().forEach { out += it }
        return out.toFloatArray()
    }

    private fun rms(x: FloatArray, from: Int, to: Int): Double =
        sqrt((from until to).sumOf { x[it].toDouble() * x[it] } / (to - from))

    @Test
    fun keepsLengthAndToneWhenDownsampling() {
        val input = tone(44_100, 1_000.0, 2.0)
        val out = resample(input, 44_100, 16_000)
        assertTrue("length ${out.size}", abs(out.size - 32_000) <= 2)
        val expected = tone(16_000, 1_000.0, 2.0)
        val error = (2_000 until 30_000).maxOf { abs(out[it] - expected[it]) }
        assertTrue("max error $error", error < 0.01)
    }

    @Test
    fun removesFrequenciesAboveTheNewNyquist() {
        val out = resample(tone(48_000, 11_000.0, 1.0), 48_000, 16_000)
        val level = rms(out, 1_000, out.size - 1_000)
        assertTrue("an 11 kHz tone should not alias into 16 kHz audio (rms $level)", level < 0.005)
    }

    @Test
    fun upsamplingKeepsTheTone() {
        val out = resample(tone(8_000, 440.0, 1.0), 8_000, 16_000)
        assertEquals(16_000.0, out.size.toDouble(), 2.0)
        assertEquals(0.5 / sqrt(2.0), rms(out, 1_000, 15_000), 0.01)
    }
}
