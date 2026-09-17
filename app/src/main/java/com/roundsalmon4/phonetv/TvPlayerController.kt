package com.roundsalmon4.phonetv

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
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
import kotlinx.coroutines.launch
import kotlin.math.abs

@OptIn(UnstableApi::class)
class TvPlayerController(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _status = MutableStateFlow(CastStatus())
    val status: StateFlow<CastStatus> = _status

    private var player: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var ticker: Job? = null
    private var pendingQuality: Int? = null
    private var pendingSubtitle: Int? = null
    // The subtitle list sent with the last play command, used to resolve the
    // selected CC by language/label instead of trusting track ordering.
    private var lastSubtitleList: List<CastSubtitle>? = null

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            emitStatus()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
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
        val p = player ?: return
        val selector = trackSelector ?: return
        val textGroups = p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        if (textGroups.isEmpty()) {
            pendingSubtitle = indexArg
            return
        }
        val group = textGroups.first()
        val builder = selector.buildUponParameters().setRendererDisabled(C.TRACK_TYPE_TEXT, false)
        when {
            indexArg == null -> Unit // auto-select, renderer enabled
            indexArg == -1 -> {
                selector.setParameters(selector.buildUponParameters().setRendererDisabled(C.TRACK_TYPE_TEXT, true))
                return
            }
            group.length > 0 -> {
                // Prefer matching the sender's subtitle by language/label (the
                // manifest track order can differ from the cast list order).
                val target = lastSubtitleList?.getOrNull(indexArg)
                var matchIndex = -1
                if (target != null) {
                    for (i in 0 until group.length) {
                        val format = group.getTrackFormat(i)
                        val langMatch = target.languageCode.isNotBlank() &&
                            target.languageCode.equals(format.language, ignoreCase = true)
                        val labelMatch = target.name.isNotBlank() &&
                            target.name.equals(format.label, ignoreCase = true)
                        if (langMatch || labelMatch) {
                            matchIndex = i
                            break
                        }
                    }
                }
                val idx = if (matchIndex >= 0) matchIndex else indexArg.coerceAtMost(group.length - 1)
                val override = TrackSelectionOverride(group.mediaTrackGroup, listOf(idx))
                selector.setParameters(builder.addOverride(override))
                return
            }
        }
        selector.setParameters(builder)
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