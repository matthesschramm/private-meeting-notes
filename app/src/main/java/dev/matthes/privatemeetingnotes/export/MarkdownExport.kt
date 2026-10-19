package dev.matthes.privatemeetingnotes.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import dev.matthes.privatemeetingnotes.data.ActionItem
import dev.matthes.privatemeetingnotes.data.Meeting
import dev.matthes.privatemeetingnotes.data.MeetingRepository
import dev.matthes.privatemeetingnotes.data.Speaker
import dev.matthes.privatemeetingnotes.data.Summary
import dev.matthes.privatemeetingnotes.data.Utterance
import dev.matthes.privatemeetingnotes.llm.Summarizer
import dev.matthes.privatemeetingnotes.ui.Format
import java.io.File

/** Meeting notes as Markdown, shared only when the user asks, through the Android share sheet. */
object MarkdownExport {
    fun meeting(
        meeting: Meeting,
        summary: Summary?,
        actions: List<ActionItem>,
        speakers: List<Speaker>,
        utterances: List<Utterance>,
        repository: MeetingRepository,
        includeTranscript: Boolean,
    ): String = buildString {
        appendLine("# ${meeting.title}")
        appendLine()
        appendLine("${Format.date(meeting.startedAt)} · ${Format.duration(meeting.durationMs)}")
        val names = speakers.associate { it.label to MeetingRepository.displayName(it) }
        if (names.isNotEmpty()) appendLine("Speakers: ${names.values.joinToString(", ")}")
        if (summary != null) {
            appendLine()
            appendLine("## Overview")
            appendLine(summary.overview)
            section("Key points", repository.decodeList(summary.keyPointsJson))
            section("Decisions", repository.decodeList(summary.decisionsJson))
            section("Open questions", repository.decodeList(summary.openQuestionsJson))
        }
        if (actions.isNotEmpty()) {
            appendLine()
            appendLine("## Action items")
            for (a in actions) {
                val owner = a.owner?.let { "**$it**: " } ?: ""
                val due = a.due?.let { " (due $it)" } ?: ""
                appendLine("- [${if (a.done) "x" else " "}] $owner${a.task}$due")
            }
        }
        summary?.tidyNotes?.let {
            appendLine()
            appendLine("## Notes")
            appendLine(it)
        }
        if (includeTranscript && utterances.isNotEmpty()) {
            appendLine()
            appendLine("## Transcript")
            for (u in utterances) {
                appendLine()
                appendLine("**${names[u.speakerLabel] ?: MeetingRepository.defaultSpeakerName(u.speakerLabel)}** [${Summarizer.clock(u.startMs)}]: ${u.text}")
            }
        }
    }

    private fun StringBuilder.section(title: String, items: List<String>) {
        if (items.isEmpty()) return
        appendLine()
        appendLine("## $title")
        items.forEach { appendLine("- $it") }
    }

    /**
     * As text, or as a text file when longer than [MAX_TEXT_CHARS]: Android passes text to the share sheet in one
     * message, and the notes and transcript of a 3-hour meeting (621 KB on the way) never got there.
     */
    fun share(context: Context, title: String, markdown: String) {
        val send = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_SUBJECT, title)
        if (markdown.length <= MAX_TEXT_CHARS) {
            send.setType("text/markdown").putExtra(Intent.EXTRA_TEXT, markdown)
        } else {
            val file = sharedFile(context, title, markdown)
            send.setType("text/plain").putExtra(Intent.EXTRA_STREAM, file).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            send.clipData = ClipData.newRawUri(title, file)
        }
        context.startActivity(Intent.createChooser(send, "Share notes").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Replaces the file shared before, which the app it went to has had time to read. */
    private fun sharedFile(context: Context, title: String, text: String): Uri {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val name = title.replace(UNSAFE_IN_FILE_NAMES, " ").trim().take(80).ifEmpty { "Meeting" }
        val file = File(dir, "$name.txt").apply { writeText(text) }
        return FileProvider.getUriForFile(context, "${context.packageName}.shared", file)
    }

    /** About 45 minutes of transcript; longer goes as a file. */
    const val MAX_TEXT_CHARS = 50_000
    private val UNSAFE_IN_FILE_NAMES = Regex("""[\\/:*?"<>|\r\n]+""")
}
