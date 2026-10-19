package dev.matthes.privatemeetingnotes.processing

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import dev.matthes.privatemeetingnotes.AppContainer
import dev.matthes.privatemeetingnotes.alignment.Aligner
import dev.matthes.privatemeetingnotes.asr.SpeechDetector
import dev.matthes.privatemeetingnotes.asr.SpeechRegion
import dev.matthes.privatemeetingnotes.asr.Transcriber
import dev.matthes.privatemeetingnotes.asr.Vocabulary
import dev.matthes.privatemeetingnotes.asr.Word
import dev.matthes.privatemeetingnotes.audio.SAMPLE_RATE
import dev.matthes.privatemeetingnotes.audio.WavFile
import dev.matthes.privatemeetingnotes.data.ActionItem
import dev.matthes.privatemeetingnotes.data.MeetingRepository
import dev.matthes.privatemeetingnotes.data.Speaker
import dev.matthes.privatemeetingnotes.data.Stage
import dev.matthes.privatemeetingnotes.data.Summary
import dev.matthes.privatemeetingnotes.data.Utterance
import dev.matthes.privatemeetingnotes.diagnostics.Log
import dev.matthes.privatemeetingnotes.diarization.ExtraSpeakers
import dev.matthes.privatemeetingnotes.diarization.FallbackDiarizer
import dev.matthes.privatemeetingnotes.diarization.NemotronDiarizer
import dev.matthes.privatemeetingnotes.diarization.SpeakerActivity
import dev.matthes.privatemeetingnotes.diarization.SpeakerGrouping
import dev.matthes.privatemeetingnotes.llm.Prompts
import dev.matthes.privatemeetingnotes.llm.Summarizer
import dev.matthes.privatemeetingnotes.llm.TranscriptLine
import dev.matthes.privatemeetingnotes.models.ModelId
import dev.matthes.privatemeetingnotes.speakers.VoiceEmbedder
import dev.matthes.privatemeetingnotes.speakers.VoiceProfile
import dev.matthes.privatemeetingnotes.speakers.Voices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.CancellationException

/** Files in a meeting's job folder. Each finished stage leaves a marker, so an interrupted job resumes after it. */
class Checkpoints(private val dir: File) {
    val regions = File(dir, "regions.json")
    val words = File(dir, "words.jsonl")
    val activity = File(dir, "activity.bin")
    val diarization = File(dir, "diarization.json")
    val profiles = File(dir, "profiles.json")

    fun done(stage: Stage) = marker(stage).exists()

    fun markDone(stage: Stage) = marker(stage).writeText(System.currentTimeMillis().toString())

    /** Forgets [stage] and everything after it, so they run again. */
    fun clearFrom(stage: Stage) {
        for (s in Stage.entries.filter { it.ordinal >= stage.ordinal }) {
            marker(s).delete()
            filesOf(s).forEach { it.delete() }
        }
    }

    private fun marker(stage: Stage) = File(dir, "${stage.name}.done")

    private fun filesOf(stage: Stage): List<File> = when (stage) {
        Stage.VAD -> listOf(regions)
        Stage.ASR -> listOf(words)
        Stage.DIARIZE -> listOf(activity, diarization)
        Stage.SPEAKERS -> listOf(profiles)
        else -> emptyList()
    }
}

@Serializable
private data class RegionWords(val region: Int, val words: List<Word>)

@Serializable
private data class DiarizationInfo(val method: String, val headCount: Int?)

/**
 * Turns a recording into a speaker-labelled transcript and notes, one stage at a time, with one heavy model in
 * memory at a time.
 */
class Pipeline(private val c: AppContainer) {
    private val json = Json { ignoreUnknownKeys = true }
    private val repository get() = c.repository

    /** Debug benchmarks only: use the clustering pipeline whenever a head count is known. */
    var forceFallback = false

    /** Debug benchmarks only: the longest audio the clustering pipeline clusters at once. */
    var fallbackChunkSamples = FallbackDiarizer.MAX_CHUNK_SAMPLES

    /** The stage a re-split with [headCount] must start from, given how the meeting was diarised before. */
    fun resplitStage(meetingId: String, headCount: Int?): Stage {
        val info = Checkpoints(c.files.jobDir(meetingId)).diarization.takeIf { it.exists() }
            ?.let { json.decodeFromString(DiarizationInfo.serializer(), it.readText()) }
        val needsSplit = headCount != null && headCount > NEMOTRON_MAX_SPEAKERS
        return if (info == null || needsSplit || info.method != NEMOTRON) Stage.DIARIZE else Stage.ALIGN
    }

    /** Makes [firstStage] and everything after it run again on the next [run]. */
    fun restartFrom(meetingId: String, firstStage: Stage) = Checkpoints(c.files.jobDir(meetingId)).clearFrom(firstStage)

    /** The stage the next [run] starts with, the first without a checkpoint. */
    fun firstUnfinished(meetingId: String): Stage {
        val checkpoints = Checkpoints(c.files.jobDir(meetingId))
        return Stage.entries.first { it == Stage.DONE || !checkpoints.done(it) }
    }

    /** Runs every stage that has not finished yet. */
    suspend fun run(
        meetingId: String,
        progress: (Stage, Float) -> Unit,
        isCancelled: () -> Boolean,
    ): Map<Stage, Long> = withContext(Dispatchers.Default) {
        val meeting = c.database.meetings().get(meetingId) ?: throw IllegalStateException("The meeting was deleted")
        val checkpoints = Checkpoints(c.files.jobDir(meetingId))
        val timings = linkedMapOf<Stage, Long>()
        val audioFile = c.files.recording(meetingId)
        val needsAudio = listOf(Stage.VAD, Stage.ASR, Stage.DIARIZE, Stage.SPEAKERS).any { !checkpoints.done(it) }
        if (needsAudio && !audioFile.exists()) throw IllegalStateException("This meeting's audio has been deleted")
        var audioSeconds = meeting.durationMs / 1000

        suspend fun stage(stage: Stage, block: suspend () -> Unit) {
            if (checkpoints.done(stage)) return
            if (isCancelled()) throw CancellationException("Processing cancelled")
            waitForChargerIfRequired(stage, progress)
            progress(stage, 0f)
            val started = System.nanoTime()
            block()
            val ms = (System.nanoTime() - started) / 1_000_000
            timings[stage] = ms
            checkpoints.markDone(stage)
            Log.i("stage $stage took $ms ms (audio $audioSeconds s)")
        }

        if (needsAudio) {
            WavFile(audioFile).use { audio ->
                c.database.meetings().setDuration(meetingId, audio.durationMs)
                audioSeconds = audio.durationMs / 1000
                stage(Stage.VAD) {
                    c.models.requireUsable(ModelId.SILERO_VAD)
                    val regions = SpeechDetector(c.models.file(ModelId.SILERO_VAD, "silero_vad.onnx")).use { vad ->
                        vad.detect(audio) { progress(Stage.VAD, it) }
                    }
                    checkpoints.regions.writeText(json.encodeToString(ListSerializer(SpeechRegion.serializer()), regions))
                    Log.i("found ${regions.size} speech regions")
                }
                stage(Stage.ASR) { transcribe(audio, checkpoints, progress, isCancelled) }
                stage(Stage.DIARIZE) {
                    val fallbackSpeakers = meeting.headCount?.takeIf { forceFallback }
                    val heads = meeting.headCount
                    val split = fallbackSpeakers == null && heads != null && heads > NEMOTRON_MAX_SPEAKERS
                    val activity = if (fallbackSpeakers != null) {
                        c.models.requireUsable(ModelId.PYANNOTE, ModelId.WESPEAKER)
                        val speech = json.decodeFromString(ListSerializer(SpeechRegion.serializer()), checkpoints.regions.readText())
                        FallbackDiarizer(
                            c.models.file(ModelId.PYANNOTE, "model.onnx"),
                            c.models.file(ModelId.WESPEAKER, WESPEAKER_FILE),
                            fallbackSpeakers,
                            THREADS,
                            fallbackChunkSamples,
                        ).use { it.run(audio, speech, { p -> progress(Stage.DIARIZE, p) }, isCancelled) }
                    } else {
                        c.models.requireUsable(ModelId.NEMOTRON)
                        if (split) c.models.requireUsable(ModelId.WESPEAKER)
                        val (diarizer, network) = NemotronDiarizer.open(c.models.dir(ModelId.NEMOTRON), THREADS)
                        network.use {
                            val share = if (split) 0.5f else 1f
                            val found = diarizer.run(audio, { p -> progress(Stage.DIARIZE, p * share) }, isCancelled)
                            if (!split) return@use found
                            VoiceEmbedder(c.models.file(ModelId.WESPEAKER, WESPEAKER_FILE), 2).use { embedder ->
                                ExtraSpeakers.split(found, audio, heads, { diarizer.run(it, {}, isCancelled) }, embedder) { p ->
                                    progress(Stage.DIARIZE, share + p * (1 - share))
                                }
                            }
                        }
                    }
                    activity.write(checkpoints.activity)
                    val method = when {
                        fallbackSpeakers != null -> FALLBACK
                        split -> NEMOTRON_SPLIT
                        else -> NEMOTRON
                    }
                    checkpoints.diarization.writeText(json.encodeToString(DiarizationInfo.serializer(), DiarizationInfo(method, meeting.headCount)))
                }
                stage(Stage.SPEAKERS) {
                    c.models.requireUsable(ModelId.WESPEAKER)
                    val activity = SpeakerActivity.read(checkpoints.activity)
                    val profiles = VoiceEmbedder(c.models.file(ModelId.WESPEAKER, WESPEAKER_FILE), 2).use {
                        Voices.profiles(audio, activity, it)
                    }
                    checkpoints.profiles.writeText(json.encodeToString(ListSerializer(VoiceProfile.serializer()), profiles))
                }
            }
        }
        stage(Stage.ALIGN) { align(meetingId, checkpoints) }
        stage(Stage.SUMMARY) { summarise(meetingId, progress) }
        stage(Stage.INDEX) {
            if (c.indexer.available) c.indexer.indexMeeting(meetingId) { progress(Stage.INDEX, it) } else Log.w("search index skipped: model not on the phone")
        }
        timings
    }

    private fun transcribe(audio: WavFile, checkpoints: Checkpoints, progress: (Stage, Float) -> Unit, isCancelled: () -> Boolean) {
        c.models.requireUsable(ModelId.PARAKEET)
        val regions = json.decodeFromString(ListSerializer(SpeechRegion.serializer()), checkpoints.regions.readText())
        val finished = readWords(checkpoints).map { it.region }.toSet()
        if (finished.size == regions.size) return
        Transcriber(c.models.dir(ModelId.PARAKEET), c.settings.current.asrThreads).use { asr ->
            for ((i, region) in regions.withIndex()) {
                if (i in finished) continue
                if (isCancelled()) throw CancellationException("Processing cancelled")
                // Pad each region a little, but never into its neighbours, so no word is transcribed twice.
                val previousEnd = regions.getOrNull(i - 1)?.end ?: 0L
                val nextStart = regions.getOrNull(i + 1)?.start ?: audio.sampleCount
                val start = maxOf(region.start - PAD_SAMPLES, (previousEnd + region.start) / 2)
                val end = minOf(region.end + PAD_SAMPLES, (region.end + nextStart) / 2)
                val words = asr.transcribe(audio.read(start, end), start * 1000 / SAMPLE_RATE)
                checkpoints.words.appendText(json.encodeToString(RegionWords.serializer(), RegionWords(i, words)) + "\n")
                progress(Stage.ASR, (i + 1f) / regions.size)
            }
        }
    }

    private fun readWords(checkpoints: Checkpoints): List<RegionWords> {
        if (!checkpoints.words.exists()) return emptyList()
        // A line cut off by a crash is dropped; that region is simply transcribed again.
        return checkpoints.words.readLines().mapNotNull { line ->
            runCatching { json.decodeFromString(RegionWords.serializer(), line) }.getOrNull()
        }.distinctBy { it.region }
    }

    private suspend fun align(meetingId: String, checkpoints: Checkpoints) {
        val meeting = c.database.meetings().get(meetingId) ?: return
        val heard = readWords(checkpoints).sortedBy { it.region }.flatMap { it.words }
        val words = Vocabulary.correct(heard, Vocabulary.parse(c.settings.current.vocabulary))
        val activity = SpeakerActivity.read(checkpoints.activity)
        val profiles = json.decodeFromString(ListSerializer(VoiceProfile.serializer()), checkpoints.profiles.readText())
        val groups = SpeakerGrouping.groups(activity, profiles, meeting.headCount)
        val utterances = Aligner().align(words, activity.regrouped(groups))
        val order = utterances.map { it.speaker }.distinct()
        val labelOf = order.withIndex().associate { (label, group) -> group to label }

        val profileBySpeaker = profiles.associateBy { it.speaker }
        val newVoices = order.map { group -> groupVoice(groups[group].mapNotNull { profileBySpeaker[it] }) }
        val names = carryOverNames(c.database.transcripts().speakers(meetingId), newVoices)
        val found = order.mapIndexed { label, group ->
            val voice = newVoices[label]
            Speaker(
                meetingId = meetingId,
                label = label,
                displayName = names[label]?.displayName,
                voiceprintId = names[label]?.voiceprintId,
                autoNamed = names[label]?.autoNamed ?: false,
                embedding = voice.first?.let { Voices.toBytes(it) },
                speechMs = voice.second,
            )
        }
        val speakers = c.voiceprints.nameKnownVoices(found)
        c.database.transcripts().replace(
            meetingId,
            speakers,
            utterances.mapIndexed { ordinal, u ->
                Utterance(
                    meetingId = meetingId,
                    ordinal = ordinal,
                    speakerLabel = labelOf.getValue(u.speaker),
                    startMs = u.startMs,
                    endMs = u.endMs,
                    text = u.text,
                    wordsJson = repository.encodeWords(u.words),
                )
            },
        )
        Log.i("transcript has ${utterances.size} utterances, ${words.size} words, ${speakers.size} speakers, ${speakers.count { it.autoNamed }} named from voiceprints")
    }

    /** A group's voice: its members' embeddings weighted by how much each spoke, and their total speech. */
    private fun groupVoice(members: List<VoiceProfile>): Pair<FloatArray?, Long> {
        val withVoice = members.filter { it.embedding != null }
        val speech = members.sumOf { it.speechMs }
        if (withVoice.isEmpty()) return Pair(null, speech)
        val sum = FloatArray(withVoice.first().embedding!!.size)
        for (m in withVoice) {
            val weight = maxOf(1L, m.speechMs).toFloat()
            m.embedding!!.forEachIndexed { i, x -> sum[i] += x * weight }
        }
        return Pair(dev.matthes.privatemeetingnotes.speakers.normalised(sum), speech)
    }

    /** After a re-split, gives each new speaker the name of the previously named speaker whose voice it matches. */
    private fun carryOverNames(previous: List<Speaker>, voices: List<Pair<FloatArray?, Long>>): Map<Int, Speaker> {
        val named = previous.filter { it.displayName != null && it.embedding != null }.toMutableList()
        val result = mutableMapOf<Int, Speaker>()
        val pairs = voices.withIndex().flatMap { (label, voice) ->
            val v = voice.first ?: return@flatMap emptyList()
            named.map { old -> Triple(label, old, Voices.cosine(v, Voices.fromBytes(old.embedding!!))) }
        }.filter { it.third >= NAME_MATCH }.sortedByDescending { it.third }
        for ((label, old, _) in pairs) {
            if (label in result || old !in named) continue
            result[label] = old
            named -= old
        }
        return result
    }

    private suspend fun summarise(meetingId: String, progress: (Stage, Float) -> Unit) {
        if (!c.llm.available()) {
            Log.w("summary skipped: language model not on the phone")
            return
        }
        val lines = transcriptLines(meetingId)
        if (lines.isEmpty()) return
        val notes = c.summarizer.notes(lines, Vocabulary.terms(c.settings.current.vocabulary)) { progress(Stage.SUMMARY, it) }
        val meeting = c.database.meetings().get(meetingId) ?: return
        c.database.summaries().upsert(
            Summary(
                meetingId = meetingId,
                title = notes.title.ifBlank { null },
                overview = notes.overview,
                keyPointsJson = repository.encodeList(notes.keyPoints),
                decisionsJson = repository.encodeList(notes.decisions),
                openQuestionsJson = repository.encodeList(notes.openQuestions),
                tidyNotes = null,
                promptVersion = Prompts.VERSION,
                model = c.llm.modelName(),
                createdAt = System.currentTimeMillis(),
            ),
        )
        c.database.summaries().replaceActions(
            meetingId,
            notes.actionItems.filter { it.task.isNotBlank() }.mapIndexed { i, a ->
                ActionItem(
                    meetingId = meetingId,
                    ordinal = i,
                    owner = a.owner.trim().takeUnless { it.isEmpty() || it.equals("Unassigned", ignoreCase = true) },
                    task = a.task.trim(),
                    due = a.due.trim().ifEmpty { null },
                    evidenceMs = Summarizer.parseClock(a.evidenceTime),
                    done = false,
                )
            },
        )
        if (MeetingRepository.isDefaultTitle(meeting.title) && notes.title.isNotBlank()) {
            c.database.meetings().setTitle(meetingId, notes.title.trim())
        }
    }

    suspend fun transcriptLines(meetingId: String): List<TranscriptLine> {
        val speakers = c.database.transcripts().speakers(meetingId).associateBy { it.label }
        return c.database.transcripts().utterances(meetingId).map { u ->
            TranscriptLine(u.startMs, speakers[u.speakerLabel]?.let(MeetingRepository::displayName) ?: MeetingRepository.defaultSpeakerName(u.speakerLabel), u.text)
        }
    }

    private suspend fun waitForChargerIfRequired(stage: Stage, progress: (Stage, Float) -> Unit) {
        while (c.settings.current.processOnlyWhileCharging && !isCharging(c.context)) {
            progress(stage, WAITING_FOR_CHARGER)
            delay(30_000)
        }
    }

    companion object {
        const val NEMOTRON_MAX_SPEAKERS = 8
        /** Progress value meaning "paused until the phone is on a charger". */
        const val WAITING_FOR_CHARGER = -1f
        private const val THREADS = 4
        private const val PAD_SAMPLES = SAMPLE_RATE / 4L
        private const val NAME_MATCH = 0.6f
        private const val NEMOTRON = "nemotron"
        private const val NEMOTRON_SPLIT = "nemotron-split"
        private const val FALLBACK = "fallback"
        private const val WESPEAKER_FILE = "wespeaker_en_voxceleb_resnet34_LM.onnx"

        fun isCharging(context: Context): Boolean {
            val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        }
    }
}
