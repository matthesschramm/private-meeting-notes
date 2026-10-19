package dev.matthes.privatemeetingnotes.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

enum class MeetingSource { RECORDED, IMPORTED }

enum class MeetingStatus { RECORDING, RECORDED, QUEUED, PROCESSING, READY, FAILED }

@Entity(tableName = "meetings")
data class Meeting(
    @PrimaryKey val id: String,
    val title: String,
    val startedAt: Long,
    val durationMs: Long,
    val source: MeetingSource,
    val audioFile: String?,
    val headCount: Int?,
    val status: MeetingStatus,
    val inputDevice: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

enum class JobState { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

enum class Stage(val label: String) {
    VAD("Finding speech"),
    ASR("Transcribing"),
    DIARIZE("Separating speakers"),
    SPEAKERS("Matching voices"),
    ALIGN("Building transcript"),
    SUMMARY("Writing summary"),
    INDEX("Indexing for search"),
    DONE("Done"),
}

@Entity(
    tableName = "processing_jobs",
    foreignKeys = [ForeignKey(Meeting::class, ["id"], ["meetingId"], onDelete = ForeignKey.CASCADE)],
)
data class ProcessingJob(
    @PrimaryKey val meetingId: String,
    val state: JobState,
    val stage: Stage,
    val progress: Float,
    val firstStage: Stage,
    val requestedAt: Long,
    val startedAt: Long?,
    val finishedAt: Long?,
    val error: String?,
    val timingsJson: String,
)

@Entity(
    tableName = "speakers",
    primaryKeys = ["meetingId", "label"],
    foreignKeys = [ForeignKey(Meeting::class, ["id"], ["meetingId"], onDelete = ForeignKey.CASCADE)],
)
data class Speaker(
    val meetingId: String,
    val label: Int,
    val displayName: String?,
    val voiceprintId: String?,
    val autoNamed: Boolean,
    val embedding: ByteArray?,
    val speechMs: Long,
)

@Entity(
    tableName = "utterances",
    indices = [Index("meetingId")],
    foreignKeys = [ForeignKey(Meeting::class, ["id"], ["meetingId"], onDelete = ForeignKey.CASCADE)],
)
data class Utterance(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val meetingId: String,
    val ordinal: Int,
    val speakerLabel: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val wordsJson: String,
    val movedByUser: Boolean = false,
)

@Entity(
    tableName = "summaries",
    foreignKeys = [ForeignKey(Meeting::class, ["id"], ["meetingId"], onDelete = ForeignKey.CASCADE)],
)
data class Summary(
    @PrimaryKey val meetingId: String,
    val title: String?,
    val overview: String,
    val keyPointsJson: String,
    val decisionsJson: String,
    val openQuestionsJson: String,
    val tidyNotes: String?,
    val promptVersion: Int,
    val model: String,
    val createdAt: Long,
)

@Entity(
    tableName = "action_items",
    indices = [Index("meetingId")],
    foreignKeys = [ForeignKey(Meeting::class, ["id"], ["meetingId"], onDelete = ForeignKey.CASCADE)],
)
data class ActionItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val meetingId: String,
    val ordinal: Int,
    val owner: String?,
    val task: String,
    val due: String?,
    val evidenceMs: Long?,
    val done: Boolean,
)

@Entity(tableName = "voiceprints")
data class Voiceprint(
    @PrimaryKey val id: String,
    val name: String,
    val embedding: ByteArray,
    val sampleCount: Int,
    val isSelf: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "documents")
data class Document(
    @PrimaryKey val id: String,
    val title: String,
    val mimeType: String,
    val fileName: String,
    val sizeBytes: Long,
    val pageCount: Int?,
    val addedAt: Long,
    val indexedAt: Long?,
    val error: String?,
)

enum class SourceType { TRANSCRIPT, SUMMARY, DOCUMENT }

@Entity(tableName = "chunks", indices = [Index("sourceType", "sourceId")])
data class Chunk(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceType: SourceType,
    val sourceId: String,
    val ordinal: Int,
    val text: String,
    val startMs: Long?,
    val endMs: Long?,
    val page: Int?,
    val speakers: String?,
    val vector: ByteArray?,
)

@Fts4(contentEntity = Chunk::class)
@Entity(tableName = "chunks_fts")
data class ChunkFts(val text: String)

@Entity(tableName = "chat_threads")
data class ChatThread(
    @PrimaryKey val id: String,
    val meetingId: String?,
    val title: String,
    val memo: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "chat_messages",
    indices = [Index("threadId")],
    foreignKeys = [ForeignKey(ChatThread::class, ["id"], ["threadId"], onDelete = ForeignKey.CASCADE)],
)
data class ChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val threadId: String,
    val role: String,
    val text: String,
    val citationsJson: String?,
    val createdAt: Long,
)
