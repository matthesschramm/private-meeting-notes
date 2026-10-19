package dev.matthes.privatemeetingnotes.bench

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.matthes.privatemeetingnotes.asr.SpeechDetector
import dev.matthes.privatemeetingnotes.asr.Transcriber
import dev.matthes.privatemeetingnotes.audio.SAMPLE_RATE
import dev.matthes.privatemeetingnotes.audio.WavFile
import dev.matthes.privatemeetingnotes.container
import dev.matthes.privatemeetingnotes.data.AppFiles
import dev.matthes.privatemeetingnotes.data.LlmBackend
import dev.matthes.privatemeetingnotes.data.LlmModel
import dev.matthes.privatemeetingnotes.data.ChatThread
import dev.matthes.privatemeetingnotes.data.MeetingSource
import dev.matthes.privatemeetingnotes.data.MeetingStatus
import dev.matthes.privatemeetingnotes.data.Speaker
import dev.matthes.privatemeetingnotes.data.Stage
import dev.matthes.privatemeetingnotes.data.Utterance
import dev.matthes.privatemeetingnotes.data.SettingsValues
import dev.matthes.privatemeetingnotes.data.SourceType
import dev.matthes.privatemeetingnotes.rag.Answerer
import dev.matthes.privatemeetingnotes.rag.SearchScope
import dev.matthes.privatemeetingnotes.speakers.VoiceEmbedder
import dev.matthes.privatemeetingnotes.speakers.Voices
import java.util.UUID
import dev.matthes.privatemeetingnotes.importing.AudioImporter
import dev.matthes.privatemeetingnotes.processing.Checkpoints
import android.net.Uri
import dev.matthes.privatemeetingnotes.diarization.FallbackDiarizer
import dev.matthes.privatemeetingnotes.diarization.NemotronDiarizer
import dev.matthes.privatemeetingnotes.llm.LlmHost
import dev.matthes.privatemeetingnotes.llm.LlmTask
import dev.matthes.privatemeetingnotes.llm.MeetingNotes
import dev.matthes.privatemeetingnotes.llm.Summarizer
import dev.matthes.privatemeetingnotes.llm.TranscriptLine
import dev.matthes.privatemeetingnotes.models.ModelId
import com.google.ai.edge.litertlm.ActivationDataType
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.BenchmarkInfo
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import dev.matthes.privatemeetingnotes.llm.text
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Debug-only entry point: runs pipeline stages on a WAV in the app's bench/ folder and writes results and timings
 * next to it. Started from the Mac with `adb shell am instrument -e input <file.wav> ...`.
 */
@RunWith(AndroidJUnit4::class)
class BenchTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val files = AppFiles(context)

    private fun input(): File = File(files.bench, requireNotNull(args.getString("input")) { "pass -e input <file.wav>" })

    private fun report(name: String, json: String) {
        File(files.bench, "${input().nameWithoutExtension}.$name.json").writeText(json)
    }

    /**
     * The whole pipeline, as the processing service runs it, on a WAV: transcript, speakers, notes, per-stage timings
     * and peak memory. Options: heads=<n> (expected head count), summary=false, backend=cpu|gpu, keep=true,
     * fallback=true (the clustering pipeline at any head count), chunkMinutes=<n> (its longest chunk).
     */
    @Test
    fun pipeline() {
        val c = context.container
        val previous = c.settings.current
        // Like the processing service, so the phone doesn't sleep through a long recording with the screen off.
        val wakeLock = context.getSystemService(android.os.PowerManager::class.java)
            .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "PrivateMeetingNotes:bench")
        wakeLock.acquire(4L * 60 * 60 * 1000)
        args.getString("backend")?.let { b -> c.settings.update { it.copy(llmBackend = LlmBackend.valueOf(b.uppercase())) } }
        args.getString("model")?.let { m -> c.settings.update { it.copy(llmModel = LlmModel.valueOf(m.uppercase())) } }
        // vocab="Thandi: Fendy|Niamh: Nive" sets the names and terms, one entry per "|".
        args.getString("vocab")?.let { v -> c.settings.update { it.copy(vocabulary = v.replace('|', '\n')) } }
        try {
            runBlocking {
                c.models.refresh()
                val heads = args.getString("heads")?.toInt()
                val meeting = c.repository.create(MeetingSource.IMPORTED, "Benchmark: ${input().nameWithoutExtension}", heads, null)
                input().copyTo(c.files.recording(meeting.id), overwrite = true)
                if (args.getString("summary") == "false") Checkpoints(c.files.jobDir(meeting.id)).markDone(Stage.SUMMARY)
                c.pipeline.forceFallback = args.getString("fallback") == "true"
                args.getString("chunkMinutes")?.let { c.pipeline.fallbackChunkSamples = it.toLong() * 60 * SAMPLE_RATE }
                val t0 = System.nanoTime()
                val timings = try {
                    c.pipeline.run(meeting.id, { _, _ -> }, { false })
                } finally {
                    c.pipeline.forceFallback = false
                    c.pipeline.fallbackChunkSamples = FallbackDiarizer.MAX_CHUNK_SAMPLES
                }
                val totalMs = (System.nanoTime() - t0) / 1_000_000
                val speakers = c.database.transcripts().speakers(meeting.id)
                val utterances = c.database.transcripts().utterances(meeting.id)
                val summary = c.database.summaries().get(meeting.id)
                val actions = c.database.summaries().actions(meeting.id)
                val audioMs = c.database.meetings().get(meeting.id)?.durationMs ?: 0
                report(
                    "pipeline" + (if (args.getString("fallback") == "true") "-fallback" else "") +
                        listOf("heads", "chunkMinutes").mapNotNull { k -> args.getString(k)?.let { "-$k$it" } }.joinToString(""),
                    Json.encodeToString(
                        kotlinx.serialization.json.JsonObject.serializer(),
                        buildJsonObject {
                            put("audioMs", audioMs)
                            put("totalMs", totalMs)
                            put("backend", c.settings.current.llmBackend.name)
                            put("peakRssMb", peakRssMb())
                            put("timings", buildJsonObject { timings.forEach { (stage, ms) -> put(stage.name, ms) } })
                            put("speakers", speakers.size)
                            putJsonArray("utterances") {
                                utterances.forEach { u ->
                                    add(buildJsonObject {
                                        put("speaker", "S${u.speakerLabel}")
                                        put("start", u.startMs / 1000.0)
                                        put("end", u.endMs / 1000.0)
                                        put("text", u.text)
                                    })
                                }
                            }
                            summary?.let {
                                put("overview", it.overview)
                                put("decisions", it.decisionsJson)
                            }
                            putJsonArray("actions") { actions.forEach { a -> add("${a.owner}: ${a.task} (${a.due}) @${a.evidenceMs}") } }
                        },
                    ),
                )
                if (args.getString("keep") != "true") c.repository.delete(meeting.id)
            }
        } finally {
            c.settings.update { previous }
            wakeLock.release()
        }
    }

    /**
     * Removes what interrupted benchmark runs leave behind: "Benchmark: ..." meetings, search chunks whose meeting or
     * document no longer exists, and the speed settings the benchmarks change.
     */
    @Test
    fun cleanup() = runBlocking {
        val c = context.container
        val leftovers = c.database.meetings().all().filter { it.title.startsWith("Benchmark: ") }
        leftovers.forEach { c.queue.cancel(it.id); c.repository.delete(it.id) }
        val meetings = c.database.meetings().all().map { it.id }.toSet()
        val documents = c.database.knowledge().documents().map { it.id }.toSet()
        val orphans = c.database.knowledge().vectors()
            .filter { if (it.sourceType == SourceType.DOCUMENT) it.sourceId !in documents else it.sourceId !in meetings }
            .map { it.sourceType to it.sourceId }.distinct()
        orphans.forEach { (type, id) -> c.database.knowledge().deleteChunks(type, id) }
        c.retriever.invalidate()
        val defaults = SettingsValues()
        c.settings.update {
            it.copy(llmBackend = defaults.llmBackend, llmCpuThreads = defaults.llmCpuThreads,
                llmContextTokens = defaults.llmContextTokens, asrThreads = defaults.asrThreads)
        }
        println("cleanup: ${leftovers.size} benchmark meetings, ${orphans.size} orphaned sources; ${meetings.size} meetings and ${documents.size} documents remain")
    }

    /**
     * Voiceprint acceptance test: processes the two-, four- and six-speaker room meetings in turn. After each one,
     * every speaker without the right name is named from the ground truth, as the user would, so the later meetings
     * show which voices are named automatically. Push "<set>-room.wav" and "<set>-truth.json" for each set.
     */
    @Test
    fun voiceprints() = runBlocking {
        val c = context.container
        val before = c.database.voiceprints().all()
        val meetings = mutableListOf<String>()
        val results = mutableListOf<kotlinx.serialization.json.JsonObject>()
        try {
            c.models.refresh()
            for (set in listOf("two-speakers", "four-speakers", "six-speakers")) {
                val truth = Json.parseToJsonElement(File(files.bench, "$set-truth.json").readText()).jsonObject
                val meeting = c.repository.create(MeetingSource.IMPORTED, "Benchmark: $set voices", null, null)
                meetings += meeting.id
                File(files.bench, "$set-room.wav").copyTo(c.files.recording(meeting.id), overwrite = true)
                Checkpoints(c.files.jobDir(meeting.id)).markDone(Stage.SUMMARY)
                val prints = c.database.voiceprints().all()
                c.pipeline.run(meeting.id, { _, _ -> }, { false })
                val truthOf = truthNames(truth, c.database.transcripts().utterances(meeting.id))
                for (s in c.database.transcripts().speakers(meeting.id)) {
                    val voice = s.embedding?.let { Voices.fromBytes(it) }
                    val expected = truthOf[s.label]
                    results += buildJsonObject {
                        put("meeting", set)
                        put("speaker", s.label)
                        put("truth", expected)
                        put("known", prints.any { it.name == expected })
                        put("autoName", s.displayName.takeIf { s.autoNamed })
                        put("speechMs", s.speechMs)
                        putJsonArray("embedding") { voice?.forEach { add(it) } }
                        putJsonArray("cosine") {
                            prints.map { it.name to (voice?.let { v -> Voices.cosine(v, Voices.fromBytes(it.embedding)) } ?: Float.NaN) }
                                .sortedByDescending { it.second }
                                .forEach { (name, cos) -> add(buildJsonObject { put("name", name); put("cosine", cos) }) }
                        }
                    }
                    if (expected != null && !(s.autoNamed && s.displayName == expected)) {
                        c.repository.renameSpeaker(meeting.id, s.label, expected, c.voiceprints.learn(s, expected, isSelf = false))
                    }
                }
            }
            File(files.bench, "two-speakers-room.voiceprints.json").writeText(
                Json.encodeToString(kotlinx.serialization.json.JsonArray.serializer(), kotlinx.serialization.json.JsonArray(results)),
            )
        } finally {
            meetings.forEach { c.repository.delete(it) }
            c.database.voiceprints().all().filter { p -> before.none { it.id == p.id } }.forEach {
                c.database.transcripts().unlinkVoiceprint(it.id)
                c.database.voiceprints().delete(it.id)
            }
            before.forEach { c.database.voiceprints().upsert(it) }
            c.retriever.invalidate()
        }
    }

    /** Voice embeddings of whole short recordings, one per WAV named in the input list, to check the embedding model. */
    @Test
    fun embed() {
        val names = input().readLines().map { it.trim() }.filter { it.isNotEmpty() }
        val model = File(files.model(ModelId.WESPEAKER.key), "wespeaker_en_voxceleb_resnet34_LM.onnx")
        val rows = VoiceEmbedder(model, 4).use { embedder ->
            names.map { name ->
                val voice = WavFile(File(files.bench, name)).use { embedder.embed(it.read(0, it.sampleCount)) }
                buildJsonObject {
                    put("file", name)
                    putJsonArray("embedding") { voice?.forEach { add(it) } }
                }
            }
        }
        report("embeddings", Json.encodeToString(kotlinx.serialization.json.JsonArray.serializer(), kotlinx.serialization.json.JsonArray(rows)))
    }

    /**
     * Metadata of every meeting and its processing job, to diagnose a failure: lengths, head count, status, stage,
     * error, timings, settings that affect processing, and which checkpoints exist with their sizes. No transcript
     * text, names or audio.
     */
    @Test
    fun inspect() = runBlocking {
        val c = context.container
        val rows = c.database.meetings().all().map { m ->
            val job = c.database.jobs().get(m.id)
            val jobDir = c.files.jobDir(m.id)
            buildJsonObject {
                put("id", m.id)
                put("startedAt", java.time.Instant.ofEpochMilli(m.startedAt).toString())
                put("durationMinutes", m.durationMs / 60_000.0)
                put("source", m.source.name)
                put("headCount", m.headCount)
                put("status", m.status.name)
                put("recordingBytes", c.files.recording(m.id).takeIf { it.exists() }?.length() ?: -1L)
                job?.let { j ->
                    put("jobState", j.state.name)
                    put("jobStage", j.stage.name)
                    put("jobFirstStage", j.firstStage.name)
                    put("jobError", j.error)
                    put("requestedAt", java.time.Instant.ofEpochMilli(j.requestedAt).toString())
                    put("jobStartedAt", j.startedAt?.let { java.time.Instant.ofEpochMilli(it).toString() })
                    put("jobFinishedAt", j.finishedAt?.let { java.time.Instant.ofEpochMilli(it).toString() })
                    put("timings", j.timingsJson)
                }
                putJsonArray("checkpoints") {
                    jobDir.listFiles()?.sortedBy { it.name }?.forEach { f ->
                        add("${f.name} ${f.length()} bytes, modified ${java.time.Instant.ofEpochMilli(f.lastModified())}")
                    }
                }
            }
        }
        val settings = c.settings.current
        val report = buildJsonObject {
            putJsonArray("meetings") { rows.forEach { add(it) } }
            put("processOnlyWhileCharging", settings.processOnlyWhileCharging)
            put("audioRetention", settings.audioRetention.name)
            put("llm", "${settings.llmModel} on ${settings.llmBackend}")
        }
        File(files.bench, "inspect.json").writeText(Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), report))
        println("inspect: ${rows.size} meetings")
    }

    /**
     * One meeting's transcript, speakers and notes, for comparing with another transcript of the same meeting when
     * the user asks: -e meeting <id>. The utterances are in the shape scripts/score.py reads.
     */
    @Test
    fun export() = runBlocking {
        val c = context.container
        val id = requireNotNull(args.getString("meeting")) { "pass -e meeting <id>" }
        val meeting = requireNotNull(c.database.meetings().get(id)) { "no meeting $id" }
        val summary = c.database.summaries().get(id)
        val speakers = c.database.transcripts().speakers(id)
        val utterances = c.database.transcripts().utterances(id)
        val actions = c.database.summaries().actions(id)
        val report = buildJsonObject {
            put("title", meeting.title)
            put("startedAt", java.time.Instant.ofEpochMilli(meeting.startedAt).toString())
            put("durationMs", meeting.durationMs)
            put("headCount", meeting.headCount)
            putJsonArray("speakers") {
                speakers.forEach { s ->
                    add(buildJsonObject {
                        put("speaker", "S${s.label}")
                        put("name", s.displayName)
                        put("speechMs", s.speechMs)
                    })
                }
            }
            putJsonArray("utterances") {
                utterances.forEach { u ->
                    add(buildJsonObject {
                        put("speaker", "S${u.speakerLabel}")
                        put("start", u.startMs / 1000.0)
                        put("end", u.endMs / 1000.0)
                        put("text", u.text)
                    })
                }
            }
            summary?.let {
                put("overview", it.overview)
                put("keyPoints", it.keyPointsJson)
                put("decisions", it.decisionsJson)
                put("openQuestions", it.openQuestionsJson)
            }
            putJsonArray("actions") {
                actions.forEach { a -> add("${a.owner ?: "Unassigned"}: ${a.task}" + (a.due?.let { " (due $it)" } ?: "")) }
            }
        }
        File(files.bench, "export.json").writeText(Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), report))
    }

    /** The voice embeddings and names of every speaker in the phone's meetings, for checking voiceprint thresholds. */
    @Test
    fun speakers() = runBlocking {
        val c = context.container
        val rows = c.database.meetings().all().flatMap { meeting ->
            c.database.transcripts().speakers(meeting.id).map { s ->
                buildJsonObject {
                    put("meeting", meeting.title)
                    put("label", s.label)
                    put("name", s.displayName)
                    put("autoNamed", s.autoNamed)
                    put("speechMs", s.speechMs)
                    putJsonArray("embedding") { s.embedding?.let { Voices.fromBytes(it) }?.forEach { add(it) } }
                }
            }
        }
        report("speakers", Json.encodeToString(kotlinx.serialization.json.JsonArray.serializer(), kotlinx.serialization.json.JsonArray(rows)))
    }

    /** Each detected speaker's ground-truth name: whoever they overlap most in the script's timeline. */
    private fun truthNames(truth: kotlinx.serialization.json.JsonObject, utterances: List<Utterance>): Map<Int, String> {
        val segments = truth.getValue("segments").jsonArray.map { it.jsonObject }.map {
            Triple((it.getValue("start").jsonPrimitive.double * 1000).toLong(), (it.getValue("end").jsonPrimitive.double * 1000).toLong(), it.getValue("speaker").jsonPrimitive.content)
        }
        val overlap = mutableMapOf<Int, MutableMap<String, Long>>()
        for (u in utterances) {
            for ((start, end, name) in segments) {
                val shared = minOf(u.endMs, end) - maxOf(u.startMs, start)
                if (shared > 0) overlap.getOrPut(u.speakerLabel) { mutableMapOf() }.merge(name, shared, Long::plus)
            }
        }
        return overlap.mapValues { (_, byName) -> byName.maxBy { it.value }.key }
    }

    /** Import on its own: decodes any audio file to 16 kHz mono WAV, then transcribes it to check the result. */
    @Test
    fun importing() {
        val out = File(files.bench, "${input().nameWithoutExtension}.imported.wav")
        val t0 = System.nanoTime()
        val samples = runBlocking { AudioImporter(context).import(Uri.fromFile(input()), out) {} }
        val t1 = System.nanoTime()
        val words = WavFile(out).use { wav ->
            val regions = SpeechDetector(files.model("${ModelId.SILERO_VAD.key}/silero_vad.onnx")).use { it.detect(wav) }
            Transcriber(files.model(ModelId.PARAKEET.key), 4).use { asr ->
                regions.flatMap { asr.transcribe(wav.read(it.start, it.end), it.start * 1000 / SAMPLE_RATE) }
            }
        }
        out.delete()
        report(
            "import",
            Json.encodeToString(
                kotlinx.serialization.json.JsonObject.serializer(),
                buildJsonObject {
                    put("inputBytes", input().length())
                    put("importedMs", samples * 1000 / SAMPLE_RATE)
                    put("importMs", (t1 - t0) / 1_000_000)
                    putJsonArray("utterances") {
                        add(buildJsonObject {
                            put("speaker", "S0")
                            put("text", words.joinToString(" ") { it.text })
                        })
                    }
                },
            ),
        )
    }

    /**
     * Retrieval test from test-data/retrieval/questions.json: indexes the synthetic meetings' ground-truth
     * transcripts and the test documents, then asks each question, checking sources, answer words and timings.
     */
    @OptIn(ExperimentalApi::class)
    @Test
    fun retrieval() {
        val c = context.container
        val previous = c.settings.current
        ExperimentalFlags.enableBenchmark = true
        args.getString("backend")?.let { b -> c.settings.update { it.copy(llmBackend = LlmBackend.valueOf(b.uppercase())) } }
        args.getString("model")?.let { m -> c.settings.update { it.copy(llmModel = LlmModel.valueOf(m.uppercase())) } }
        val spec = Json.parseToJsonElement(input().readText()).jsonObject
        val meetingIds = mutableMapOf<String, String>()
        val documentIds = mutableMapOf<String, String>()
        try {
            runBlocking {
                c.models.refresh()
                val t0 = System.nanoTime()
                for (name in spec.getValue("meetings").jsonArray.map { it.jsonPrimitive.content }) {
                    val truth = Json.parseToJsonElement(File(files.bench, "$name-truth.json").readText()).jsonObject
                    val meeting = c.repository.create(MeetingSource.IMPORTED, truth.getValue("title").jsonPrimitive.content, null, null)
                    val names = truth.getValue("speakers").jsonArray.map { it.jsonPrimitive.content }
                    val utterances = truth.getValue("segments").jsonArray.mapIndexed { i, s ->
                        val o = s.jsonObject
                        Utterance(
                            meetingId = meeting.id,
                            ordinal = i,
                            speakerLabel = names.indexOf(o.getValue("speaker").jsonPrimitive.content),
                            startMs = (o.getValue("start").jsonPrimitive.double * 1000).toLong(),
                            endMs = (o.getValue("end").jsonPrimitive.double * 1000).toLong(),
                            text = o.getValue("text").jsonPrimitive.content,
                            wordsJson = "[]",
                        )
                    }
                    val speakers = names.mapIndexed { i, n -> Speaker(meeting.id, i, n, null, false, null, 0) }
                    c.database.transcripts().replace(meeting.id, speakers, utterances)
                    c.database.meetings().setStatus(meeting.id, MeetingStatus.READY)
                    c.indexer.indexMeeting(meeting.id)
                    meetingIds[name] = meeting.id
                }
                val documentReports = spec.getValue("documents").jsonArray.map { it.jsonPrimitive.content }.map { name ->
                    val td = System.nanoTime()
                    val id = c.indexer.addDocument(Uri.fromFile(File(files.bench, name))) {}.id
                    documentIds[name] = id
                    val document = c.database.knowledge().document(id)
                    buildJsonObject {
                        put("name", name)
                        put("indexMs", (System.nanoTime() - td) / 1_000_000)
                        put("pages", document?.pageCount ?: -1)
                        put("chunks", c.database.knowledge().chunkCount(SourceType.DOCUMENT, id))
                        put("error", document?.error ?: "")
                    }
                }
                val indexMs = (System.nanoTime() - t0) / 1_000_000
                val nameOf = (meetingIds + documentIds).entries.associate { (name, id) -> id to name }
                val tw = System.nanoTime()
                c.llm.warmUp()
                val warmUpMs = (System.nanoTime() - tw) / 1_000_000
                val results = spec.getValue("questions").jsonArray.map { item ->
                    val o = item.jsonObject
                    val question = o.getValue("q").jsonPrimitive.content
                    val expected = o.getValue("sources").jsonArray.map { it.jsonPrimitive.content }
                    val groups = o.getValue("answer").jsonArray.map { g -> g.jsonArray.map { it.jsonPrimitive.content.lowercase() } }
                    val started = System.nanoTime()
                    val passages = c.retriever.search(question, c.embedder.query(question), SearchScope(), Answerer.PASSAGES)
                    val retrieved = passages.map { nameOf[it.chunk.sourceId] ?: "?" }
                    val thread = ChatThread(UUID.randomUUID().toString(), null, "", null, System.currentTimeMillis(), System.currentTimeMillis())
                    c.database.knowledge().upsertThread(thread)
                    var firstWordsMs = -1L
                    val asked = System.nanoTime()
                    val answer = c.answerer.ask(thread, question, SearchScope()) { text ->
                        if (firstWordsMs < 0 && text.isNotBlank()) firstWordsMs = (System.nanoTime() - asked) / 1_000_000
                    }
                    val totalMs = (System.nanoTime() - asked) / 1_000_000
                    c.database.knowledge().deleteThread(thread.id)
                    val citations = c.answerer.citations(answer)
                    val cited = citations.map { nameOf[it.sourceId] ?: "?" }.distinct()
                    val text = answer.text.lowercase()
                    buildJsonObject {
                        put("question", question)
                        o["page"]?.jsonPrimitive?.int?.let { page -> put("citedPage", citations.any { it.page == page }) }
                        put("retrievedAll", expected.all { it in retrieved })
                        put("answerCorrect", groups.all { g -> g.any { Regex("(?<!\\w|\\d,)${Regex.escape(it)}(?!\\w|,\\d)").containsMatchIn(text) } })
                        put("citedAll", expected.all { it in cited })
                        put("firstWordsMs", firstWordsMs)
                        put("totalMs", totalMs)
                        put("retrievalMs", (asked - started) / 1_000_000)
                        c.answerer.lastBenchmark?.let { b ->
                            put("prefillTokens", b.lastPrefillTokenCount)
                            put("prefillTokensPerSecond", b.lastPrefillTokensPerSecond)
                            put("timeToFirstTokenSeconds", b.timeToFirstTokenInSecond)
                            put("decodeTokens", b.lastDecodeTokenCount)
                            put("decodeTokensPerSecond", b.lastDecodeTokensPerSecond)
                        }
                        putJsonArray("retrieved") { retrieved.forEach { add(it) } }
                        putJsonArray("cited") { cited.forEach { add(it) } }
                        put("answer", answer.text)
                    }
                }
                report(
                    "retrieval-" + c.llm.modelName(LlmTask.ANSWERS).substringAfterLast(' ').lowercase(),
                    Json.encodeToString(
                        kotlinx.serialization.json.JsonObject.serializer(),
                        buildJsonObject {
                            put("indexMs", indexMs)
                            putJsonArray("documents") { documentReports.forEach { add(it) } }
                            put("warmUpMs", warmUpMs)
                            put("questions", results.size)
                            put("retrievedAll", results.count { it.getValue("retrievedAll").jsonPrimitive.content == "true" })
                            put("answerCorrect", results.count { it.getValue("answerCorrect").jsonPrimitive.content == "true" })
                            put("citedAll", results.count { it.getValue("citedAll").jsonPrimitive.content == "true" })
                            putJsonArray("results") { results.forEach { add(it) } }
                        },
                    ),
                )
            }
        } finally {
            if (args.getString("keep") != "true") runBlocking {
                meetingIds.values.forEach { c.repository.delete(it) }
                documentIds.values.forEach { c.indexer.deleteDocument(it) }
                c.retriever.invalidate()
            }
            ExperimentalFlags.enableBenchmark = false
            c.settings.update { previous }
        }
    }

    /** The process's peak resident memory so far, from /proc (VmHWM). */
    private fun peakRssMb(): Long =
        File("/proc/self/status").readLines().firstOrNull { it.startsWith("VmHWM:") }
            ?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull()?.div(1024) ?: -1

    /**
     * One short question and a reply of at most 64 tokens, to check that a model and backend give sensible text, and
     * how fast. Options: model=e4b|e2b, backend=cpu|gpu, activation=float16|float32, spec=true|false.
     */
    @OptIn(ExperimentalApi::class)
    @Test
    fun prompt() {
        val container = context.container
        val previous = container.settings.current
        args.getString("backend")?.let { b -> container.settings.update { it.copy(llmBackend = LlmBackend.valueOf(b.uppercase())) } }
        args.getString("model")?.let { m -> container.settings.update { it.copy(llmModel = LlmModel.valueOf(m.uppercase())) } }
        ExperimentalFlags.enableBenchmark = true
        try {
            runBlocking {
                container.models.refresh()
                val host = LlmHost(context, container.models, container.settings, this)
                args.getString("activation")?.let { host.gpuActivation = ActivationDataType.valueOf(it.uppercase()) }
                args.getString("spec")?.let { host.speculativeDecoding = it.toBoolean() }
                val t0 = System.nanoTime()
                host.withEngine { }
                val loadMs = (System.nanoTime() - t0) / 1_000_000
                val (text, speed) = host.withEngine<Pair<String, BenchmarkInfo>> { engine ->
                    host.conversation(engine, "You answer briefly.", maxOutputTokens = 64).use { conversation ->
                        conversation.sendMessage(Message.user("In two sentences, what is a meeting agenda for?")).text() to conversation.getBenchmarkInfo()
                    }
                }
                val tag = args.keySet().filter { it !in setOf("class", "input") }.sorted().joinToString("-") { "$it-${args.getString(it)}" }
                report(
                    "prompt-$tag",
                    Json.encodeToString(
                        kotlinx.serialization.json.JsonObject.serializer(),
                        buildJsonObject {
                            put("model", host.modelName())
                            put("loadMs", loadMs)
                            put("prefillTokensPerSecond", speed.lastPrefillTokensPerSecond)
                            put("decodeTokens", speed.lastDecodeTokenCount)
                            put("decodeTokensPerSecond", speed.lastDecodeTokensPerSecond)
                            put("text", text)
                        },
                    ),
                )
                host.trim()
            }
        } finally {
            ExperimentalFlags.enableBenchmark = false
            container.settings.update { previous }
        }
    }

    /** Gemma on its own: meeting notes from a synthetic meeting's ground-truth transcript (truth.json). */
    @OptIn(ExperimentalApi::class)
    @Test
    fun notes() {
        val truth = Json.parseToJsonElement(input().readText()).jsonObject
        val lines = truth.getValue("segments").jsonArray.map {
            val segment = it.jsonObject
            TranscriptLine(
                (segment.getValue("start").jsonPrimitive.double * 1000).toLong(),
                segment.getValue("speaker").jsonPrimitive.content,
                segment.getValue("text").jsonPrimitive.content,
            )
        }
        val container = context.container
        val previous = container.settings.current
        args.getString("backend")?.let { b -> container.settings.update { it.copy(llmBackend = LlmBackend.valueOf(b.uppercase())) } }
        args.getString("model")?.let { m -> container.settings.update { it.copy(llmModel = LlmModel.valueOf(m.uppercase())) } }
        args.getString("threads")?.let { t -> container.settings.update { it.copy(llmCpuThreads = t.toInt()) } }
        args.getString("context")?.let { t -> container.settings.update { it.copy(llmContextTokens = t.toInt()) } }
        ExperimentalFlags.enableBenchmark = true
        try {
            runBlocking {
                container.models.refresh()
                val host = LlmHost(context, container.models, container.settings, this)
                args.getString("activation")?.let { host.gpuActivation = ActivationDataType.valueOf(it.uppercase()) }
                args.getString("spec")?.let { host.speculativeDecoding = it.toBoolean() }
                val summarizer = Summarizer(host)
                summarizer.alwaysConstrained = args.getString("constrained") == "true"
                val t0 = System.nanoTime()
                host.withEngine { }
                val t1 = System.nanoTime()
                val notes = try {
                    summarizer.notes(lines)
                } finally {
                    report("notes-replies", Json.encodeToString(ListSerializer(String.serializer()), summarizer.benchmarkReplies))
                }
                val t2 = System.nanoTime()
                val speed = summarizer.lastBenchmark
                val settings = container.settings.current
                report(
                    "notes-${settings.llmModel.name.lowercase()}-${settings.llmBackend.name.lowercase()}${if (settings.llmCpuThreads > 0) "-t${settings.llmCpuThreads}" else ""}" +
                        (args.getString("context")?.let { "-c$it" } ?: "") +
                        (args.getString("activation")?.let { "-${it.lowercase()}" } ?: "") +
                        (args.getString("spec")?.let { "-spec-$it" } ?: "") +
                        if (summarizer.alwaysConstrained) "-constrained" else "",
                    Json.encodeToString(
                        kotlinx.serialization.json.JsonObject.serializer(),
                        buildJsonObject {
                            put("transcriptChars", Summarizer.format(lines).length)
                            put("contextTokens", host.contextTokens)
                            put("peakRssMb", peakRssMb())
                            put("loadMs", (t1 - t0) / 1_000_000)
                            put("notesMs", (t2 - t1) / 1_000_000)
                            if (speed != null) {
                                put("prefillTokens", speed.lastPrefillTokenCount)
                                put("prefillTokensPerSecond", speed.lastPrefillTokensPerSecond)
                                put("decodeTokens", speed.lastDecodeTokenCount)
                                put("decodeTokensPerSecond", speed.lastDecodeTokensPerSecond)
                                put("timeToFirstTokenSeconds", speed.timeToFirstTokenInSecond)
                            }
                            put("notes", Json.encodeToJsonElement(MeetingNotes.serializer(), notes))
                        },
                    ),
                )
                host.trim()
            }
        } finally {
            ExperimentalFlags.enableBenchmark = false
            container.settings.update { previous }
        }
    }

    /** Nemotron on its own: writes the speaker probabilities as <input>.diar.f32 for the parity check. */
    @Test
    fun diarize() {
        WavFile(input()).use { wav ->
            val threads = args.getString("threads")?.toInt() ?: 4
            val step = args.getString("step") ?: "step_int8.onnx"
            val t0 = System.nanoTime()
            val (diarizer, network) = NemotronDiarizer.open(files.model(ModelId.NEMOTRON.key), threads, step)
            val t1 = System.nanoTime()
            val activity = network.use { diarizer.run(wav) }
            val t2 = System.nanoTime()
            val bytes = ByteBuffer.allocate(activity.probs.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            bytes.asFloatBuffer().put(activity.probs)
            val suffix = if (step == "step_int8.onnx") "diar" else "diar-fp32"
            File(files.bench, "${input().nameWithoutExtension}.$suffix.f32").writeBytes(bytes.array())
            report(
                "diar",
                Json.encodeToString(
                    kotlinx.serialization.json.JsonObject.serializer(),
                    buildJsonObject {
                        put("audioMs", wav.durationMs)
                        put("threads", threads)
                        put("loadMs", (t1 - t0) / 1_000_000)
                        put("runMs", (t2 - t1) / 1_000_000)
                        put("realTimeFactor", (t2 - t1) / 1e6 / wav.durationMs)
                        putJsonArray("activeSeconds") { (0 until activity.speakers).forEach { add(activity.activeFrames(it) / 100.0) } }
                    },
                ),
            )
        }
    }

    @Test
    fun asr() {
        WavFile(input()).use { wav ->
            val t0 = System.nanoTime()
            val regions = SpeechDetector(files.model("${ModelId.SILERO_VAD.key}/silero_vad.onnx")).use { it.detect(wav) }
            val t1 = System.nanoTime()
            val threads = args.getString("threads")?.toInt() ?: 4
            Transcriber(files.model(ModelId.PARAKEET.key), threads).use { asr ->
                val t2 = System.nanoTime()
                val words = regions.flatMap { asr.transcribe(wav.read(it.start, it.end), it.start * 1000 / SAMPLE_RATE) }
                val t3 = System.nanoTime()
                val whole = asr.transcribe(wav.read(0, wav.sampleCount), 0)
                val raw = asr.decode(wav.read(0, wav.sampleCount))
                report(
                    "asr",
                    Json.encodeToString(
                        kotlinx.serialization.json.JsonObject.serializer(),
                        buildJsonObject {
                            put("audioMs", wav.durationMs)
                            put("vadMs", (t1 - t0) / 1_000_000)
                            put("asrLoadMs", (t2 - t1) / 1_000_000)
                            put("asrMs", (t3 - t2) / 1_000_000)
                            putJsonArray("regions") { regions.forEach { add("${it.start * 1000 / SAMPLE_RATE}-${it.end * 1000 / SAMPLE_RATE}") } }
                            put("text", words.joinToString(" ") { it.text })
                            put("wholeFileText", whole.joinToString(" ") { it.text })
                            put("rawText", raw.text)
                            putJsonArray("rawTokens") {
                                raw.tokens.forEachIndexed { i, t -> add("${raw.timestamps.getOrNull(i)}/${raw.durations?.getOrNull(i)} [$t]") }
                            }
                            putJsonArray("words") { words.forEach { add("${it.startMs}-${it.endMs} ${it.text}") } }
                        },
                    ),
                )
            }
        }
    }
}
