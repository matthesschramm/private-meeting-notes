package dev.matthes.privatemeetingnotes.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        Meeting::class, ProcessingJob::class, Speaker::class, Utterance::class, Summary::class, ActionItem::class,
        Voiceprint::class, Document::class, Chunk::class, ChunkFts::class, ChatThread::class, ChatMessage::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun meetings(): MeetingDao
    abstract fun jobs(): JobDao
    abstract fun transcripts(): TranscriptDao
    abstract fun summaries(): SummaryDao
    abstract fun voiceprints(): VoiceprintDao
    abstract fun knowledge(): KnowledgeDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "private-meeting-notes.db").build()
    }
}
