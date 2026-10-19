package dev.matthes.privatemeetingnotes.llm

/** Versioned prompt templates. Bump [VERSION] when a template changes, so stored outputs record what made them. */
object Prompts {
    const val VERSION = 3

    val NOTES_SYSTEM = """
        You write accurate, concise notes of meetings from their transcripts.
        Use only what the transcript says. Never add facts, names, numbers or dates that are not in it.
        Refer to people exactly as the transcript labels them, for example "Olivia" or "Speaker 2".
        Write plain English with Australian spelling.
    """.trimIndent()

    val NOTES_SCHEMA = """
        {"type":"object","properties":{
        "title":{"type":"string"},
        "overview":{"type":"string"},
        "key_points":{"type":"array","maxItems":10,"items":{"type":"string"}},
        "decisions":{"type":"array","maxItems":10,"items":{"type":"string"}},
        "open_questions":{"type":"array","maxItems":8,"items":{"type":"string"}},
        "action_items":{"type":"array","maxItems":15,"items":{"type":"object","properties":{
        "owner":{"type":"string"},"task":{"type":"string"},"due":{"type":"string"},"evidence_time":{"type":"string"}},
        "required":["owner","task","due","evidence_time"]}}},
        "required":["title","overview","key_points","decisions","open_questions","action_items"]}
    """.trimIndent().replace("\n", "")

    private val NOTES_FIELDS = """
        Write the notes as JSON with these fields:
        - title: a short title for the meeting, at most 8 words.
        - overview: 2 to 4 sentences on what the meeting was about and what came out of it.
        - key_points: the main points discussed, one short sentence each, at most 10.
        - decisions: what the participants explicitly agreed or decided, at most 10. Leave it empty if nothing was
          decided.
        - open_questions: questions or issues raised but not resolved in the meeting, at most 8.
        - action_items: tasks someone agreed to do, at most 15, each listed once. For each, give owner (who will do it, as labelled in the
          transcript, or "Unassigned"), task (what they will do, starting with a verb), due (the deadline as said,
          such as "by Friday", or an empty string) and evidence_time (the [time] of the line where it was agreed).
    """.trimIndent()

    fun notesRequest(transcript: String, part: Int? = null, parts: Int? = null, terms: List<String> = emptyList()): String = buildString {
        if (part != null && parts != null) {
            append("This is part $part of $parts of a meeting transcript. Write notes for this part only.\n")
        } else {
            append("Here is the transcript of a meeting.\n")
        }
        append("Each line is [time] speaker: words.\n\n<transcript>\n")
        append(transcript)
        append("\n</transcript>\n\n")
        if (terms.isNotEmpty()) {
            append("These names and terms may come up, and the transcript may have misheard them. Where the transcript ")
            append("means one of them, spell it like this: ${terms.joinToString(", ")}.\n\n")
        }
        append(NOTES_FIELDS)
    }

    fun combineRequest(partNotes: List<String>): String = buildString {
        append("Below are notes written separately for consecutive parts of one meeting, as JSON, in order.\n\n")
        partNotes.forEachIndexed { i, notes -> append("<part number=\"${i + 1}\">\n$notes\n</part>\n") }
        append(
            "\nMerge them into notes for the whole meeting in the same JSON format: one title and one overview for " +
                "the whole meeting, and combined key points, decisions, open questions and action items. Remove " +
                "duplicates, and drop open questions that a later part answered.\n\n",
        )
        append(NOTES_FIELDS)
    }

    val TIDY_SYSTEM = """
        You turn meeting transcripts into clean, readable notes in Markdown.
        Keep every fact, number, name and date. Remove filler, repetition and false starts.
        Never add anything that is not in the transcript. Write plain English with Australian spelling.
    """.trimIndent()

    fun tidyRequest(transcript: String, part: Int? = null, parts: Int? = null, terms: List<String> = emptyList()): String = buildString {
        if (part != null && parts != null) append("This is part $part of $parts of a meeting transcript.\n")
        append("Each line is [time] speaker: words.\n\n<transcript>\n")
        append(transcript)
        append(
            "\n</transcript>\n\nRewrite it as tidy notes. Group the discussion under short Markdown headings (##) " +
                "by topic, in the order it was discussed. Under each heading, write short bullet points of what was " +
                "said, naming who said it with the speaker labels exactly as given.",
        )
        if (terms.isNotEmpty()) append(" Spell these names and terms like this where the transcript means them: ${terms.joinToString(", ")}.")
    }
}
