package dev.matthes.privatemeetingnotes.llm

import android.content.Context
import com.google.ai.edge.litertlm.ActivationDataType
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LiteRtLmJniException
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import dev.matthes.privatemeetingnotes.data.LlmBackend
import dev.matthes.privatemeetingnotes.data.LlmModel
import dev.matthes.privatemeetingnotes.data.Settings
import dev.matthes.privatemeetingnotes.diagnostics.Log
import dev.matthes.privatemeetingnotes.models.ModelId
import dev.matthes.privatemeetingnotes.models.ModelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Hosts Gemma 4 E4B through LiteRT-LM. The engine (about 3.7 GB, memory-mapped) is loaded on first use, kept while
 * work keeps coming, and unloaded after a minute idle or when Android signals memory pressure. One task at a time.
 */
class LlmHost(
    private val context: Context,
    private val models: ModelStore,
    private val settings: Settings,
    private val scope: CoroutineScope,
) {
    private val lock = Mutex()
    private var engine: Engine? = null
    private var loadedChoice: Choice? = null
    private var idleUnload: Job? = null

    /** Which model file runs on which processor. */
    private data class Choice(val model: ModelId, val file: String, val backend: LlmBackend, val label: String)

    /**
     * Notes use the model chosen in Settings. Answers use E2B whenever it is on the phone: on the GPU it needs about
     * 0.8 GB against E4B's 6.6 GB, and opening Ask with E4B loaded once made Android close every app, this one
     * included, for lack of memory.
     */
    private fun choice(task: LlmTask): Choice {
        val s = settings.current
        val e2b = Choice(ModelId.GEMMA_E2B, E2B_FILE, LlmBackend.GPU, "Gemma 4 E2B")
        if (task == LlmTask.ANSWERS && models.isUsable(ModelId.GEMMA_E2B)) return e2b
        return when (s.llmModel) {
            LlmModel.E2B -> e2b.copy(backend = s.llmBackend)
            LlmModel.E4B -> Choice(ModelId.GEMMA, MODEL_FILE, s.llmBackend, "Gemma 4 E4B")
        }
    }

    /** Whether the model for [task] is on the phone and intact. */
    fun available(task: LlmTask = LlmTask.NOTES): Boolean = models.isUsable(choice(task).model)

    /**
     * Number format of the GPU's intermediate values. Applies at the next load. In LiteRT's default 16-bit format,
     * Gemma's replies to prompts of 6,700 tokens and more broke down on this phone's GPU; 32-bit costs no speed.
     */
    var gpuActivation: ActivationDataType = ActivationDataType.FLOAT32

    /**
     * Whether replies use the model file's draft model to propose several tokens at a time, which the model then
     * checks together. It wrote notes 18% faster on the Pixel 11, and with greedy decoding the reply is unchanged.
     */
    var speculativeDecoding: Boolean = true

    /** The context window, in tokens, that the engine is loaded with. */
    val contextTokens: Int get() = settings.current.llmContextTokens.coerceAtMost(MAX_CONTEXT_TOKENS)

    fun modelName(task: LlmTask = LlmTask.NOTES): String = choice(task).label

    /** Runs [block] with the engine for [task] loaded. Calls are serialised. */
    suspend fun <T> withEngine(task: LlmTask = LlmTask.NOTES, block: suspend (Engine) -> T): T = lock.withLock {
        idleUnload?.cancel()
        val ready = withContext(Dispatchers.IO) { loaded(choice(task)) }
        try {
            block(ready)
        } finally {
            idleUnload = scope.launch {
                delay(IDLE_UNLOAD_MS)
                lock.withLock { release() }
            }
        }
    }

    /**
     * A fresh conversation for one task: low temperature, no hidden reasoning, a bounded reply. [jsonSchema] allows
     * replies under a JSON schema; it adds about 3 seconds to creating the conversation on this phone.
     */
    fun conversation(
        engine: Engine,
        system: String,
        temperature: Double = 0.3,
        maxOutputTokens: Int = MAX_OUTPUT_TOKENS,
        greedy: Boolean = false,
        jsonSchema: Boolean = false,
    ): Conversation =
        engine.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(system),
                samplerConfig = SamplerConfig(topK = if (greedy) 1 else 40, topP = 0.95, temperature = temperature, seed = 7),
                maxOutputToken = maxOutputTokens,
                thinkingConfig = ThinkingConfig(enableThinking = false),
                enableResponseFormat = jsonSchema,
                enableSpeculativeDecoding = speculativeDecoding,
            ),
        )

    /**
     * Loads the engine and has it read a short prompt, so the user's first question doesn't also pay for the GPU's
     * one-off set-up of reading prompts (a few seconds on the Pixel 11).
     */
    suspend fun warmUp(task: LlmTask = LlmTask.ANSWERS) = withEngine(task) { engine ->
        conversation(engine, "Reply with one word.", maxOutputTokens = 1).use { it.sendMessage(Message.user(WARM_UP_PROMPT)) }
        Unit
    }

    /** Frees the engine now, if no task is using it. */
    fun trim() {
        if (lock.tryLock()) {
            try {
                release()
            } finally {
                lock.unlock()
            }
        }
    }

    private fun loaded(wanted: Choice): Engine {
        engine?.let { if (loadedChoice == wanted) return it else release() }
        models.requireUsable(wanted.model)
        val started = System.nanoTime()
        Engine.setNativeMinLogSeverity(LogSeverity.WARNING)
        val created = try {
            start(wanted)
        } catch (e: LiteRtLmJniException) {
            if (wanted.backend != LlmBackend.GPU) throw e
            // A GPU cache left by a run that was cut off, such as by a reboot, stops the engine from starting.
            Log.w("llm failed to start on GPU; clearing its cache and trying again", e)
            context.cacheDir.listFiles { f -> f.name.startsWith(wanted.file) && "mldrift" in f.name }?.forEach { it.delete() }
            start(wanted)
        }
        Log.i("llm ${wanted.label} loaded on ${wanted.backend} in ${(System.nanoTime() - started) / 1_000_000} ms")
        engine = created
        loadedChoice = wanted
        return created
    }

    private fun start(wanted: Choice): Engine {
        val created = Engine(
            EngineConfig(
                modelPath = models.file(wanted.model, wanted.file).path,
                backend = when (wanted.backend) {
                    LlmBackend.CPU -> settings.current.llmCpuThreads.takeIf { it > 0 }?.let { Backend.CPU(threadCount = it) } ?: Backend.CPU()
                    LlmBackend.GPU -> Backend.GPU()
                },
                maxNumTokens = contextTokens,
                cacheDir = context.cacheDir.path,
                activationDataType = gpuActivation.takeIf { wanted.backend == LlmBackend.GPU },
            ),
        )
        try {
            created.initialize()
        } catch (e: Exception) {
            runCatching { created.close() }
            throw e
        }
        return created
    }

    private fun release() {
        engine?.close()
        if (engine != null) Log.i("llm unloaded")
        engine = null
        loadedChoice = null
    }

    companion object {
        const val MODEL_FILE = "gemma-4-E4B-it.litertlm"
        /** The general E2B build; its "-gpu" variant wrote only noise on this phone with LiteRT-LM 0.18. */
        const val E2B_FILE = "gemma-4-E2B-it.litertlm"
        private val WARM_UP_PROMPT = "The meeting notes are ready. ".repeat(100) + "Reply with OK."
        private const val IDLE_UNLOAD_MS = 60_000L
        /** About five minutes of writing at this phone's speed; long enough for any notes, short of a runaway reply. */
        const val MAX_OUTPUT_TOKENS = 2_048
        /**
         * The largest context tested. On the GPU, 32-bit values need more memory than 16-bit ones, and a 32K context
         * in 16-bit hung the Pixel 11 until its hardware watchdog rebooted it. On the CPU, a longer prompt plus a long
         * reply would outlast LiteRT-LM's limit of 10 minutes for one reply.
         */
        const val MAX_CONTEXT_TOKENS = 10_240
    }
}

/** What the language model is used for; the two can use different models. */
enum class LlmTask { NOTES, ANSWERS }

/** The text of a model reply. */
fun Message.text(): String = contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
