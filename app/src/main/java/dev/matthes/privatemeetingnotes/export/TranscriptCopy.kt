package dev.matthes.privatemeetingnotes.export

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import dev.matthes.privatemeetingnotes.data.MeetingRepository
import dev.matthes.privatemeetingnotes.data.Speaker
import dev.matthes.privatemeetingnotes.data.Utterance
import dev.matthes.privatemeetingnotes.llm.Summarizer

/** The transcript alone as plain text, put on the clipboard only when the user asks. */
object TranscriptCopy {
    /** One paragraph per utterance, "Emily [1:02:15]: …", with the names given to the speakers. */
    fun text(speakers: List<Speaker>, utterances: List<Utterance>): String {
        val names = speakers.associate { it.label to MeetingRepository.displayName(it) }
        return utterances.joinToString("\n\n") { u ->
            "${names[u.speakerLabel] ?: MeetingRepository.defaultSpeakerName(u.speakerLabel)} [${Summarizer.clock(u.startMs)}]: ${u.text}"
        }
    }

    /**
     * Marked sensitive, so Android's copy confirmation doesn't show the meeting's words; Android confirms the copy
     * itself. False if the clipboard refused it, as it may a transcript of many hours.
     */
    fun copy(context: Context, title: String, text: String): Boolean {
        val clip = ClipData.newPlainText(title, text)
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        return try {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
            true
        } catch (e: RuntimeException) {
            false
        }
    }
}
