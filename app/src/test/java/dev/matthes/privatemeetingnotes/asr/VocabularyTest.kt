package dev.matthes.privatemeetingnotes.asr

import org.junit.Assert.assertEquals
import org.junit.Test

class VocabularyTest {
    private fun words(text: String) = text.split(" ").mapIndexed { i, w -> Word(w, i * 500L, i * 500L + 400) }

    @Test
    fun parsesTermsWithAndWithoutVariants() {
        val entries = Vocabulary.parse("Niamh: Nive, Neve\n\nThandi\n  Acme Labs : Acme Lab, AcmeLabs  ")
        assertEquals(listOf("Niamh", "Thandi", "Acme Labs"), entries.map { it.term })
        assertEquals(listOf("Nive", "Neve"), entries[0].heardAs)
        assertEquals(emptyList<String>(), entries[1].heardAs)
        // Variants are single words, so "Acme Lab" is dropped and only "AcmeLabs" is kept.
        assertEquals(listOf("AcmeLabs"), entries[2].heardAs)
    }

    @Test
    fun replacesWholeWordsAndKeepsPunctuationAndTimings() {
        val entries = Vocabulary.parse("Niamh: Nive\nThandi: Fendy")
        val fixed = Vocabulary.correct(words("Great, Nive, how is support? Fendy. Nivea stays."), entries)
        assertEquals("Great, Niamh, how is support? Thandi. Nivea stays.", fixed.joinToString(" ") { it.text })
        assertEquals(500L, fixed[1].startMs)
    }

    @Test
    fun matchesVariantsWhateverTheirCaseAndKeepsPossessives() {
        val fixed = Vocabulary.correct(words("FENDY said Fendy's quote was fine."), Vocabulary.parse("Thandi: Fendy"))
        assertEquals("Thandi said Thandi's quote was fine.", fixed.joinToString(" ") { it.text })
    }
}
