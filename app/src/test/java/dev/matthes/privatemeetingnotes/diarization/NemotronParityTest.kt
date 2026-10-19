package dev.matthes.privatemeetingnotes.diarization

import dev.matthes.privatemeetingnotes.audio.WavFile
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/**
 * Checks the Kotlin Nemotron port against the reference JavaScript pipeline on the synthetic meetings.
 * Needs models/ and the reference outputs from `node .tooling/parity/reference.mjs test-data/synthetic/(set)/(file).wav`.
 */
class NemotronParityTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }
    private val models = File(root, "models/nemotron-3-diarization")

    @Test
    fun matchesReferenceOnSyntheticMeetings() {
        assumeTrue("models not downloaded", File(models, "step_int8.onnx").exists())
        val clips = File(root, "test-data/synthetic").listFiles().orEmpty().sorted()
            .flatMap { set -> listOf("clean", "room").map { File(set, "$it.wav") } }
            .filter { it.exists() && referenceOf(it).exists() }
        assumeTrue("no reference outputs", clips.isNotEmpty())

        val (diarizer, network) = NemotronDiarizer.open(models, threads = 4)
        network.use {
            for (wav in clips) {
                val ours = WavFile(wav).use { diarizer.run(it) }
                val report = Parity.compare(ours.probs, NemotronDiarizer.readFloats(referenceOf(wav)))
                println("${wav.parentFile?.name}/${wav.name}: $report")
                assertTrue("${wav.name}: $report", report.passes)
            }
        }
    }

    private fun referenceOf(wav: File) = File(wav.path.removeSuffix(".wav") + ".reference.f32")
}

/** The plan's pass mark: at least 99.9% per-frame agreement after thresholding, boundaries within 20 ms. */
data class ParityReport(
    val frames: Int,
    val agreement: Double,
    val maxProbDiff: Double,
    val boundaries: Int,
    val boundariesOff: Int,
    val maxShiftMs: Int,
) {
    val passes: Boolean get() = agreement >= 0.999 && boundariesOff == 0

    override fun toString() = "frames $frames, agreement ${"%.4f".format(agreement * 100)}%, " +
        "max prob diff ${"%.4f".format(maxProbDiff)}, boundaries $boundaries with $boundariesOff beyond 20 ms " +
        "(max shift $maxShiftMs ms)"
}

object Parity {
    private const val SPEAKERS = 8
    private const val FRAME_MS = 10
    private const val TOLERANCE_MS = 20
    private const val MIN_RUN_FRAMES = 5

    fun compare(ours: FloatArray, reference: FloatArray): ParityReport {
        require(ours.size == reference.size) { "length differs: ${ours.size} vs ${reference.size}" }
        val frames = ours.size / SPEAKERS
        var agree = 0
        var maxDiff = 0.0
        for (i in ours.indices) {
            if ((ours[i] > 0.5f) == (reference[i] > 0.5f)) agree++
            maxDiff = max(maxDiff, abs(ours[i] - reference[i]).toDouble())
        }
        var boundaries = 0
        var off = 0
        var maxShift = 0
        for (s in 0 until SPEAKERS) {
            val ourEdges = edges(ours, frames, s)
            for (edge in edges(reference, frames, s)) {
                boundaries++
                val shift = ourEdges.filter { it.second == edge.second }.minOfOrNull { abs(it.first - edge.first) * FRAME_MS }
                if (shift == null || shift > TOLERANCE_MS) off++ else maxShift = max(maxShift, shift)
            }
        }
        return ParityReport(frames, agree.toDouble() / ours.size, maxDiff, boundaries, off, maxShift)
    }

    /** Onsets (true) and offsets (false) of a speaker's runs above 0.5 lasting at least 50 ms. */
    private fun edges(probs: FloatArray, frames: Int, speaker: Int): List<Pair<Int, Boolean>> {
        val out = mutableListOf<Pair<Int, Boolean>>()
        var on = -1
        for (t in 0..frames) {
            val active = t < frames && probs[t * SPEAKERS + speaker] > 0.5f
            if (active && on < 0) on = t
            if (!active && on >= 0) {
                if (t - on >= MIN_RUN_FRAMES) {
                    out += Pair(on, true)
                    out += Pair(t, false)
                }
                on = -1
            }
        }
        return out
    }
}
