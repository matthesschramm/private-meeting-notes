package dev.matthes.privatemeetingnotes.playback

import android.media.AudioAttributes
import android.media.MediaPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/** Plays a meeting's recording from any point, for tap-to-play in the transcript. */
class AudioPlayer(file: File, private val scope: CoroutineScope) {
    private val player = MediaPlayer().apply {
        setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        setDataSource(file.path)
        prepare()
        setOnCompletionListener { playingState.value = false }
    }
    private val positionState = MutableStateFlow(0L)
    private val playingState = MutableStateFlow(false)
    private var ticker: Job? = null

    val position: StateFlow<Long> = positionState.asStateFlow()
    val playing: StateFlow<Boolean> = playingState.asStateFlow()
    val durationMs: Long get() = player.duration.toLong()

    fun playFrom(ms: Long) {
        player.seekTo(ms.coerceAtLeast(0), MediaPlayer.SEEK_CLOSEST)
        positionState.value = ms
        play()
    }

    fun toggle() = if (player.isPlaying) pause() else play()

    fun skip(deltaMs: Long) {
        val target = (player.currentPosition + deltaMs).coerceIn(0, player.duration.toLong())
        player.seekTo(target, MediaPlayer.SEEK_CLOSEST)
        positionState.value = target
    }

    private fun play() {
        player.start()
        playingState.value = true
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive && player.isPlaying) {
                positionState.value = player.currentPosition.toLong()
                delay(100)
            }
            playingState.value = player.isPlaying
        }
    }

    fun pause() {
        if (player.isPlaying) player.pause()
        playingState.value = false
    }

    fun release() {
        ticker?.cancel()
        player.release()
    }
}
