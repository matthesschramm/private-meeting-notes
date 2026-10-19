package dev.matthes.privatemeetingnotes.speakers

import dev.matthes.privatemeetingnotes.data.Speaker
import dev.matthes.privatemeetingnotes.data.Voiceprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class VoiceprintsTest {
    /** Like the embedding model's voices: a large part every voice shares, plus a smaller part of each person's own. */
    private fun voice(vararg own: Pair<Int, Float>): FloatArray {
        val v = FloatArray(16) { 1f }
        for ((axis, weight) in own) v[axis] += weight
        return normalised(v)
    }

    private fun person(axis: Int) = voice(axis to 2f)

    private fun speaker(label: Int, v: FloatArray?, name: String? = null, voiceprintId: String? = null) =
        Speaker("m", label, name, voiceprintId, false, v?.let { Voices.toBytes(it) }, 60_000)

    private fun print(name: String, v: FloatArray) = Voiceprint(name.lowercase(), name, Voices.toBytes(v), 1, false, 0, 0)

    private val known = listOf(print("James", person(0)), print("Olivia", person(1)), print("Raj", person(2)))

    @Test
    fun namesKnownVoicesAndLeavesNewOnesUnnamed() {
        val named = Voiceprints.match(
            listOf(speaker(0, voice(0 to 2f, 5 to 0.3f)), speaker(1, voice(1 to 2f, 6 to 0.3f)), speaker(2, person(3))),
            known,
        )
        assertEquals(listOf("James", "Olivia", null), named.map { it.displayName })
        assertTrue(named[0].autoNamed && named[1].autoNamed)
    }

    @Test
    fun sharedPartAloneDoesNotMatch() {
        // Raw cosine between these two is about 0.8, only because of what all voices share.
        val named = Voiceprints.match(listOf(speaker(0, person(4)), speaker(1, person(5)), speaker(2, person(6))), known)
        assertEquals(listOf(null, null, null), named.map { it.displayName })
    }

    @Test
    fun twoAlmostEqualVoiceprintsNameNobody() {
        val prints = known + print("Emily", voice(0 to 2f, 7 to 0.2f))
        val named = Voiceprints.match(listOf(speaker(0, voice(0 to 2f, 7 to 0.1f)), speaker(1, person(3)), speaker(2, person(4))), prints)
        assertEquals(null, named[0].displayName)
    }

    @Test
    fun aVoiceprintCloseToTwoSpeakersNamesNeither() {
        val named = Voiceprints.match(
            listOf(speaker(0, voice(0 to 2f, 5 to 0.2f)), speaker(1, voice(0 to 2f, 6 to 0.2f)), speaker(2, person(3))),
            known,
        )
        assertEquals(listOf(null, null, null), named.map { it.displayName })
    }

    @Test
    fun tooFewVoicesToTellApartNamesNobody() {
        val named = Voiceprints.match(listOf(speaker(0, voice(0 to 2f, 5 to 0.3f))), listOf(print("James", person(0))))
        assertEquals(null, named[0].displayName)
    }

    @Test
    fun keepsNamesAlreadyGivenAndDoesNotReuseTheirVoiceprint() {
        val named = Voiceprints.match(
            listOf(speaker(0, person(0), "James", "james"), speaker(1, voice(0 to 2f, 5 to 0.3f)), speaker(2, null), speaker(3, person(3))),
            known,
        )
        assertEquals(listOf("James", null, null, null), named.map { it.displayName })
    }

    @Test
    fun blendingMovesTheVoiceprintTowardsNewMeetings() {
        val a = FloatArray(4).also { it[0] = 1f }
        val b = FloatArray(4).also { it[1] = 1f }
        val blended = Voiceprints.blend(a, samples = 1, voice = b)
        assertEquals(1.0, sqrt(blended.sumOf { (it * it).toDouble() }), 1e-5)
        assertEquals(45.0, Math.toDegrees(kotlin.math.atan2(blended[1].toDouble(), blended[0].toDouble())), 1e-3)
    }
}
