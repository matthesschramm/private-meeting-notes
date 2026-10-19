package dev.matthes.privatemeetingnotes.diarization

import dev.matthes.privatemeetingnotes.speakers.VoiceProfile
import dev.matthes.privatemeetingnotes.speakers.normalised
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeakerGroupingTest {
    /** Like the embedding model's voices: a large part every voice shares, plus a smaller part of each person's own. */
    private fun voice(person: Int, noise: Int) = FloatArray(16) { 1f }.also {
        it[person] += 2f
        it[8 + noise] += 0.4f
    }.let { normalised(it).toList() }

    @Test
    fun theSamePeopleInEachChunkBecomeOneGroupEach() {
        // Two chunks of four clusters each, labels chunk * 4 + cluster, for three people. In both, the clustering had
        // to find one cluster too many: the first split Ann in two, the second kept a moment of noise apart.
        val profiles = listOf(
            VoiceProfile(0, 600_000, voice(0, 0)),
            VoiceProfile(1, 400_000, voice(1, 1)),
            VoiceProfile(2, 300_000, voice(2, 2)),
            VoiceProfile(3, 90_000, voice(0, 3)),
            VoiceProfile(4, 500_000, voice(2, 4)),
            VoiceProfile(5, 700_000, voice(0, 5)),
            VoiceProfile(6, 400, voice(3, 6)),
            VoiceProfile(7, 200_000, voice(1, 7)),
        )
        val groups = SpeakerGrouping.merge(profiles, 3).map { it.sorted() }.sortedBy { it.first() }
        assertEquals(listOf(listOf(0, 3, 5), listOf(1, 7), listOf(2, 4)), groups)
    }
}
