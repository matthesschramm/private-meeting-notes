package dev.matthes.privatemeetingnotes.diarization

import dev.matthes.privatemeetingnotes.asr.SpeechRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackDiarizerTest {
    private val second = 16_000L
    private val minute = 60 * second
    private val max = 60 * minute
    private val slack = minute

    /** Ten seconds of speech, then a one-second pause, over and over. */
    private fun steadyTalk(length: Long) = (0 until length step 11 * second).map { SpeechRegion(it, minOf(length, it + 10 * second)) }

    @Test
    fun aRecordingUpToAnHourIsOneChunk() {
        assertEquals(listOf(0L, 30 * minute), FallbackDiarizer.cuts(30 * minute, steadyTalk(30 * minute), max, slack))
        assertEquals(2, FallbackDiarizer.cuts(58 * minute, steadyTalk(58 * minute), max, slack).size)
    }

    @Test
    fun aLongRecordingIsCutIntoTheFewestChunksInPauses() {
        val length = 189 * minute
        val speech = steadyTalk(length)
        val cuts = FallbackDiarizer.cuts(length, speech, max, slack)
        assertEquals(5, cuts.size)
        assertEquals(0L, cuts.first())
        assertEquals(length, cuts.last())
        cuts.zipWithNext().forEach { (start, end) -> assertTrue(end - start <= max) }
        for ((i, cut) in cuts.drop(1).dropLast(1).withIndex()) {
            assertTrue(kotlin.math.abs(cut - length * (i + 1) / 4) <= slack)
            assertTrue("cut at $cut is mid-speech", speech.none { cut > it.start && cut < it.end })
        }
    }

    @Test
    fun theLongestPauseNearTheEvenSplitWins() {
        val length = 100 * minute
        val speech = listOf(
            SpeechRegion(0, 49 * minute),
            SpeechRegion(49 * minute + second / 2, 50 * minute + 20 * second),
            SpeechRegion(50 * minute + 23 * second, length),
        )
        assertEquals(listOf(0L, 50 * minute + 21 * second + second / 2, length), FallbackDiarizer.cuts(length, speech, max, slack))
    }

    @Test
    fun withoutAPauseTheCutIsTheEvenSplit() {
        val length = 100 * minute
        assertEquals(listOf(0L, 50 * minute, length), FallbackDiarizer.cuts(length, listOf(SpeechRegion(0, length)), max, slack))
    }
}
