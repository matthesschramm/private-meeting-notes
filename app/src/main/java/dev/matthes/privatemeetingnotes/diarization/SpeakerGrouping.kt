package dev.matthes.privatemeetingnotes.diarization

import dev.matthes.privatemeetingnotes.speakers.VoiceProfile
import dev.matthes.privatemeetingnotes.speakers.Voices

/**
 * Decides which detected speakers are real people: drops "ghost" speakers with almost no speech, and when the expected
 * head count is known and more voices were found, merges the most similar voices until the count matches.
 */
object SpeakerGrouping {
    private const val GHOST_MS = 1_000L

    /** Groups of detected speaker indices, one group per person, ordered by when they first speak. */
    fun groups(activity: SpeakerActivity, profiles: List<VoiceProfile>, headCount: Int?): List<List<Int>> =
        merge(profiles, headCount).sortedBy { group -> group.minOf { firstActiveFrame(activity, it) } }

    /** Groups of [profiles]' speakers, ghosts left out, with the most similar voices merged until [headCount] remain. */
    fun merge(profiles: List<VoiceProfile>, headCount: Int?): List<List<Int>> {
        val real = profiles.filter { it.speechMs >= GHOST_MS }.map { it.speaker }.ifEmpty { profiles.map { it.speaker } }
        val groups = real.map { mutableListOf(it) }.toMutableList()
        val vectors = profiles.associate { it.speaker to it.vector() }
        if (headCount != null && headCount >= 1) {
            while (groups.size > headCount) {
                var best: Pair<Int, Int>? = null
                var bestSimilarity = Float.NEGATIVE_INFINITY
                for (i in groups.indices) {
                    for (j in i + 1 until groups.size) {
                        val similarity = similarity(groups[i], groups[j], vectors)
                        if (similarity > bestSimilarity) {
                            bestSimilarity = similarity
                            best = Pair(i, j)
                        }
                    }
                }
                val (keep, merge) = best ?: break
                groups[keep] += groups.removeAt(merge)
            }
        }
        return groups
    }

    /** Average similarity between two groups' voices; speakers without a voice embedding count as dissimilar. */
    private fun similarity(a: List<Int>, b: List<Int>, vectors: Map<Int, FloatArray?>): Float {
        var total = 0f
        var count = 0
        for (x in a) for (y in b) {
            val va = vectors[x]
            val vb = vectors[y]
            total += if (va != null && vb != null) Voices.cosine(va, vb) else -1f
            count++
        }
        return total / count
    }

    private fun firstActiveFrame(activity: SpeakerActivity, speaker: Int): Int =
        (0 until activity.frames).firstOrNull { activity[it, speaker] > 0.5f } ?: Int.MAX_VALUE
}
