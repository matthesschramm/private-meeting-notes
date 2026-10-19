package dev.matthes.privatemeetingnotes.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MeetingDao {
    @Query("SELECT * FROM meetings ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<Meeting>>

    @Query(
        """
        SELECT * FROM meetings WHERE title LIKE '%' || :query || '%'
        OR id IN (SELECT meetingId FROM utterances WHERE text LIKE '%' || :query || '%')
        OR id IN (SELECT meetingId FROM summaries WHERE overview LIKE '%' || :query || '%')
        ORDER BY startedAt DESC
        """,
    )
    fun search(query: String): Flow<List<Meeting>>

    @Query("SELECT * FROM meetings WHERE id = :id")
    fun observe(id: String): Flow<Meeting?>

    @Query("SELECT * FROM meetings WHERE id = :id")
    suspend fun get(id: String): Meeting?

    @Query("SELECT * FROM meetings WHERE status = :status")
    suspend fun withStatus(status: MeetingStatus): List<Meeting>

    @Query("SELECT * FROM meetings ORDER BY startedAt DESC")
    suspend fun all(): List<Meeting>

    @Upsert
    suspend fun upsert(meeting: Meeting)

    @Query("UPDATE meetings SET status = :status, updatedAt = :now WHERE id = :id")
    suspend fun setStatus(id: String, status: MeetingStatus, now: Long = System.currentTimeMillis())

    @Query("UPDATE meetings SET title = :title, updatedAt = :now WHERE id = :id")
    suspend fun setTitle(id: String, title: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE meetings SET headCount = :headCount, updatedAt = :now WHERE id = :id")
    suspend fun setHeadCount(id: String, headCount: Int?, now: Long = System.currentTimeMillis())

    @Query("UPDATE meetings SET durationMs = :durationMs, updatedAt = :now WHERE id = :id")
    suspend fun setDuration(id: String, durationMs: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE meetings SET audioFile = NULL, updatedAt = :now WHERE id = :id")
    suspend fun clearAudio(id: String, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM meetings WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface JobDao {
    @Query("SELECT * FROM processing_jobs WHERE meetingId = :meetingId")
    fun observe(meetingId: String): Flow<ProcessingJob?>

    @Query("SELECT * FROM processing_jobs")
    fun observeAll(): Flow<List<ProcessingJob>>

    @Query("SELECT * FROM processing_jobs WHERE meetingId = :meetingId")
    suspend fun get(meetingId: String): ProcessingJob?

    @Query("SELECT * FROM processing_jobs WHERE state = 'QUEUED' ORDER BY requestedAt LIMIT 1")
    suspend fun nextQueued(): ProcessingJob?

    @Query("SELECT * FROM processing_jobs WHERE state IN ('QUEUED', 'RUNNING')")
    suspend fun pending(): List<ProcessingJob>

    @Upsert
    suspend fun upsert(job: ProcessingJob)

    @Query("UPDATE processing_jobs SET stage = :stage, progress = :progress WHERE meetingId = :meetingId")
    suspend fun setProgress(meetingId: String, stage: Stage, progress: Float)

    @Query("UPDATE processing_jobs SET state = 'QUEUED' WHERE state = 'RUNNING'")
    suspend fun requeueInterrupted()
}

@Dao
interface TranscriptDao {
    @Query("SELECT * FROM utterances WHERE meetingId = :meetingId ORDER BY ordinal")
    fun observeUtterances(meetingId: String): Flow<List<Utterance>>

    @Query("SELECT * FROM utterances WHERE meetingId = :meetingId ORDER BY ordinal")
    suspend fun utterances(meetingId: String): List<Utterance>

    @Query("SELECT * FROM speakers WHERE meetingId = :meetingId ORDER BY label")
    fun observeSpeakers(meetingId: String): Flow<List<Speaker>>

    @Query("SELECT * FROM speakers WHERE meetingId = :meetingId ORDER BY label")
    suspend fun speakers(meetingId: String): List<Speaker>

    @Query("SELECT * FROM speakers WHERE voiceprintId = :voiceprintId")
    suspend fun speakersWithVoiceprint(voiceprintId: String): List<Speaker>

    @Query("UPDATE speakers SET displayName = :name, voiceprintId = :voiceprintId, autoNamed = 0 WHERE meetingId = :meetingId AND label = :label")
    suspend fun renameSpeaker(meetingId: String, label: Int, name: String?, voiceprintId: String?)

    @Query("UPDATE speakers SET displayName = :name, voiceprintId = :voiceprintId, autoNamed = 1 WHERE meetingId = :meetingId AND label = :label")
    suspend fun autoNameSpeaker(meetingId: String, label: Int, name: String, voiceprintId: String)

    @Query("UPDATE speakers SET displayName = NULL, voiceprintId = NULL, autoNamed = 0 WHERE voiceprintId = :voiceprintId")
    suspend fun forgetVoiceprint(voiceprintId: String)

    /** Keeps the names in past meetings but stops linking them to the voiceprint. */
    @Query("UPDATE speakers SET voiceprintId = NULL, autoNamed = 0 WHERE voiceprintId = :voiceprintId")
    suspend fun unlinkVoiceprint(voiceprintId: String)

    @Query("SELECT voiceprintId AS id, COUNT(DISTINCT meetingId) AS meetings FROM speakers WHERE voiceprintId IS NOT NULL GROUP BY voiceprintId")
    fun observeVoiceprintUse(): Flow<List<VoiceprintUse>>

    @Query("UPDATE utterances SET speakerLabel = :label, movedByUser = 1 WHERE id = :utteranceId")
    suspend fun moveUtterance(utteranceId: Long, label: Int)

    @Insert
    suspend fun insertUtterances(utterances: List<Utterance>)

    @Upsert
    suspend fun upsertSpeakers(speakers: List<Speaker>)

    @Query("DELETE FROM utterances WHERE meetingId = :meetingId")
    suspend fun deleteUtterances(meetingId: String)

    @Query("DELETE FROM speakers WHERE meetingId = :meetingId")
    suspend fun deleteSpeakers(meetingId: String)

    @Transaction
    suspend fun replace(meetingId: String, speakers: List<Speaker>, utterances: List<Utterance>) {
        deleteUtterances(meetingId)
        deleteSpeakers(meetingId)
        upsertSpeakers(speakers)
        insertUtterances(utterances)
    }
}

@Dao
interface SummaryDao {
    @Query("SELECT * FROM summaries WHERE meetingId = :meetingId")
    fun observe(meetingId: String): Flow<Summary?>

    @Query("SELECT * FROM summaries WHERE meetingId = :meetingId")
    suspend fun get(meetingId: String): Summary?

    @Upsert
    suspend fun upsert(summary: Summary)

    @Query("UPDATE summaries SET tidyNotes = :notes WHERE meetingId = :meetingId")
    suspend fun setTidyNotes(meetingId: String, notes: String?)

    @Query("SELECT * FROM action_items WHERE meetingId = :meetingId ORDER BY ordinal")
    fun observeActions(meetingId: String): Flow<List<ActionItem>>

    @Query("SELECT * FROM action_items WHERE meetingId = :meetingId ORDER BY ordinal")
    suspend fun actions(meetingId: String): List<ActionItem>

    @Query("UPDATE action_items SET done = :done WHERE id = :id")
    suspend fun setDone(id: Long, done: Boolean)

    @Query("DELETE FROM action_items WHERE meetingId = :meetingId")
    suspend fun deleteActions(meetingId: String)

    @Insert
    suspend fun insertActions(items: List<ActionItem>)

    @Transaction
    suspend fun replaceActions(meetingId: String, items: List<ActionItem>) {
        deleteActions(meetingId)
        insertActions(items)
    }
}

@Dao
interface VoiceprintDao {
    @Query("SELECT * FROM voiceprints ORDER BY name")
    fun observeAll(): Flow<List<Voiceprint>>

    @Query("SELECT * FROM voiceprints")
    suspend fun all(): List<Voiceprint>

    @Query("SELECT * FROM voiceprints WHERE id = :id")
    suspend fun get(id: String): Voiceprint?

    @Query("SELECT * FROM voiceprints WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun byName(name: String): Voiceprint?

    @Upsert
    suspend fun upsert(voiceprint: Voiceprint)

    @Query("DELETE FROM voiceprints WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE voiceprints SET isSelf = 0 WHERE isSelf = 1")
    suspend fun clearSelf()
}

/** How many meetings a voiceprint has named a speaker in. */
data class VoiceprintUse(val id: String, val meetings: Int)

data class ChunkMatch(val id: Long, val info: ByteArray)

@Dao
interface KnowledgeDao {
    @Query("SELECT * FROM documents ORDER BY addedAt DESC")
    fun observeDocuments(): Flow<List<Document>>

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun document(id: String): Document?

    @Query("SELECT * FROM documents")
    suspend fun documents(): List<Document>

    @Upsert
    suspend fun upsertDocument(document: Document)

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun deleteDocument(id: String)

    @Insert
    suspend fun insertChunks(chunks: List<Chunk>)

    @Query("DELETE FROM chunks WHERE sourceType = :type AND sourceId = :sourceId")
    suspend fun deleteChunks(type: SourceType, sourceId: String)

    @Query("SELECT * FROM chunks WHERE id IN (:ids)")
    suspend fun chunks(ids: List<Long>): List<Chunk>

    @Query("SELECT id, vector, sourceType, sourceId FROM chunks WHERE vector IS NOT NULL")
    suspend fun vectors(): List<ChunkVector>

    @Query("SELECT COUNT(*) FROM chunks WHERE sourceType = :type AND sourceId = :sourceId")
    suspend fun chunkCount(type: SourceType, sourceId: String): Int

    @Query(
        """
        SELECT chunks.id AS id, matchinfo(chunks_fts, 'pcnalx') AS info
        FROM chunks_fts JOIN chunks ON chunks.id = chunks_fts.rowid
        WHERE chunks_fts MATCH :query
        """,
    )
    suspend fun keywordMatches(query: String): List<ChunkMatch>

    @Query("SELECT * FROM chat_threads WHERE (:meetingId IS NULL AND meetingId IS NULL) OR meetingId = :meetingId ORDER BY updatedAt DESC")
    fun observeThreads(meetingId: String?): Flow<List<ChatThread>>

    @Query("SELECT * FROM chat_threads WHERE id = :id")
    suspend fun thread(id: String): ChatThread?

    @Upsert
    suspend fun upsertThread(thread: ChatThread)

    @Query("DELETE FROM chat_threads WHERE id = :id")
    suspend fun deleteThread(id: String)

    @Query("SELECT * FROM chat_messages WHERE threadId = :threadId ORDER BY id")
    fun observeMessages(threadId: String): Flow<List<ChatMessage>>

    @Query("SELECT * FROM chat_messages WHERE threadId = :threadId ORDER BY id")
    suspend fun messages(threadId: String): List<ChatMessage>

    @Insert
    suspend fun insertMessage(message: ChatMessage): Long

    @Query("UPDATE chat_messages SET text = :text, citationsJson = :citationsJson WHERE id = :id")
    suspend fun updateMessage(id: Long, text: String, citationsJson: String?)
}

data class ChunkVector(val id: Long, val vector: ByteArray, val sourceType: SourceType, val sourceId: String)
