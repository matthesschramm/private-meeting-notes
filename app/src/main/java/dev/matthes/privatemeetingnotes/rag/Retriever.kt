package dev.matthes.privatemeetingnotes.rag

import dev.matthes.privatemeetingnotes.data.AppDatabase
import dev.matthes.privatemeetingnotes.data.Chunk
import dev.matthes.privatemeetingnotes.data.ChunkVector
import dev.matthes.privatemeetingnotes.data.SourceType
import dev.matthes.privatemeetingnotes.speakers.Voices
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln

/** What a question may draw on. Null fields don't filter. */
data class SearchScope(
    val meetingId: String? = null,
    val fromMs: Long? = null,
    val toMs: Long? = null,
    val speaker: String? = null,
    val includeDocuments: Boolean = true,
)

/** A retrieved chunk with the title of the meeting or document it came from. */
data class Passage(val chunk: Chunk, val sourceTitle: String, val sourceDate: Long?)

/**
 * Hybrid retrieval: the 20 nearest chunks by embedding and the 20 best keyword matches (BM25 over the FTS4 index),
 * merged with reciprocal rank fusion. Personal scale needs no vector database: vectors are scanned in memory.
 */
class Retriever(private val db: AppDatabase) {
    private val lock = Mutex()
    private var vectors: List<ChunkVector>? = null

    /** Call after chunks change, so the next search reloads vectors. */
    fun invalidate() {
        vectors = null
    }

    suspend fun search(question: String, queryVector: FloatArray, scope: SearchScope, limit: Int = 8): List<Passage> {
        val allowed = allowedSources(scope)
        val byVector = lock.withLock { vectors ?: db.knowledge().vectors().also { vectors = it } }
            .filter { allowed(it.sourceType, it.sourceId) }
            .map { it.id to Voices.cosine(queryVector, Voices.fromBytes(it.vector)) }
            .sortedByDescending { it.second }
            .take(CANDIDATES)
            .map { it.first }
        val keywordQuery = ftsQuery(question)
        val byKeyword = if (keywordQuery == null) emptyList() else {
            val matches = runCatching { db.knowledge().keywordMatches(keywordQuery) }.getOrDefault(emptyList())
            val meta = db.knowledge().chunks(matches.map { it.id }).associateBy { it.id }
            matches.filter { m -> meta[m.id]?.let { allowed(it.sourceType, it.sourceId) } == true }
                .map { it.id to bm25(it.info) }
                .sortedByDescending { it.second }
                .take(CANDIDATES)
                .map { it.first }
        }
        val fused = reciprocalRankFusion(listOf(byVector, byKeyword)).take(limit * 2)
        val chunks = db.knowledge().chunks(fused).associateBy { it.id }
        return fused.mapNotNull { chunks[it] }
            .filter { chunk -> scope.speaker == null || chunk.sourceType != SourceType.TRANSCRIPT || chunk.speakers?.contains(scope.speaker, ignoreCase = true) == true }
            .take(limit)
            .map { chunk -> describe(chunk) }
    }

    private suspend fun allowedSources(scope: SearchScope): (SourceType, String) -> Boolean {
        val meetings = db.meetings().all().associateBy { it.id }
        return { type, id ->
            when (type) {
                SourceType.DOCUMENT -> scope.includeDocuments && scope.meetingId == null
                else -> {
                    val meeting = meetings[id]
                    meeting != null && (scope.meetingId == null || scope.meetingId == id) &&
                        (scope.fromMs == null || meeting.startedAt >= scope.fromMs) &&
                        (scope.toMs == null || meeting.startedAt <= scope.toMs)
                }
            }
        }
    }

    private suspend fun describe(chunk: Chunk): Passage = when (chunk.sourceType) {
        SourceType.DOCUMENT -> db.knowledge().document(chunk.sourceId).let { Passage(chunk, it?.title ?: "Document", it?.addedAt) }
        else -> db.meetings().get(chunk.sourceId).let { Passage(chunk, it?.title ?: "Meeting", it?.startedAt) }
    }

    companion object {
        private const val CANDIDATES = 20
        private const val RRF_K = 60.0
        private const val BM25_K1 = 1.2
        private const val BM25_B = 0.75
        private val STOPWORDS = setOf(
            "a", "an", "and", "are", "as", "at", "be", "but", "by", "did", "do", "does", "for", "from", "had", "has",
            "have", "how", "i", "in", "is", "it", "its", "me", "my", "of", "on", "or", "our", "so", "that", "the",
            "their", "them", "there", "they", "this", "to", "us", "was", "we", "were", "what", "when", "where",
            "which", "who", "why", "will", "with", "would", "you", "your", "about", "said", "say", "tell",
        )

        /** Rank lists merged by sum of 1 / (60 + rank), the usual RRF constant. */
        fun reciprocalRankFusion(rankings: List<List<Long>>): List<Long> {
            val scores = HashMap<Long, Double>()
            for (ranking in rankings) {
                ranking.forEachIndexed { rank, id -> scores[id] = (scores[id] ?: 0.0) + 1.0 / (RRF_K + rank + 1) }
            }
            return scores.entries.sortedWith(compareByDescending<Map.Entry<Long, Double>> { it.value }.thenBy { it.key }).map { it.key }
        }

        /** An FTS4 query matching any content word of the question, as a prefix; null if it has none. */
        fun ftsQuery(question: String): String? {
            val terms = question.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
                .filter { it.length > 1 && it !in STOPWORDS }
                .distinct()
                .take(12)
            return if (terms.isEmpty()) null else terms.joinToString(" OR ") { "$it*" }
        }

        /**
         * BM25 for one row from FTS4's matchinfo('pcnalx') blob: phrase count p, column count c, row count n,
         * average tokens per column a, tokens in this row l, then per phrase and column the hits in this row,
         * the hits in all rows and the rows with a hit.
         */
        fun bm25(info: ByteArray): Double {
            val ints = ByteBuffer.wrap(info).order(ByteOrder.nativeOrder()).asIntBuffer()
            val values = IntArray(ints.remaining()).also { ints.get(it) }
            val p = values[0]
            val c = values[1]
            val n = values[2].toDouble()
            val avg = DoubleArray(c) { values[3 + it].toDouble().coerceAtLeast(1.0) }
            val length = DoubleArray(c) { values[3 + c + it].toDouble() }
            var score = 0.0
            for (phrase in 0 until p) {
                for (col in 0 until c) {
                    val base = 3 + 2 * c + 3 * (phrase * c + col)
                    val tf = values[base].toDouble()
                    val docs = values[base + 2].toDouble()
                    if (tf == 0.0) continue
                    val idf = ln((n - docs + 0.5) / (docs + 0.5) + 1)
                    score += idf * tf * (BM25_K1 + 1) / (tf + BM25_K1 * (1 - BM25_B + BM25_B * length[col] / avg[col]))
                }
            }
            return score
        }
    }
}
