package dev.matthes.privatemeetingnotes.audio

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

const val SAMPLE_RATE = 16_000

/** Mono audio at 16 kHz, read by sample range. Samples are floats in [-1, 1). */
interface AudioSource {
    val sampleCount: Long

    /** Samples [start, end), clamped to the recording. */
    fun read(start: Long, end: Long): FloatArray
}

class FloatAudio(private val samples: FloatArray) : AudioSource {
    override val sampleCount: Long get() = samples.size.toLong()

    override fun read(start: Long, end: Long): FloatArray =
        samples.copyOfRange(start.coerceIn(0, sampleCount).toInt(), end.coerceIn(0, sampleCount).toInt())
}

/** Reads 16 kHz mono PCM16 WAV files. Positional reads, so one instance can serve several threads. */
class WavFile(file: File) : AudioSource, Closeable {
    private val channel = FileChannel.open(file.toPath(), StandardOpenOption.READ)
    private val dataOffset: Long
    override val sampleCount: Long

    init {
        val header = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        channel.read(header, 0)
        header.flip()
        require(header.remaining() == 12) { "Not a WAV file" }
        val riff = header.int
        header.int
        require(riff == RIFF && header.int == WAVE) { "Not a WAV file" }
        var position = 12L
        var format: Format? = null
        var data: Pair<Long, Long>? = null
        while (data == null && position + 8 <= channel.size()) {
            val chunk = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            channel.read(chunk, position)
            chunk.flip()
            val id = chunk.int
            val size = chunk.int.toLong() and 0xffffffffL
            val body = position + 8
            when (id) {
                FMT -> {
                    val fmt = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                    channel.read(fmt, body)
                    fmt.flip()
                    format = Format(fmt.short.toInt(), fmt.short.toInt(), fmt.int, fmt.getShort(14).toInt())
                }
                DATA -> {
                    // A recording interrupted before its header was fixed up still has all its samples.
                    val available = channel.size() - body
                    data = body to if (size == 0L || size > available) available else size
                }
            }
            position = body + size + (size and 1)
        }
        val fmt = requireNotNull(format) { "WAV file has no format chunk" }
        require(fmt.encoding == 1 && fmt.channels == 1 && fmt.sampleRate == SAMPLE_RATE && fmt.bits == 16) {
            "Expected 16 kHz mono PCM16, got $fmt"
        }
        val (offset, bytes) = requireNotNull(data) { "WAV file has no data chunk" }
        dataOffset = offset
        sampleCount = bytes / 2
    }

    val durationMs: Long get() = sampleCount * 1000 / SAMPLE_RATE

    /** Reads in blocks, so an hour of audio for the clustering pipeline costs its 230 MB of floats and no more. */
    override fun read(start: Long, end: Long): FloatArray {
        val from = start.coerceIn(0, sampleCount)
        val to = end.coerceIn(from, sampleCount)
        val samples = FloatArray((to - from).toInt())
        val block = ByteBuffer.allocate(minOf(samples.size, BLOCK_SAMPLES) * 2).order(ByteOrder.LITTLE_ENDIAN)
        var done = 0
        while (done < samples.size) {
            block.clear()
            block.limit(minOf(block.capacity(), (samples.size - done) * 2))
            var position = dataOffset + (from + done) * 2
            while (block.hasRemaining()) {
                val read = channel.read(block, position)
                if (read <= 0) break
                position += read
            }
            block.flip()
            val shorts = block.asShortBuffer()
            val count = shorts.remaining()
            if (count == 0) break
            for (i in 0 until count) samples[done + i] = shorts.get(i) / 32768f
            done += count
        }
        return if (done == samples.size) samples else samples.copyOf(done)
    }

    override fun close() = channel.close()

    private data class Format(val encoding: Int, val channels: Int, val sampleRate: Int, val bits: Int)

    companion object {
        private const val RIFF = 0x46464952
        private const val WAVE = 0x45564157
        private const val FMT = 0x20746d66
        private const val DATA = 0x61746164
        private const val BLOCK_SAMPLES = 65_536
    }
}

/**
 * Writes 16 kHz mono PCM16 WAV. The header's sizes are rewritten and the file synced every few seconds,
 * so a crash or a killed process loses seconds of audio, not the recording.
 */
class WavWriter(private val file: File, append: Boolean = false) : Closeable {
    private val raf = RandomAccessFile(file, "rw")
    private var dataBytes: Long
    private val buffer = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN)
    private var lastSync = System.nanoTime()

    init {
        if (append && raf.length() > HEADER_BYTES) {
            dataBytes = raf.length() - HEADER_BYTES
            raf.seek(raf.length())
        } else {
            raf.setLength(0)
            raf.write(header(0))
            dataBytes = 0
        }
    }

    val samplesWritten: Long get() = (dataBytes + buffer.position()) / 2

    fun write(samples: ShortArray, count: Int) {
        for (i in 0 until count) {
            if (!buffer.hasRemaining()) drain()
            buffer.putShort(samples[i])
        }
        if (System.nanoTime() - lastSync > SYNC_INTERVAL_NS) sync()
    }

    /** Pushes buffered audio to disk, rewrites the header and syncs the file. */
    fun sync() {
        drain()
        val end = raf.filePointer
        raf.seek(0)
        raf.write(header(dataBytes))
        raf.seek(end)
        raf.fd.sync()
        lastSync = System.nanoTime()
    }

    override fun close() {
        sync()
        raf.close()
    }

    private fun drain() {
        if (buffer.position() == 0) return
        raf.write(buffer.array(), 0, buffer.position())
        dataBytes += buffer.position()
        buffer.clear()
    }

    companion object {
        const val HEADER_BYTES = 44L
        private const val SYNC_INTERVAL_NS = 5_000_000_000L

        fun header(dataBytes: Long): ByteArray = ByteBuffer.allocate(HEADER_BYTES.toInt()).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt((36 + dataBytes).toInt())
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(SAMPLE_RATE)
            putInt(SAMPLE_RATE * 2)
            putShort(2)
            putShort(16)
            put("data".toByteArray())
            putInt(dataBytes.toInt())
        }.array()

        /** Rewrites the header of a recording that was cut off, from the file's length. Returns its sample count. */
        fun repair(file: File): Long {
            RandomAccessFile(file, "rw").use { raf ->
                if (raf.length() < HEADER_BYTES) {
                    raf.setLength(0)
                    raf.write(header(0))
                    return 0
                }
                val dataBytes = (raf.length() - HEADER_BYTES) and 1L.inv()
                raf.setLength(HEADER_BYTES + dataBytes)
                raf.seek(0)
                raf.write(header(dataBytes))
                raf.fd.sync()
                return dataBytes / 2
            }
        }
    }
}
