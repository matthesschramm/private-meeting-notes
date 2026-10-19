package dev.matthes.privatemeetingnotes.speakers

import dev.matthes.privatemeetingnotes.data.AppDatabase
import dev.matthes.privatemeetingnotes.data.Settings
import dev.matthes.privatemeetingnotes.data.Speaker
import dev.matthes.privatemeetingnotes.data.Voiceprint
import java.util.UUID

/**
 * Remembers the voices of speakers the user names, on this phone only, and names those voices when they speak in
 * later meetings. A voiceprint is the average of the voice embeddings of the speakers it was learned from.
 */
class Voiceprints(private val db: AppDatabase, private val settings: Settings) {

    /**
     * Learns [name]'s voice from [speaker] and returns the voiceprint to link the speaker to, or null when voices
     * aren't remembered or there is no name. [isSelf] marks the voiceprint as the phone owner's.
     */
    suspend fun learn(speaker: Speaker, name: String, isSelf: Boolean): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || !settings.current.rememberVoices) return null
        val now = System.currentTimeMillis()
        val existing = db.voiceprints().byName(trimmed)
        val voice = speaker.embedding?.let { Voices.fromBytes(it) }
        val alreadyLearned = existing != null && speaker.voiceprintId == existing.id && !speaker.autoNamed
        val updated = when {
            existing == null && voice == null -> return null
            existing == null -> Voiceprint(UUID.randomUUID().toString(), trimmed, Voices.toBytes(voice!!), 1, isSelf, now, now)
            voice == null || alreadyLearned -> existing.copy(isSelf = isSelf, updatedAt = now)
            else -> existing.copy(
                embedding = Voices.toBytes(blend(Voices.fromBytes(existing.embedding), existing.sampleCount, voice)),
                sampleCount = existing.sampleCount + 1,
                isSelf = isSelf,
                updatedAt = now,
            )
        }
        if (isSelf) db.voiceprints().clearSelf()
        db.voiceprints().upsert(updated)
        return updated.id
    }

    /** Names [speakers] who have no name yet and whose voice matches a voiceprint, if voices are remembered. */
    suspend fun nameKnownVoices(speakers: List<Speaker>): List<Speaker> {
        if (!settings.current.rememberVoices) return speakers
        return match(speakers, db.voiceprints().all())
    }

    companion object {
        /** Similarity, after removing what all voices share, a speaker needs to a voiceprint to be named after it. */
        const val MATCH = 0.6f
        /** How much closer the best voiceprint must be than the next best, so similar voices aren't confused. */
        const val MARGIN = 0.1f
        /** How much closer the voiceprint must be to this speaker than to anyone else in the meeting. */
        const val LEAD = 0.1f
        /** Voices needed to estimate what all voices share; fewer and nobody is named automatically. */
        const val MIN_VOICES = 6
        /** Later meetings keep moving a voiceprint at least this much, so it follows a voice that changes. */
        private const val MAX_WEIGHT = 20

        fun blend(print: FloatArray, samples: Int, voice: FloatArray): FloatArray {
            val weight = minOf(samples, MAX_WEIGHT).toFloat()
            return normalised(FloatArray(print.size) { print[it] * weight + voice[it] })
        }

        /**
         * One voiceprint per speaker and one speaker per voiceprint, best matches first. Voiceprints already linked
         * to a named speaker in this meeting are not used again.
         *
         * The voice embeddings of different people point in nearly the same direction (their average has length
         * 0.93 on the synthetic meetings), so raw cosine similarity hardly tells them apart. Similarities are taken
         * after subtracting the average of the voiceprints and this meeting's speakers, who are all different people.
         */
        fun match(speakers: List<Speaker>, prints: List<Voiceprint>): List<Speaker> {
            val voices = speakers.associate { it.label to it.embedding?.let { e -> Voices.fromBytes(e) } }
            val printVectors = prints.associate { it.id to Voices.fromBytes(it.embedding) }
            val cohort = printVectors.values + voices.values.filterNotNull()
            if (prints.isEmpty() || cohort.size < MIN_VOICES) return speakers
            val mean = FloatArray(cohort.first().size).also { m -> cohort.forEach { v -> v.forEachIndexed { i, x -> m[i] += x / cohort.size } } }
            fun centred(v: FloatArray) = normalised(FloatArray(v.size) { v[it] - mean[it] })
            val centredSpeakers = voices.mapValues { (_, v) -> v?.let(::centred) }
            val centredPrints = printVectors.mapValues { (_, v) -> centred(v) }

            val taken = speakers.mapNotNull { it.voiceprintId }.toMutableSet()
            val candidates = speakers.filter { it.displayName == null && centredSpeakers[it.label] != null }.mapNotNull { speaker ->
                val voice = centredSpeakers.getValue(speaker.label)!!
                val ranked = prints.filter { it.id !in taken }
                    .map { it to Voices.cosine(voice, centredPrints.getValue(it.id)) }
                    .sortedByDescending { it.second }
                val best = ranked.firstOrNull() ?: return@mapNotNull null
                val runnerUp = ranked.getOrNull(1)?.second ?: -1f
                val others = centredSpeakers.filterKeys { it != speaker.label }.values.filterNotNull()
                    .maxOfOrNull { Voices.cosine(it, centredPrints.getValue(best.first.id)) } ?: -1f
                if (best.second >= MATCH && best.second - runnerUp >= MARGIN && best.second - others >= LEAD) {
                    Triple(speaker, best.first, best.second)
                } else {
                    null
                }
            }.sortedByDescending { it.third }
            val named = mutableMapOf<Int, Voiceprint>()
            for ((speaker, print, _) in candidates) {
                if (print.id in taken || speaker.label in named) continue
                named[speaker.label] = print
                taken += print.id
            }
            return speakers.map { s ->
                named[s.label]?.let { s.copy(displayName = it.name, voiceprintId = it.id, autoNamed = true) } ?: s
            }
        }
    }
}
