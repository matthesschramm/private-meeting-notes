package dev.matthes.privatemeetingnotes.models

import android.content.Context
import dev.matthes.privatemeetingnotes.data.AppFiles
import dev.matthes.privatemeetingnotes.diagnostics.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

enum class ModelId(val key: String) {
    SILERO_VAD("silero-vad"),
    PARAKEET("parakeet-tdt-0.6b-v2-int8"),
    NEMOTRON("nemotron-3-diarization"),
    PYANNOTE("pyannote-segmentation-3.0"),
    WESPEAKER("wespeaker-resnet34-lm"),
    EMBEDDING_GEMMA("embeddinggemma-2-text-270m"),
    GEMMA("gemma-4-e4b"),
    GEMMA_E2B("gemma-4-e2b"),
}

@Serializable
data class RegistryFile(val path: String, val size: Long, val sha256: String)

/** A model the app can use; an [optional] one only enables a choice in Settings when it is on the phone. */
@Serializable
data class RegistryModel(val id: String, val purpose: String, val licence: String, val optional: Boolean = false, val files: List<RegistryFile>)

@Serializable
data class Registry(val version: Int, val models: List<RegistryModel>)

enum class FileState { MISSING, WRONG_SIZE, UNVERIFIED, VERIFIED, CORRUPT }

data class ModelState(val model: RegistryModel, val files: Map<String, FileState>) {
    /** Usable now: every file is present at the right size, and none failed its checksum. */
    val usable: Boolean get() = files.values.all { it == FileState.UNVERIFIED || it == FileState.VERIFIED }
    val verified: Boolean get() = files.values.all { it == FileState.VERIFIED }
    val sizeBytes: Long get() = model.files.sumOf { it.size }
}

/** Knows which model files the app needs and whether the ones pushed to the phone are intact. */
class ModelStore(context: Context, private val files: AppFiles) {
    val registry: Registry = context.assets.open("model-registry.json").use {
        Json.decodeFromString(Registry.serializer(), it.readBytes().decodeToString())
    }
    private val checks = context.getSharedPreferences("model_checks", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(registry.models.associate { it.id to ModelState(it, emptyMap()) })
    private val verifyLock = Mutex()

    val states: StateFlow<Map<String, ModelState>> = state.asStateFlow()

    fun ensureDirectories() {
        registry.models.forEach { files.model(it.id).mkdirs() }
    }

    fun dir(id: ModelId): File = files.model(id.key)

    fun file(id: ModelId, name: String): File = files.model("${id.key}/$name")

    fun isUsable(id: ModelId): Boolean = state.value[id.key]?.usable == true

    fun requireUsable(vararg ids: ModelId) {
        val missing = ids.filterNot { isUsable(it) }
        if (missing.isNotEmpty()) throw MissingModelsException(missing)
    }

    /** Fast check: presence and size, plus checksum results remembered from earlier runs. */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        state.value = registry.models.associate { model ->
            model.id to ModelState(model, model.files.associate { it.path to quickState(it) })
        }
    }

    /** Hashes every present file that hasn't been verified at its current size and modification time. */
    suspend fun verify() = verifyLock.withLock {
        withContext(Dispatchers.IO) {
            refresh()
            for (model in registry.models) {
                for (entry in model.files) {
                    if (quickState(entry) != FileState.UNVERIFIED) continue
                    val file = files.model(entry.path)
                    val started = System.nanoTime()
                    val ok = sha256(file) == entry.sha256
                    Log.i("checksum ${model.id} ${if (ok) "ok" else "MISMATCH"} in ${(System.nanoTime() - started) / 1_000_000} ms")
                    checks.edit().putString(entry.path, stamp(file, if (ok) "ok" else "bad")).apply()
                    setFileState(model.id, entry.path, if (ok) FileState.VERIFIED else FileState.CORRUPT)
                }
            }
        }
    }

    private fun quickState(entry: RegistryFile): FileState {
        val file = files.model(entry.path)
        if (!file.isFile) return FileState.MISSING
        if (file.length() != entry.size) return FileState.WRONG_SIZE
        return when (checks.getString(entry.path, null)) {
            stamp(file, "ok") -> FileState.VERIFIED
            stamp(file, "bad") -> FileState.CORRUPT
            else -> FileState.UNVERIFIED
        }
    }

    private fun setFileState(modelId: String, path: String, fileState: FileState) {
        state.update { all ->
            val current = all.getValue(modelId)
            all + (modelId to current.copy(files = current.files + (path to fileState)))
        }
    }

    private fun stamp(file: File, result: String) = "${file.length()}:${file.lastModified()}:$result"

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1 shl 20)
        file.inputStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

class MissingModelsException(val missing: List<ModelId>) :
    IllegalStateException("Models not ready: ${missing.joinToString { it.key }}")
