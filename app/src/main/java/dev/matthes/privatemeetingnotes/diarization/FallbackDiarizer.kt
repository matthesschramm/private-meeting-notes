package dev.matthes.privatemeetingnotes.diarization

import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationSegment
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import dev.matthes.privatemeetingnotes.asr.SpeechRegion
import dev.matthes.privatemeetingnotes.audio.AudioSource
import dev.matthes.privatemeetingnotes.audio.FloatAudio
import dev.matthes.privatemeetingnotes.audio.SAMPLE_RATE
import dev.matthes.privatemeetingnotes.diagnostics.Log
import dev.matthes.privatemeetingnotes.speakers.VoiceEmbedder
import dev.matthes.privatemeetingnotes.speakers.VoiceProfile
import dev.matthes.privatemeetingnotes.speakers.Voices
import java.io.File
import java.util.concurrent.CancellationException

/**
 * The clustering pipeline first planned for meetings of more than eight people: pyannote segmentation 3.0, WeSpeaker
 * embeddings and clustering into [headCount] speakers, through sherpa-onnx. It gave far more words to the wrong
 * person than Nemotron on every synthetic meeting, so processing uses Nemotron and [ExtraSpeakers] instead, and this
 * stays for comparison in the benchmarks. sherpa-onnx clusters audio it holds in memory, about 230 MB per hour, so a
 * longer recording is clustered in chunks of up to [maxChunkSamples], cut in pauses, and the chunks' speakers are
 * then matched up by voice.
 */
class FallbackDiarizer(
    segmentationModel: File,
    private val embeddingModel: File,
    private val headCount: Int,
    private val threads: Int,
    private val maxChunkSamples: Long = MAX_CHUNK_SAMPLES,
) : AutoCloseable {
    private val diarization = OfflineSpeakerDiarization(
        config = OfflineSpeakerDiarizationConfig(
            segmentation = OfflineSpeakerSegmentationModelConfig(
                // Windows overlapping by half instead of the default 90% cut the voice embeddings, the slow part,
                // about five-fold (125 s for 4.5 minutes of audio with the default).
                pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(model = segmentationModel.path, windowShiftRatio = 0.5f),
                numThreads = threads,
            ),
            embedding = SpeakerEmbeddingExtractorConfig(model = embeddingModel.path, numThreads = threads),
            clustering = FastClusteringConfig(numClusters = headCount),
            minDurationOn = 0.3f,
            minDurationOff = 0.5f,
        ),
    )

    /** [speech] is where voice activity detection heard speech; chunks are cut in the pauses between. */
    fun run(
        audio: AudioSource,
        speech: List<SpeechRegion>,
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): SpeakerActivity {
        val chunks = cuts(audio.sampleCount, speech, maxChunkSamples, CUT_SLACK_SAMPLES).zipWithNext()
        if (chunks.size == 1) {
            val segments = diarization.processWithCallback(audio.read(0, audio.sampleCount), Progress(onProgress), 0L)
            return activity(segments, frames(audio.sampleCount), labels(segments))
        }
        // Each chunk's clusters get labels of their own, after the previous chunk's, until matched up by voice.
        val turns = mutableListOf<ChunkTurn>()
        val profiles = mutableListOf<VoiceProfile>()
        var first = 0
        VoiceEmbedder(embeddingModel, threads).use { embedder ->
            for ((k, chunk) in chunks.withIndex()) {
                if (isCancelled()) throw CancellationException("Processing cancelled")
                val (start, end) = chunk
                if (speechIn(speech, start, end) < headCount * MIN_SPEECH_PER_CLUSTER_SAMPLES) {
                    Log.i("chunk ${k + 1} of ${chunks.size} skipped: hardly any speech")
                    continue
                }
                val samples = audio.read(start, end)
                val segments = diarization.processWithCallback(samples, Progress { onProgress((k + it * CLUSTERING_SHARE) / chunks.size) }, 0L)
                val labels = labels(segments)
                val local = activity(segments, frames(end - start), labels)
                profiles += Voices.profiles(FloatAudio(samples), local, embedder).map { it.copy(speaker = first + it.speaker) }
                val offset = start.toDouble() / SAMPLE_RATE
                segments.forEach { turns += ChunkTurn(first + it.speaker, it.start + offset, it.end + offset) }
                first += labels
                onProgress((k + 1f) / chunks.size)
            }
        }
        val groups = SpeakerGrouping.merge(profiles, headCount)
        val groupOf = groups.withIndex().flatMap { (g, members) -> members.map { it to g } }.toMap()
        Log.i("${chunks.size} chunks, ${profiles.size} chunk speakers grouped into ${groups.size}")
        val frames = frames(audio.sampleCount)
        val speakers = maxOf(1, groups.size)
        val probs = FloatArray(frames * speakers)
        for (turn in turns) {
            val group = groupOf[turn.label] ?: continue
            for (f in frameRange(turn.start, turn.end, frames)) probs[f * speakers + group] = 1f
        }
        return SpeakerActivity(frames, speakers, probs)
    }

    private class ChunkTurn(val label: Int, val start: Double, val end: Double)

    override fun close() = diarization.release()

    /**
     * sherpa-onnx calls back through JNI by the signature invoke(IIJ)Ljava/lang/Integer;. A lambda compiles to a
     * desugared class without that method and crashes the process, so this must stay a real class.
     */
    class Progress(private val onProgress: (Float) -> Unit) : (Int, Int, Long) -> Int {
        override fun invoke(done: Int, total: Int, arg: Long): Int {
            onProgress(if (total > 0) done.toFloat() / total else 0f)
            return 0
        }
    }

    companion object {
        /** An hour of audio, 230 MB as floats, is the most handed to sherpa-onnx at once. */
        const val MAX_CHUNK_SAMPLES = 60L * 60 * SAMPLE_RATE
        /** How far a cut may move from an even split to land in a pause. */
        private const val CUT_SLACK_SAMPLES = 60L * SAMPLE_RATE
        /** A chunk with less speech than this per cluster is a break, not worth clustering. */
        private const val MIN_SPEECH_PER_CLUSTER_SAMPLES = 10L * SAMPLE_RATE
        /** Share of a chunk's time spent clustering; the rest goes on the voices of its speakers. */
        private const val CLUSTERING_SHARE = 0.9f
        private const val FRAME_SAMPLES = 160

        /**
         * Where to cut [sampleCount] samples into the fewest chunks of at most [maxSamples]: even splits, each moved
         * to the middle of the longest pause between [speech] regions within [slack] of it. Starts with 0 and ends
         * with [sampleCount].
         */
        internal fun cuts(sampleCount: Long, speech: List<SpeechRegion>, maxSamples: Long, slack: Long): List<Long> {
            val chunks = maxOf(1L, (sampleCount + maxSamples - 2 * slack - 1) / (maxSamples - 2 * slack))
            val sorted = speech.sortedBy { it.start }
            val pauseStarts = listOf(0L) + sorted.map { it.end }
            val pauseEnds = sorted.map { it.start } + sampleCount
            val cuts = mutableListOf(0L)
            for (i in 1 until chunks) {
                val even = sampleCount * i / chunks
                val (from, to) = Pair(even - slack, even + slack)
                var best = even
                var longest = 0L
                for (p in pauseStarts.indices) {
                    val start = maxOf(pauseStarts[p], from)
                    val end = minOf(pauseEnds[p], to)
                    if (end - start > longest) {
                        longest = end - start
                        best = (start + end) / 2
                    }
                }
                cuts += best
            }
            cuts += sampleCount
            return cuts
        }

        private fun speechIn(speech: List<SpeechRegion>, start: Long, end: Long): Long =
            speech.sumOf { maxOf(0L, minOf(it.end, end) - maxOf(it.start, start)) }

        private fun frames(samples: Long) = (samples / FRAME_SAMPLES).toInt()

        private fun frameRange(startSeconds: Double, endSeconds: Double, frames: Int) =
            (startSeconds * 100).toInt().coerceAtLeast(0) until (endSeconds * 100).toInt().coerceAtMost(frames)

        private fun labels(segments: Array<OfflineSpeakerDiarizationSegment>) = (segments.maxOfOrNull { it.speaker } ?: 0) + 1

        private fun activity(segments: Array<OfflineSpeakerDiarizationSegment>, frames: Int, speakers: Int): SpeakerActivity {
            val probs = FloatArray(frames * speakers)
            for (segment in segments) {
                for (f in frameRange(segment.start.toDouble(), segment.end.toDouble(), frames)) probs[f * speakers + segment.speaker] = 1f
            }
            return SpeakerActivity(frames, speakers, probs)
        }
    }
}
