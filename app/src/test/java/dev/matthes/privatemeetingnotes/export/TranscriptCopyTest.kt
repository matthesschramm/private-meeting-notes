package dev.matthes.privatemeetingnotes.export

import dev.matthes.privatemeetingnotes.data.Speaker
import dev.matthes.privatemeetingnotes.data.Utterance
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptCopyTest {
    @Test
    fun eachUtteranceIsAParagraphWithItsSpeakerAndTime() {
        val speakers = listOf(Speaker("m", 0, "Emily", null, false, null, 1_000), Speaker("m", 1, null, null, false, null, 1_000))
        val utterances = listOf(
            Utterance(meetingId = "m", ordinal = 0, speakerLabel = 0, startMs = 2_000, endMs = 4_000, text = "Shall we start?", wordsJson = "[]"),
            Utterance(meetingId = "m", ordinal = 1, speakerLabel = 1, startMs = 3_735_000, endMs = 3_740_000, text = "Yes.", wordsJson = "[]"),
        )
        assertEquals("Emily [00:02]: Shall we start?\n\nSpeaker 2 [1:02:15]: Yes.", TranscriptCopy.text(speakers, utterances))
    }
}
