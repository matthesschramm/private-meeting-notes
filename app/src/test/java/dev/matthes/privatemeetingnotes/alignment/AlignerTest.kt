package dev.matthes.privatemeetingnotes.alignment

import dev.matthes.privatemeetingnotes.asr.Word
import dev.matthes.privatemeetingnotes.diarization.SpeakerActivity
import org.junit.Assert.assertEquals
import org.junit.Test

class AlignerTest {
    /** Activity where each (speaker, startMs, endMs) span is fully active. */
    private fun activity(durationMs: Long, speakers: Int, vararg spans: Triple<Int, Long, Long>): SpeakerActivity {
        val frames = (durationMs / 10).toInt()
        val probs = FloatArray(frames * speakers)
        for ((s, start, end) in spans) {
            for (f in (start / 10).toInt() until (end / 10).toInt()) probs[f * speakers + s] = 1f
        }
        return SpeakerActivity(frames, speakers, probs)
    }

    /** Words of [text], each 300 ms long with 100 ms gaps, starting at [startMs]. */
    private fun words(text: String, startMs: Long): List<Word> =
        text.split(" ").mapIndexed { i, t -> Word(t, startMs + i * 400L, startMs + i * 400L + 300) }

    @Test
    fun alternatingSpeakersGetTheirOwnUtterances() {
        val first = words("Shall we start with the budget?", 0)
        val second = words("Yes, I have the numbers here.", 3_000)
        val act = activity(6_000, 2, Triple(0, 0, 2_400), Triple(1, 3_000, 5_400))
        val result = Aligner().align(first + second, act)
        assertEquals(listOf(0, 1), result.map { it.speaker })
        assertEquals("Shall we start with the budget?", result[0].text)
        assertEquals("Yes, I have the numbers here.", result[1].text)
    }

    @Test
    fun lastWordsDriftingIntoTheNextTurnStayWithTheirSentence() {
        val sentence = words("I think we should renew for one year.", 0)
        // Speaker 1's activity starts 0.8 s early, covering this sentence's last two words.
        val act = activity(8_000, 2, Triple(0, 0, 2_400), Triple(1, 2_400, 6_000))
        val reply = words("Agreed, one year is fine.", 3_600)
        val result = Aligner().align(sentence + reply, act)
        assertEquals(2, result.size)
        assertEquals(0, result[0].speaker)
        assertEquals("I think we should renew for one year.", result[0].text)
        assertEquals(1, result[1].speaker)
    }

    @Test
    fun unpunctuatedChangeOfSpeakerIsSplitWhenTheOtherSpeakerIsClear() {
        // One "sentence" with no punctuation at the real change of speaker after the fifth word.
        val run = words("we can ship on the twentieth okay but I need the screens by Thursday at the latest.", 0)
        val act = activity(8_000, 2, Triple(0, 0, 2_000), Triple(1, 2_000, 7_000))
        val result = Aligner().align(run, act)
        assertEquals(listOf(0, 1), result.map { it.speaker })
        assertEquals("we can ship on the", result[0].text)
    }

    @Test
    fun wordsInSilenceGoToTheNearestSpeaker() {
        val lone = listOf(Word("Right.", 5_000, 5_300))
        val act = activity(8_000, 2, Triple(1, 5_600, 7_000))
        val result = Aligner().align(lone, act)
        assertEquals(1, result.single().speaker)
    }

    @Test
    fun sameSpeakerSentencesMergeAndLongPausesBreakThem() {
        val a = words("First point.", 0)
        val b = words("Second point.", 1_000)
        val c = words("After a long pause.", 6_000)
        val act = activity(10_000, 1, Triple(0, 0, 10_000))
        val result = Aligner().align(a + b + c, act)
        assertEquals(listOf("First point. Second point.", "After a long pause."), result.map { it.text })
    }

    @Test
    fun sentenceEndsRecogniseTrailingQuotesAndBrackets() {
        assertEquals(true, Aligner.endsSentence("done.\""))
        assertEquals(true, Aligner.endsSentence("(really?)"))
        assertEquals(false, Aligner.endsSentence("e.g"))
    }
}
