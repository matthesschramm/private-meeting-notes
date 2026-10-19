package dev.matthes.privatemeetingnotes.audio

/** Stretches of a recording, [ranges] in samples, played back to back with [gap] samples of silence between them. */
class Spliced(private val source: AudioSource, private val ranges: List<LongRange>, private val gap: Long) : AudioSource {
    private val starts = LongArray(ranges.size)
    override val sampleCount: Long

    init {
        var position = 0L
        for ((i, range) in ranges.withIndex()) {
            starts[i] = position
            position += length(range) + gap
        }
        sampleCount = maxOf(0L, position - gap)
    }

    override fun read(start: Long, end: Long): FloatArray {
        val from = start.coerceIn(0, sampleCount)
        val to = end.coerceIn(from, sampleCount)
        val out = FloatArray((to - from).toInt())
        var i = maxOf(0, index(from))
        while (i < ranges.size && starts[i] < to) {
            val a = maxOf(from, starts[i])
            val b = minOf(to, starts[i] + length(ranges[i]))
            if (b > a) source.read(ranges[i].first + a - starts[i], ranges[i].first + b - starts[i]).copyInto(out, (a - from).toInt())
            i++
        }
        return out
    }

    /** Where [position] here comes from in the recording, or -1 in a gap. */
    fun original(position: Long): Long {
        val i = index(position)
        if (i < 0) return -1
        val offset = position - starts[i]
        return if (offset < length(ranges[i])) ranges[i].first + offset else -1
    }

    /** The last range starting at or before [position]. */
    private fun index(position: Long): Int {
        var lo = 0
        var hi = ranges.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (starts[mid] <= position) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }

    private fun length(range: LongRange) = range.last + 1 - range.first
}
