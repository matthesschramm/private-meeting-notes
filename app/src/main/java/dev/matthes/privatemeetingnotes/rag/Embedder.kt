package dev.matthes.privatemeetingnotes.rag

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.InputData
import com.google.ai.edge.litertlm.LogSeverity
import dev.matthes.privatemeetingnotes.models.ModelId
import dev.matthes.privatemeetingnotes.models.ModelStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.sqrt

/**
 * EmbeddingGemma 2 (270M) through LiteRT-LM's EmbeddingEngine, with the model card's query and document prompts.
 * Vectors are cut to their first 256 dimensions (the model is trained so they stand alone) and normalised.
 */
class Embedder(private val context: Context, private val models: ModelStore) {
    private val lock = Mutex()
    private var engine: EmbeddingEngine? = null

    suspend fun document(title: String?, text: String): FloatArray = embed("title: ${title ?: "none"} | text: $text")

    suspend fun query(question: String): FloatArray = embed("task: search result | query: $question")

    private suspend fun embed(input: String): FloatArray = lock.withLock {
        val text = InputData.Text(input.take(MAX_INPUT_CHARS))
        val full = loaded().computeEmbedding(listOf(text), EmbeddingOptions(normalize = false)).embedding
        normalise(full.copyOf(minOf(DIMENSIONS, full.size)))
    }

    private fun loaded(): EmbeddingEngine {
        engine?.let { return it }
        models.requireUsable(ModelId.EMBEDDING_GEMMA)
        Engine.setNativeMinLogSeverity(LogSeverity.WARNING)
        val created = EmbeddingEngine(
            EmbeddingEngineConfig(
                modelPath = models.file(ModelId.EMBEDDING_GEMMA, MODEL_FILE).path,
                backend = Backend.CPU(),
                cacheDir = context.cacheDir.path,
            ),
        )
        created.initialize()
        engine = created
        return created
    }

    fun close() {
        if (lock.tryLock()) {
            try {
                engine?.close()
                engine = null
            } finally {
                lock.unlock()
            }
        }
    }

    companion object {
        const val DIMENSIONS = 256
        private const val MODEL_FILE = "embeddinggemma-2-text-270m.litertlm"
        /**
         * This build of the model refuses inputs over 1,024 tokens. Chunks are about 200 words; this only keeps an
         * unusually dense one from failing the whole index.
         */
        private const val MAX_INPUT_CHARS = 2_500

        fun normalise(v: FloatArray): FloatArray {
            var sum = 0f
            for (x in v) sum += x * x
            val norm = sqrt(sum)
            return if (norm == 0f) v else FloatArray(v.size) { v[it] / norm }
        }
    }
}
