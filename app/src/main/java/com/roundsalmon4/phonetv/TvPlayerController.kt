package com.roundsalmon4.phonetv

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class TvPlayerController(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _status = MutableStateFlow(CastStatus())
    val status: StateFlow<CastStatus> = _status

    private var player: ExoPlayer? = null
    private var ticker: Job? = null

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            emitStatus()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            emitStatus()
        }

        override fun onPlayerError(error: PlaybackException) {
            _status.value = _status.value.copy(state = "error", error = error.errorCodeName)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            emitStatus()
        }
    }

    val isPlaying: Boolean
        get() = player?.isPlaying == true

    fun getPlayer(): ExoPlayer? = player

    init {
        createPlayer()
        startTicker()
    }

    private fun createPlayer() {
        val renderersFactory = DefaultRenderersFactory(appContext)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        val trackSelector = DefaultTrackSelector(appContext)
        val loadControl = DefaultLoadControl()
        player = ExoPlayer.Builder(appContext)
            .setRenderersFactory(renderersFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .build()
            .also { it.addListener(playerListener) }
    }

    private fun startTicker() {
        ticker = scope.launch {
            while (true) {
                emitStatus()
                delay(500)
            }
        }
    }

    private fun emitStatus() {
        val p = player ?: return
        val state = when {
            _status.value.state == "error" -> _status.value.state
            p.isPlaying -> "playing"
            p.playbackState == Player.STATE_READY -> "paused"
            p.playbackState == Player.STATE_BUFFERING -> "buffering"
            p.playbackState == Player.STATE_ENDED -> "ended"
            else -> "idle"
        }
        _status.value = CastStatus(
            state = state,
            position = p.currentPosition.coerceAtLeast(0L),
            duration = p.duration.takeIf { it > 0L } ?: 0L,
            bufferedPosition = p.bufferedPosition.coerceAtLeast(0L),
            title = _status.value.title
        )
    }

    fun play(url: String, title: String?, positionMs: Long? = null) {
        val p = player ?: return
        val lower = url.lowercase()
        val mediaItem: MediaItem = when {
            lower.contains(".mpd") || lower.contains("dash") ->
                MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_MPD).build()
            lower.contains(".m3u8") ->
                MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8).build()
            else ->
                MediaItem.fromUri(url)
        }
        _status.value = CastStatus(state = "buffering", title = title)
        p.setMediaItem(mediaItem)
        p.prepare()
        positionMs?.takeIf { it > 0L }?.let { p.seekTo(it) }
        p.play()
    }

    fun pause() {
        player?.pause()
    }

    fun resume() {
        player?.play()
    }

    fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs.coerceAtLeast(0L))
    }

    fun setVolume(volume: Float) {
        player?.volume = volume.coerceIn(0f, 1f)
    }

    fun setSpeed(speed: Float) {
        player?.setPlaybackSpeed(speed.coerceIn(0.25f, 3f))
    }

    fun stop() {
        val p = player ?: return
        p.stop()
        p.clearMediaItems()
        _status.value = CastStatus()
    }

    fun release() {
        ticker?.cancel()
        player?.let {
            it.removeListener(playerListener)
            it.release()
        }
        player = null
    }
}