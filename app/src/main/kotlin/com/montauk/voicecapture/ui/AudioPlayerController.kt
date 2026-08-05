package com.montauk.voicecapture.ui

import android.media.MediaPlayer
import android.util.Log
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Thin [MediaPlayer] wrapper for the session-detail audio-playback row.
 * "MediaPlayer is fine" per the wave-3 brief -- this isn't the app's core
 * recording path (that's [com.montauk.voicecapture.audio.AudioEngine]), just
 * local-file playback of an already-finalized `audio.ogg`.
 */
class AudioPlayerController(private val filePath: String) {
    private var mediaPlayer: MediaPlayer? = null
    private var pollJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    var isPlaying by mutableStateOf(false)
        private set
    var positionMs by mutableIntStateOf(0)
        private set
    var durationMs by mutableIntStateOf(0)
        private set
    var isAvailable by mutableStateOf(true)
        private set

    fun togglePlayPause() {
        val mp = mediaPlayer ?: prepare() ?: run { isAvailable = false; return }
        if (mp.isPlaying) {
            mp.pause()
            isPlaying = false
            pollJob?.cancel()
        } else {
            mp.start()
            isPlaying = true
            startPolling(mp)
        }
    }

    private fun prepare(): MediaPlayer? {
        // Not built via MediaPlayer().apply { ... } deliberately -- MediaPlayer
        // has its own read-only `isPlaying`, which would shadow this class's
        // `isPlaying` var if `this` inside the block were the MediaPlayer.
        return runCatching {
            val mp = MediaPlayer()
            mp.setDataSource(filePath)
            mp.setOnCompletionListener {
                isPlaying = false
                positionMs = 0
                pollJob?.cancel()
                runCatching { mp.seekTo(0) }
            }
            mp.prepare()
            durationMs = mp.duration
            mp
        }.onFailure { e -> Log.w(TAG, "failed to prepare MediaPlayer for $filePath", e) }
            .onSuccess { mediaPlayer = it }
            .getOrNull()
    }

    private fun startPolling(mp: MediaPlayer) {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                positionMs = runCatching { mp.currentPosition }.getOrDefault(positionMs)
                if (!mp.isPlaying) break
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun release() {
        pollJob?.cancel()
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
    }

    companion object {
        private const val TAG = "AudioPlayerController"
        private const val POLL_INTERVAL_MS = 200L
    }
}

@Composable
fun rememberAudioPlayer(filePath: String): AudioPlayerController {
    val controller = remember(filePath) { AudioPlayerController(filePath) }
    DisposableEffect(controller) {
        onDispose { controller.release() }
    }
    return controller
}

fun formatMillis(ms: Int): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%d:%02d", minutes, seconds)
}
