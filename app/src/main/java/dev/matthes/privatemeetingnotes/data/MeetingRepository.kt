package dev.matthes.privatemeetingnotes.data

import androidx.room.withTransaction
import dev.matthes.privatemeetingnotes.asr.Word
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Operations on meetings and their transcripts that span several tables or files. */
class MeetingRepository(private val db: AppDatabase, private val files: AppFiles) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun create(source: MeetingSource, title: String, headCount: Int?, inputDevice: String?): Meeting {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val meeting = Meeting(
            id = id,
            title = title.trim().ifEmpty { defaultTitle(source, now) },
            startedAt = now,
            durationMs = 0,
            source = source,
            audioFile = files.recording(id).name,
            headCount = headCount,
            status = if (source == MeetingSource.RECORDED) MeetingStatus.RECORDING else MeetingStatus.RECORDED,
            inputDevice = inputDevice,
            createdAt = now,
            updatedAt = now,
        )
        db.meetings().upsert(meeting)
        return meeting
    }

    /** Deletes the meeting with everything derived from it: transcript, notes, search index, audio and job files. */
    suspend fun delete(meetingId: String) {
        db.withTransaction {
            db.knowledge().deleteChunks(SourceType.TRANSCRIPT, meetingId)
            db.knowledge().deleteChunks(SourceType.SUMMARY, meetingId)
            db.meetings().delete(meetingId)
        }
        files.recording(meetingId).delete()
        files.jobDir(meetingId).deleteRecursively()
    }

    suspend fun deleteAudio(meetingId: String) {
        files.recording(meetingId).delete()
        db.meetings().clearAudio(meetingId)
    }

    /**
     * Names a speaker. The notes were written with labels such as "Speaker 2", so those are replaced there too.
     */
    suspend fun renameSpeaker(meetingId: String, label: Int, name: String?, voiceprintId: String?) {
        db.withTransaction {
            val old = db.transcripts().speakers(meetingId).firstOrNull { it.label == label }?.let { displayName(it) }
            db.transcripts().renameSpeaker(meetingId, label, name?.trim()?.ifEmpty { null }, voiceprintId)
            val new = name?.trim()?.ifEmpty { null } ?: defaultSpeakerName(label)
            if (old != null && old != new) replaceInNotes(meetingId, old, new)
        }
    }

    private suspend fun replaceInNotes(meetingId: String, old: String, new: String) {
        val pattern = Regex("\\b${Regex.escape(old)}\\b")
        db.summaries().get(meetingId)?.let { s ->
            fun fix(list: String) = json.encodeToString(ListSerializer(String.serializer()), decodeList(list).map { it.replace(pattern, new) })
            db.summaries().upsert(
                s.copy(
                    overview = s.overview.replace(pattern, new),
                    keyPointsJson = fix(s.keyPointsJson),
                    decisionsJson = fix(s.decisionsJson),
                    openQuestionsJson = fix(s.openQuestionsJson),
                    tidyNotes = s.tidyNotes?.replace(pattern, new),
                ),
            )
        }
        val actions = db.summaries().actions(meetingId)
        if (actions.any { it.owner?.contains(pattern) == true || it.task.contains(pattern) }) {
            db.summaries().replaceActions(
                meetingId,
                actions.map { it.copy(id = 0, owner = it.owner?.replace(pattern, new), task = it.task.replace(pattern, new)) },
            )
        }
    }

    suspend fun moveUtterance(utteranceId: Long, label: Int) = db.transcripts().moveUtterance(utteranceId, label)

    fun decodeList(value: String): List<String> =
        runCatching { json.decodeFromString(ListSerializer(String.serializer()), value) }.getOrDefault(emptyList())

    fun encodeList(values: List<String>): String = json.encodeToString(ListSerializer(String.serializer()), values)

    fun decodeWords(value: String): List<Word> =
        runCatching { json.decodeFromString(ListSerializer(Word.serializer()), value) }.getOrDefault(emptyList())

    fun encodeWords(words: List<Word>): String = json.encodeToString(ListSerializer(Word.serializer()), words)

    companion object {
        private val TITLE_DATE = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())

        fun defaultTitle(source: MeetingSource, at: Long): String =
            (if (source == MeetingSource.RECORDED) "Recording " else "Imported ") + TITLE_DATE.format(Date(at))

        fun isDefaultTitle(title: String) = title.startsWith("Recording ") || title.startsWith("Imported ")

        fun defaultSpeakerName(label: Int) = "Speaker ${label + 1}"

        fun displayName(speaker: Speaker) = speaker.displayName ?: defaultSpeakerName(speaker.label)
    }
}
