package com.roundsalmon4.phonetv

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

@OptIn(UnstableApi::class)
class TvPlayerController(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _status = MutableStateFlow(CastStatus())
    val status: StateFlow<CastStatus> = _status

    // Currently visible caption cues, exposed so the UI can render an overlay
    // (ExoPlayer decodes captions but does not draw them itself).
    private val _currentCues = MutableStateFlow<List<Cue>>(emptyList())
    val currentCues: StateFlow<List<Cue>> = _currentCues.asStateFlow()

    private var player: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var ticker: Job? = null
    private var pendingQuality: Int? = null
    private var pendingSubtitle: Int? = null
    // Desired playback speed, reapplied whenever the player becomes ready so
    // the prepared/adaptive state can't reset it.
    private var desiredSpeed: Float? = null
    // The subtitle list sent with the last play command, used to map the
    // sender's CC selection to a language.
    private var lastSubtitleList: List<CastSubtitle>? = null
    // One-shot recovery: a fatal decoder/OS error restarts the current media
    // once so a transient Fire TV video-codec crash doesn't freeze the cast.
    private var recoveredFromError = false

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            emitStatus()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                desiredSpeed?.let { speed -> player?.setPlaybackSpeed(speed) }
            }
            emitStatus()
        }

        override fun onTracksChanged(tracks: Tracks) {
            pendingQuality?.let { applyQuality(it) }
            pendingSubtitle?.let { index ->
                pendingSubtitle = null
                setSubtitle(index)
            }
            emitStatus()
        }

        override fun onPlayerError(error: PlaybackException) {
            val p = player
            if (p != null && !recoveredFromError) {
                recoveredFromError = true
                val pos = p.currentPosition.coerceAtLeast(0L)
                val item = p.currentMediaItem
                if (item != null) {
                    Log.i(TAG, "onPlayerError: recovering once (${error.errorCodeName}) at $pos")
                    p.setMediaItem(item)
                    p.prepare()
                    p.seekTo(pos)
                    p.play()
                    return
                }
            }
            _status.value = _status.value.copy(state = "error", error = error.errorCodeName)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            emitStatus()
        }

        override fun onCues(cues: CueGroup) {
            _currentCues.value = cues.cues
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
        val selector = DefaultTrackSelector(appContext)
        trackSelector = selector
        val loadControl = DefaultLoadControl()
        player = ExoPlayer.Builder(appContext)
            .setRenderersFactory(renderersFactory)
            .setTrackSelector(selector)
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

    fun play(
        url: String,
        title: String?,
        positionMs: Long? = null,
        subtitles: List<CastSubtitle>? = null,
        quality: Int? = null,
        speed: Float? = null,
        activeSubtitleIndex: Int? = null
    ) {
        val p = player ?: return
        val lower = url.lowercase()
        val builder = MediaItem.Builder().setUri(url)
        when {
            lower.contains(".mpd") || lower.contains("dash") -> builder.setMimeType(MimeTypes.APPLICATION_MPD)
            lower.contains(".m3u8") -> builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        }
        if (!subtitles.isNullOrEmpty()) {
            val configurations = subtitles.map { subtitle ->
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitle.url))
                    .setMimeType(subtitle.mimeType.ifBlank { MimeTypes.TEXT_VTT })
                    .setLanguage(subtitle.languageCode.ifBlank { null })
                    .setLabel(subtitle.name.ifBlank { subtitle.languageCode })
                    .build()
            }
            builder.setSubtitleConfigurations(configurations)
        }
pendingQuality = quality?.takeIf { it > 0 }
        pendingSubtitle = activeSubtitleIndex
        lastSubtitleList = subtitles
        desiredSpeed = speed?.takeIf { it > 0f }
        recoveredFromError = false
        Log.i(TAG, "play: url=$url speed=$speed subtitle=$activeSubtitleIndex subs=${subtitles?.size}")
        _status.value = CastStatus(state = "buffering", title = title)
        p.setMediaItem(builder.build())
        p.prepare()
        positionMs?.takeIf { it > 0L }?.let { p.seekTo(it) }
        speed?.takeIf { it in 0.25f..3f }?.let { p.setPlaybackSpeed(it) }
        p.play()
    }

    /** Applies a requested video height live (from the sender's quality picker). */
    fun setQuality(height: Int) {
        applyQuality(height)
    }

    /**
     * Mirrors the sender's subtitle selection. Index is into the subtitle list
     * sent with the play command: -1 disables subtitles, null lets ExoPlayer
     * auto-select, and any other index overrides the text track.
     */
    fun setSubtitle(indexArg: Int?) {
        val selector = trackSelector ?: return
        val builder = selector.buildUponParameters().setRendererDisabled(C.TRACK_TYPE_TEXT, false)
        when {
            indexArg == null -> {
                // Auto: enable the renderer and let ExoPlayer pick by language.
                selector.setParameters(builder)
                Log.i(TAG, "setSubtitle: auto")
            }
            indexArg == -1 -> {
                // Disabling the text renderer stops cue delivery, but ExoPlayer
                // may not emit an empty cue list, so clear any visible caption.
                _currentCues.value = emptyList()
                selector.setParameters(selector.buildUponParameters().setRendererDisabled(C.TRACK_TYPE_TEXT, true))
                Log.i(TAG, "setSubtitle: off")
            }
            else -> {
                // Enable captions in the sender's chosen language; ExoPlayer's
                // track selector picks the matching track automatically.
                val lang = lastSubtitleList?.getOrNull(indexArg)?.languageCode
                if (lang.isNullOrBlank()) {
                    selector.setParameters(builder)
                    Log.i(TAG, "setSubtitle: index=$indexArg no language (auto)")
                } else {
                    selector.setParameters(builder.setPreferredTextLanguage(lang))
                    Log.i(TAG, "setSubtitle: index=$indexArg lang=$lang")
                }
            }
        }
    }

    /**
     * Applies a requested video height (from the sender's Default Quality)
     * once video tracks are available, matching the closest rendition.
     */
    private fun applyQuality(targetHeight: Int) {
        val p = player ?: return
        val selector = trackSelector ?: return
        val videoGroups = p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
        if (videoGroups.isEmpty()) return

        var bestGroup: Tracks.Group? = null
        var bestIndex = -1
        var bestDiff = Int.MAX_VALUE
        for (group in videoGroups) {
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                if (format.height <= 0) continue
                val diff = abs(format.height - targetHeight)
                if (diff < bestDiff) {
                    bestDiff = diff
                    bestGroup = group
                    bestIndex = i
                }
            }
        }
        val group = bestGroup ?: return
        pendingQuality = null
        val override = TrackSelectionOverride(group.mediaTrackGroup, listOf(bestIndex))
        selector.setParameters(
            selector.buildUponParameters().addOverride(override)
        )
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
        _currentCues.value = emptyList()
    }

    fun release() {
        ticker?.cancel()
        player?.let {
            it.removeListener(playerListener)
            it.release()
        }
        player = null
    }

    private companion object {
        const val TAG = "TvPlayer"
    }
}