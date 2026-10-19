package dev.matthes.privatemeetingnotes.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RagTest {
    private fun words(n: Int, prefix: String = "w") = (1..n).joinToString(" ") { "$prefix$it" }

    @Test
    fun documentChunksStayOnTheirPageAndCarryContextAcrossBreaks() {
        val size = Chunker.TARGET_WORDS
        val overlap = Chunker.OVERLAP_WORDS
        val first = size + 100
        val chunks = Chunker.document(listOf(words(first, "a"), words(2 * size, "b")))
        assertEquals(size, chunks[0].text.split(" ").size)
        for (chunk in chunks) {
            val ws = chunk.text.split(" ")
            if (chunk.page == 1) {
                assertTrue(ws.all { it.startsWith("a") })
            } else {
                assertEquals(2, chunk.page)
                assertTrue(ws.count { it.startsWith("a") } <= overlap)
                assertTrue(ws.dropWhile { it.startsWith("a") }.all { it.startsWith("b") })
            }
        }
        // Page 2's first chunk opens with page 1's last words, then page 2 from its first word.
        val opening = chunks.first { it.page == 2 }.text.split(" ")
        assertEquals("a${first - overlap + 1}", opening.first())
        assertEquals("b1", opening[overlap])
        assertTrue(chunks.last().text.endsWith("b${2 * size}"))
        // Consecutive chunks share exactly the overlap, across the page break too.
        for (i in 1 until chunks.size) {
            val previous = chunks[i - 1].text.split(" ")
            val current = chunks[i].text.split(" ")
            assertEquals(previous.takeLast(overlap), current.take(overlap))
        }
    }

    @Test
    fun aShortTailJoinsTheChunkBeforeIt() {
        val chunks = Chunker.document(listOf(words(Chunker.TARGET_WORDS + Chunker.OVERLAP_WORDS / 2)))
        assertEquals(1, chunks.size)
        assertEquals(1, chunks[0].page)
    }

    @Test
    fun transcriptChunksKeepTurnsWholeAndRecordSpeakersAndTimes() {
        val perTurn = Chunker.TARGET_WORDS / 3
        val turns = (0 until 10).map { TurnText(if (it % 2 == 0) "Olivia" else "Raj", it * 10_000L, it * 10_000L + 9_000, words(perTurn, "t$it-")) }
        val chunks = Chunker.transcript(turns)
        assertEquals(4, chunks.size)
        assertEquals(0L, chunks[0].startMs)
        assertEquals(29_000L, chunks[0].endMs)
        assertEquals(listOf("Olivia", "Raj"), chunks[0].speakers)
        assertTrue(chunks[0].text.startsWith("Olivia: t0-1"))
        assertTrue(chunks.all { chunk -> chunk.text.lines().all { it.startsWith("Olivia: ") || it.startsWith("Raj: ") } })
        assertEquals(99_000L, chunks.last().endMs)
    }

    @Test
    fun aVeryLongTurnIsSplitOnItsOwn() {
        val chunks = Chunker.transcript(listOf(TurnText("Emily", 0, 600_000, words(4 * Chunker.TARGET_WORDS))))
        assertTrue(chunks.size >= 4)
        assertTrue(chunks.all { it.text.startsWith("Emily: ") && it.speakers == listOf("Emily") })
    }

    @Test
    fun longNotesAreIndexedInPiecesOfWholeLines() {
        val keyPoints = List(30) { "Key point: ${words(20)}" }
        val chunks = Chunker.notes((listOf("Summary of the meeting: ${words(450)}") + keyPoints).joinToString("\n"))
        assertTrue(chunks.all { it.text.split(" ", "\n").size <= Chunker.TARGET_WORDS + 2 })
        val pieces = chunks.flatMap { it.text.lines() }.filter { it.startsWith("Key point: ") }
        assertEquals(keyPoints, pieces)
    }

    @Test
    fun reciprocalRankFusionRewardsAgreementBetweenRankings() {
        val fused = Retriever.reciprocalRankFusion(listOf(listOf(1L, 2L, 3L), listOf(3L, 4L, 1L)))
        assertEquals(listOf(1L, 3L, 2L, 4L), fused)
    }

    @Test
    fun keywordQueryDropsStopwordsAndMatchesPrefixes() {
        assertEquals("decide* launch* date*", Retriever.ftsQuery("What did we decide about the launch date?")?.replace(" OR ", " "))
        assertNull(Retriever.ftsQuery("what was it?"))
    }

    @Test
    fun bm25FavoursRarerTermsAndShorterRows() {
        // matchinfo('pcnalx') for 1 phrase, 1 column, 100 rows, average 50 tokens:
        // p, c, n, a, l, then for the phrase: hits in row, hits in all rows, rows with a hit.
        fun info(rowTokens: Int, rowHits: Int, rowsWithHit: Int) = ByteBuffer.allocate(4 * 8).order(ByteOrder.nativeOrder()).apply {
            intArrayOf(1, 1, 100, 50, rowTokens, rowHits, rowHits * rowsWithHit, rowsWithHit).forEach { putInt(it) }
        }.array()
        val rare = Retriever.bm25(info(50, 1, 2))
        val common = Retriever.bm25(info(50, 1, 60))
        val shortRow = Retriever.bm25(info(20, 1, 2))
        assertTrue("rare $rare > common $common", rare > common)
        assertTrue("short row $shortRow > rare $rare", shortRow > rare)
    }
}
