package dev.matthes.privatemeetingnotes.diarization

import dev.matthes.privatemeetingnotes.audio.AudioSource
import dev.matthes.privatemeetingnotes.audio.FloatAudio
import dev.matthes.privatemeetingnotes.audio.Spliced
import dev.matthes.privatemeetingnotes.speakers.Embedder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.math.roundToInt

class ExtraSpeakersTest {
    private val second = 16_000

    /** Each voice is a constant sample value: A is 1, B is 2 and C is 3, with silence as 0. */
    private val audio = FloatAudio(FloatArray(60 * second) { i ->
        when (i / second) {
            in 0 until 10, in 20 until 30 -> 1f
            in 10 until 20, in 30 until 40 -> 2f
            else -> 3f
        }
    })

    /** Like Nemotron on few voices: each voice its own speaker, numbered by first appearance. */
    private val diarize = { source: AudioSource ->
        val frames = (source.sampleCount / 160).toInt()
        val samples = source.read(0, source.sampleCount)
        val seen = mutableListOf<Int>()
        val probs = FloatArray(frames * 8)
        for (f in 0 until frames) {
            val voice = samples[f * 160].roundToInt()
            if (voice == 0) continue
            if (voice !in seen) seen += voice
            probs[f * 8 + seen.indexOf(voice)] = 1f
        }
        SpeakerActivity(frames, 8, probs)
    }

    private val embedder = Embedder { samples ->
        FloatArray(4).also { it[samples.average().roundToInt()] = 1f }
    }

    /** The first pass: A and B share speaker 0, C is speaker 1. */
    private val shared = SpeakerActivity(6_000, 2, FloatArray(6_000 * 2) { i -> if ((i / 2 < 4_000) == (i % 2 == 0)) 1f else 0f })

    @Test
    fun aSpeakerHoldingTwoVoicesIsSplitUpToTheHeadCount() {
        val split = ExtraSpeakers.split(shared, audio, 3, diarize, embedder)
        assertEquals(3, split.speakers)
        for ((second, speaker) in listOf(5 to 0, 15 to 2, 25 to 0, 35 to 2, 45 to 1)) {
            assertEquals("at $second s", speaker, (0 until 3).single { split[second * 100, it] > 0.5f })
        }
    }

    @Test
    fun enoughSpeakersAlreadyLeavesTheActivityAsItIs() {
        assertSame(shared, ExtraSpeakers.split(shared, audio, 2, diarize, embedder))
    }

    @Test
    fun splicedAudioPlaysTheStretchesWithGapsAndMapsBack() {
        val ramp = FloatAudio(FloatArray(1_000) { it.toFloat() })
        val spliced = Spliced(ramp, listOf(100L until 200L, 500L until 550L), 10)
        assertEquals(160L, spliced.sampleCount)
        val expected = FloatArray(20) { i -> if (i < 5) 195f + i else if (i < 15) 0f else 500f + i - 15 }
        assertArrayEquals(expected, spliced.read(95, 115), 0f)
        assertEquals(100L, spliced.original(0))
        assertEquals(-1L, spliced.original(105))
        assertEquals(500L, spliced.original(110))
    }
}
