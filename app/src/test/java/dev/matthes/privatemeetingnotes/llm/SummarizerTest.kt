package dev.matthes.privatemeetingnotes.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SummarizerTest {
    /** Trimmed from a free-form Gemma reply on the Pixel 11: two lines hold only a stray quote and comma. */
    private val strayQuotes = """
        ```json
        {
          "title": "Pre-Launch Status Meeting",
          "overview": "The meeting covered the release status and support preparations.",
          "key_points": ["The tablet sync issue is not considered a blocker."],
          "decisions": [],
          "open_questions": [],
          "action_items": [
            {
              "owner": "Olivia",
              "task": "Raise the budget issue with finance on Friday.",
              "due": "by Friday",
              ",
              "evidence_time": "[02:48]"
            },
            {
              "owner": "Niamh",
              "task": "Publish the help articles the day before launch.",
              ",
              "evidence_time": "[3:38]"
            }
          ]
        }
        ```
    """.trimIndent()

    @Test
    fun strayQuoteLinesAreDropped() {
        val notes = Summarizer.parseNotes(strayQuotes)
        assertNotNull(notes)
        assertEquals("Pre-Launch Status Meeting", notes!!.title)
        assertEquals(listOf("by Friday", ""), notes.actionItems.map { it.due })
        assertEquals("[3:38]", notes.actionItems[1].evidenceTime)
    }

    @Test
    fun repairKeepsEmptyStringsInLists() {
        val reply = """
            {"title": "T", "key_points": [
              "",
              "Point"
            ],
              ",
            "decisions": []}
        """.trimIndent()
        assertEquals(listOf("", "Point"), Summarizer.parseNotes(reply)!!.keyPoints)
    }

    @Test
    fun aReplyWithoutJsonIsUnreadable() {
        assertNull(Summarizer.parseNotes("I could not write notes for this transcript."))
        assertNull(Summarizer.parseNotes("""{"title": "Cut off", "key_points": ["One", "Tw"""))
    }

    @Test
    fun notesTooLongForOneCombineAreBatchedInOrder() {
        assertEquals(listOf(listOf(0, 1), listOf(2, 3), listOf(4)), Summarizer.batches(List(5) { 1_000 }, 2_100))
        assertEquals(listOf(listOf(0), listOf(1, 2)), Summarizer.batches(listOf(5_000, 100, 100), 2_100))
    }

    @Test
    fun mergedPartsKeepEveryDistinctItemInOrder() {
        val first = MeetingNotes(
            title = "Launch planning",
            overview = "Part one.",
            keyPoints = listOf("Launch is on the twentieth.", "Video is late."),
            actionItems = listOf(NotesAction("Raj", "Confirm Tom for support", "this week", "[02:25]")),
        )
        val second = MeetingNotes(
            title = "",
            overview = "Part two.",
            keyPoints = listOf("video is late.", "Budget is over by fifteen thousand."),
            actionItems = listOf(
                NotesAction("raj", "confirm Tom for support", "", "[31:02]"),
                NotesAction("Olivia", "Raise the budget with finance", "Friday", "[33:10]"),
            ),
        )
        val merged = Summarizer.merge(listOf(first, second))
        assertEquals("Launch planning", merged.title)
        assertEquals("Part one. Part two.", merged.overview)
        assertEquals(listOf("Launch is on the twentieth.", "Video is late.", "Budget is over by fifteen thousand."), merged.keyPoints)
        assertEquals(listOf("Raj", "Olivia"), merged.actionItems.map { it.owner })
    }

    @Test
    fun clockFormatsAndParsesBothWays() {
        assertEquals("03:38", Summarizer.clock(218_000))
        assertEquals("1:00:36", Summarizer.clock(3_636_000))
        assertEquals(218_000L, Summarizer.parseClock("[3:38]"))
        assertEquals(3_636_000L, Summarizer.parseClock("1:00:36"))
        assertNull(Summarizer.parseClock("soon"))
    }
}
