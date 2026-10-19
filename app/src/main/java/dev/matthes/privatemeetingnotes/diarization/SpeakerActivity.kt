package dev.matthes.privatemeetingnotes.diarization

import kotlinx.serialization.Serializable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One speaker's continuous stretch of speech. */
@Serializable
data class Turn(val speaker: Int, val startMs: Long, val endMs: Long)

/** Probability that each speaker is talking, every 10 ms. */
class SpeakerActivity(val frames: Int, val speakers: Int, val probs: FloatArray) {
    init {
        require(probs.size == frames * speakers) { "Expected $frames x $speakers probabilities" }
    }

    operator fun get(frame: Int, speaker: Int): Float = probs[frame * speakers + speaker]

    val durationMs: Long get() = frames.toLong() * Nemotron.FRAME_MS

    /** Frames in which [speaker] is more likely talking than not. */
    fun activeFrames(speaker: Int): Int = (0 until frames).count { this[it, speaker] > 0.5f }

    /**
     * Per-speaker stretches of speech: thresholded, median-filtered against flicker, gaps shorter than [minGapMs]
     * bridged and stretches shorter than [minSpeechMs] dropped.
     */
    fun turns(threshold: Float = 0.5f, medianFrames: Int = 11, minGapMs: Long = 200, minSpeechMs: Long = 200): List<Turn> {
        val turns = mutableListOf<Turn>()
        for (s in 0 until speakers) {
            val active = BooleanArray(frames) { this[it, s] > threshold }
            val smooth = medianFilter(active, medianFrames)
            val runs = mutableListOf<LongArray>()
            var on = -1
            for (t in 0..frames) {
                val speaking = t < frames && smooth[t]
                if (speaking && on < 0) on = t
                if (!speaking && on >= 0) {
                    runs += longArrayOf(on.toLong() * Nemotron.FRAME_MS, t.toLong() * Nemotron.FRAME_MS)
                    on = -1
                }
            }
            val merged = mutableListOf<LongArray>()
            for (run in runs) {
                val last = merged.lastOrNull()
                if (last != null && run[0] - last[1] < minGapMs) last[1] = run[1] else merged += run
            }
            merged.filter { it[1] - it[0] >= minSpeechMs }.forEach { turns += Turn(s, it[0], it[1]) }
        }
        return turns.sortedWith(compareBy({ it.startMs }, { it.speaker }))
    }

    /** A copy with one speaker per entry of [groups], whose probability is the highest among that group's members. */
    fun regrouped(groups: List<List<Int>>): SpeakerActivity {
        val out = FloatArray(frames * groups.size)
        for (t in 0 until frames) {
            for ((g, members) in groups.withIndex()) {
                var p = 0f
                for (s in members) p = maxOf(p, this[t, s])
                out[t * groups.size + g] = p
            }
        }
        return SpeakerActivity(frames, groups.size, out)
    }

    fun write(file: File) {
        val tmp = File(file.path + ".tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(frames)
            out.writeInt(speakers)
            val bytes = ByteBuffer.allocate(probs.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            bytes.asFloatBuffer().put(probs)
            out.write(bytes.array())
        }
        tmp.renameTo(file)
    }

    companion object {
        private const val MAGIC = 0x53504b41

        fun read(file: File): SpeakerActivity = DataInputStream(file.inputStream().buffered()).use { input ->
            require(input.readInt() == MAGIC) { "Not a speaker activity file" }
            val frames = input.readInt()
            val speakers = input.readInt()
            val bytes = ByteArray(frames * speakers * 4)
            input.readFully(bytes)
            val probs = FloatArray(frames * speakers)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(probs)
            SpeakerActivity(frames, speakers, probs)
        }

        private fun medianFilter(values: BooleanArray, width: Int): BooleanArray {
            if (width <= 1) return values
            val half = width / 2
            val out = BooleanArray(values.size)
            var count = 0
            for (i in 0 until minOf(half, values.size)) if (values[i]) count++
            for (i in values.indices) {
                val enter = i + half
                if (enter < values.size && values[enter]) count++
                val leave = i - half - 1
                if (leave >= 0 && values[leave]) count--
                val size = minOf(values.size - 1, i + half) - maxOf(0, i - half) + 1
                out[i] = count * 2 > size
            }
            return out
        }
    }
}
