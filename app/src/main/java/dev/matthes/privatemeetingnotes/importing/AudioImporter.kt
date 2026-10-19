package dev.matthes.privatemeetingnotes.importing

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import dev.matthes.privatemeetingnotes.audio.SAMPLE_RATE
import dev.matthes.privatemeetingnotes.audio.WavWriter
import dev.matthes.privatemeetingnotes.diagnostics.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

/**
 * Decodes an audio file the phone can play (M4A, MP3, WAV, AAC, OGG/Opus and more) with MediaExtractor and
 * MediaCodec, mixes it down to mono, resamples it to 16 kHz and writes it as the same WAV format as recordings.
 */
class AudioImporter(private val context: Context) {

    fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    /** Returns the number of samples written. */
    suspend fun import(uri: Uri, out: File, onProgress: (Float) -> Unit): Long = withContext(Dispatchers.IO) {
        // MediaExtractor needs a file it can seek in; apps sharing audio, such as cloud storage, may only offer a stream.
        val local = File.createTempFile("import", null, context.cacheDir)
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw UnsupportedAudioException("The file could not be opened")
            input.use { source -> local.outputStream().use { source.copyTo(it, COPY_BUFFER) } }
            decode(local, out, onProgress)
        } finally {
            local.delete()
        }
    }

    private suspend fun decode(source: File, out: File, onProgress: (Float) -> Unit): Long {
        val extractor = MediaExtractor()
        extractor.setDataSource(source.path)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: run {
            extractor.release()
            throw UnsupportedAudioException("This file has no audio track")
        }
        extractor.selectTrack(track)
        val inputFormat = extractor.getTrackFormat(track)
        val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!
        val durationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) inputFormat.getLong(MediaFormat.KEY_DURATION) else 0L
        val codec = try {
            MediaCodec.createDecoderByType(mime).apply {
                configure(inputFormat, null, null, 0)
                start()
            }
        } catch (e: Exception) {
            extractor.release()
            throw UnsupportedAudioException("This phone can't decode $mime audio")
        }

        var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var rate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var encoding = AudioFormat.ENCODING_PCM_16BIT
        var resampler = Resampler(rate, SAMPLE_RATE)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        val started = System.nanoTime()
        try {
            return WavWriter(out).use { writer ->
                while (!outputDone) {
                    coroutineContext.ensureActive()
                    var busy = false
                    while (!inputDone) {
                        val index = codec.dequeueInputBuffer(0)
                        if (index < 0) break
                        val buffer = codec.getInputBuffer(index)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                        busy = true
                    }
                    // Waits for the decoder only when there was nothing to feed or collect.
                    while (!outputDone) {
                        val index = codec.dequeueOutputBuffer(info, if (busy) 0 else TIMEOUT_US)
                        if (index == MediaCodec.INFO_TRY_AGAIN_LATER) break
                        busy = true
                        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            val format = codec.outputFormat
                            channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            } else {
                                AudioFormat.ENCODING_PCM_16BIT
                            }
                            resampler = Resampler(rate, SAMPLE_RATE)
                        } else if (index >= 0) {
                            val buffer = codec.getOutputBuffer(index)!!.order(ByteOrder.LITTLE_ENDIAN)
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val mono = downmix(buffer, channels, encoding)
                            codec.releaseOutputBuffer(index, false)
                            write(writer, resampler.process(mono))
                            if (durationUs > 0) onProgress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
                write(writer, resampler.flush())
                Log.i("imported ${writer.samplesWritten / SAMPLE_RATE} s of $mime in ${(System.nanoTime() - started) / 1_000_000} ms")
                writer.samplesWritten
            }
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
        }
    }

    private fun downmix(buffer: java.nio.ByteBuffer, channels: Int, encoding: Int): FloatArray {
        val samples = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            FloatArray(buffer.remaining() / 4).also { buffer.asFloatBuffer().get(it) }
        } else {
            val shorts = ShortArray(buffer.remaining() / 2).also { buffer.asShortBuffer().get(it) }
            FloatArray(shorts.size) { shorts[it] / 32768f }
        }
        if (channels == 1) return samples
        return FloatArray(samples.size / channels) { frame ->
            var sum = 0f
            for (c in 0 until channels) sum += samples[frame * channels + c]
            sum / channels
        }
    }

    private fun write(writer: WavWriter, samples: FloatArray) {
        if (samples.isEmpty()) return
        val shorts = ShortArray(samples.size) { (samples[it].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
        writer.write(shorts, shorts.size)
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
        const val COPY_BUFFER = 1 shl 20
    }
}

class UnsupportedAudioException(message: String) : Exception(message)
