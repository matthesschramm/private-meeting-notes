package dev.matthes.privatemeetingnotes.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

class WavFileTest {
    private fun sample(i: Int) = ((i * 7919) % 65_536 - 32_768).toShort()

    private fun wav(samples: Int): File = File.createTempFile("wav", ".wav").apply {
        deleteOnExit()
        WavWriter(this).use { it.write(ShortArray(samples) { i -> sample(i) }, samples) }
    }

    @Test
    fun readsRangesAcrossBlocksExactly() {
        val file = wav(200_000)
        WavFile(file).use { audio ->
            assertEquals(200_000L, audio.sampleCount)
            for ((start, end) in listOf(0L to 200_000L, 65_530L to 131_080L, 199_990L to 300_000L, 5L to 5L)) {
                val expected = FloatArray((minOf(end, 200_000L) - start).toInt()) { sample(start.toInt() + it) / 32768f }
                assertArrayEquals(expected, audio.read(start, end), 0f)
            }
        }
    }

    @Test
    fun aRecordingCutShortReadsWhatIsThere() {
        val file = wav(100_000)
        WavFile(file).use { audio ->
            RandomAccessFile(file, "rw").use { it.setLength(it.length() - 2 * 30_000) }
            assertEquals(70_000 - 1_000, audio.read(1_000, 100_000).size)
        }
    }
}
