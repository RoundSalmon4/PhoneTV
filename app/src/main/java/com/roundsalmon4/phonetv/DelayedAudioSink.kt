package com.roundsalmon4.phonetv

import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioSink
import java.nio.ByteBuffer

/**
 * Wraps an [AudioSink] (normally [androidx.media3.exoplayer.audio.DefaultAudioSink])
 * and holds back PCM for a configurable number of milliseconds before handing it
 * to the underlying output, so audio can be shifted later relative to video to
 * fix lip-sync. Only positive (audio-delaying) offsets are supported: shifting
 * audio earlier is not representable on the platform output.
 */
class DelayedAudioSink(
    private val inner: AudioSink
) : AudioSink by inner {

    @Volatile private var delayUs = 0L

    private val lock = Any()
    private val queue = ArrayDeque<HeldBuffer>()
    private var heldBytes = 0L
    private var bytesPerSecond = 0L

    fun setDelayMs(delayMs: Int) {
        val clamped = delayMs.coerceIn(0, 2000)
        synchronized(lock) {
            delayUs = clamped * 1000L
        }
        Log.i(TAG, "DelayedAudioSink delay set to ${clamped}ms")
    }

    override fun configure(
        inputFormat: Format,
        specifiedBufferSize: Int,
        outputChannels: IntArray?
    ) {
        inner.configure(inputFormat, specifiedBufferSize, outputChannels)
        val frameBytes = Util.getPcmFrameSize(inputFormat.pcmEncoding, inputFormat.channelCount)
        bytesPerSecond = inputFormat.sampleRate.toLong() * frameBytes
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int
    ): Boolean {
        val size = buffer.remaining()
        val copy = ByteArray(size)
        buffer.get(copy)
        synchronized(lock) {
            queue.addLast(HeldBuffer(copy, presentationTimeUs, encodedAccessUnitCount))
            heldBytes += size
            drainLocked()
        }
        return true
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val held = synchronized(lock) { delayUs }
        return (inner.getCurrentPositionUs(sourceEnded) - held).coerceAtLeast(0L)
    }

    override fun hasPendingData(): Boolean {
        synchronized(lock) { if (queue.isNotEmpty()) return true }
        return inner.hasPendingData()
    }

    override fun isEnded(): Boolean {
        synchronized(lock) { if (queue.isNotEmpty()) return false }
        return inner.isEnded()
    }

    override fun handleDiscontinuity() {
        synchronized(lock) { clearLocked() }
        inner.handleDiscontinuity()
    }

    override fun flush() {
        synchronized(lock) { clearLocked() }
        inner.flush()
    }

    override fun reset() {
        synchronized(lock) { clearLocked() }
        inner.reset()
    }

    private fun clearLocked() {
        queue.clear()
        heldBytes = 0L
    }

    private fun drainLocked() {
        val keepUs = delayUs
        val keepBytes = if (bytesPerSecond > 0) keepUs * bytesPerSecond / 1_000_000L else 0L
        while (queue.isNotEmpty()) {
            val head = queue.first()
            // Only release a chunk once keeping it would push us below the delay target.
            if (heldBytes - head.bytes.size < keepBytes) break
            val buffer = ByteBuffer.wrap(head.bytes)
            val handled = inner.handleBuffer(buffer, head.presentationTimeUs, head.accessUnitCount)
            if (!handled) break
            queue.removeFirst()
            heldBytes -= head.bytes.size
        }
    }

    private data class HeldBuffer(
        val bytes: ByteArray,
        val presentationTimeUs: Long,
        val accessUnitCount: Int
    )

    private companion object {
        const val TAG = "DelayedAudioSink"
    }
}