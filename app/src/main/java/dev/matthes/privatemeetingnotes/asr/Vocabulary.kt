package dev.matthes.privatemeetingnotes.asr

/** A name or term as it should be spelled, and words the speech model has written instead. */
data class VocabularyEntry(val term: String, val heardAs: List<String>)

/**
 * The user's names and terms, one per line, such as "Niamh: Nive, Neve" or just "Thandi". Variants after the colon
 * are single words; whole words matching one are replaced in new transcripts. Every term is also given to the
 * language model as a spelling to use.
 */
object Vocabulary {
    fun parse(text: String): List<VocabularyEntry> = text.lines().mapNotNull { line ->
        val term = line.substringBefore(':').trim()
        if (term.isEmpty()) return@mapNotNull null
        val variants = if (':' in line) line.substringAfter(':').split(',').map { it.trim() } else emptyList()
        VocabularyEntry(term, variants.filter { it.isNotEmpty() && it.none(Char::isWhitespace) && !it.equals(term, ignoreCase = true) })
    }

    fun terms(text: String): List<String> = parse(text).map { it.term }

    /** [words] with every whole word heard as a variant replaced by its term, keeping surrounding punctuation. */
    fun correct(words: List<Word>, entries: List<VocabularyEntry>): List<Word> {
        val replacement = entries.flatMap { e -> e.heardAs.map { it.lowercase() to e.term } }.toMap()
        if (replacement.isEmpty()) return words
        return words.map { word ->
            val match = CORE.find(word.text) ?: return@map word
            val core = match.value
            val possessive = POSSESSIVE.find(core)?.value.orEmpty()
            val fixed = replacement[core.lowercase()]
                ?: replacement[core.dropLast(possessive.length).lowercase()]?.let { it + possessive }.takeIf { possessive.isNotEmpty() }
                ?: return@map word
            word.copy(text = word.text.replaceRange(match.range, fixed))
        }
    }

    /** A word without the punctuation around it; apostrophes and hyphens inside stay. */
    private val CORE = Regex("[\\p{L}\\p{N}](?:[\\p{L}\\p{N}'’-]*[\\p{L}\\p{N}])?")
    private val POSSESSIVE = Regex("['’]s$", RegexOption.IGNORE_CASE)
}
