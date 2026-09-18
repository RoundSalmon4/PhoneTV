package com.roundsalmon4.phonetv

import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.java_websocket.WebSocket
import org.java_websocket.drafts.Draft
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.handshake.HandshakeImpl1Server
import org.java_websocket.handshake.ServerHandshakeBuilder
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress

/**
 * Forces the Connection response header to 'Upgrade'. Java-WebSocket echoes
 * the client's Connection value (e.g. 'keep-alive, Upgrade') into its 101
 * response, which strict clients (OkHttp 3.x, .NET) reject and drop the
 * socket over. Intercepting put() keeps the handshake compliant regardless
 * of what the draft writes.
 */
private class FixedConnectionHandshake : HandshakeImpl1Server() {
    override fun put(name: String, value: String) {
        super.put(name, if (name.equals("Connection", ignoreCase = true)) "Upgrade" else value)
    }
}

class CastReceiver(
    private val controller: TvPlayerController,
    port: Int = 8484
) : WebSocketServer(InetSocketAddress(port), 2) {

    init {
        // Drop dead sender connections quickly: if the phone app exits without
        // a clean close (or its network path flaps), don't keep the TV thinking
        // it is still casting for the library's default 60s.
        connectionLostTimeout = 10
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val clients = mutableSetOf<WebSocket>()

    // ExoPlayer is created on the main thread and rejects calls from any
    // other thread. WebSocket frames arrive on Java-WebSocket's worker
    // threads, so every command must hop back to the main looper.
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _connectionCount = MutableStateFlow(0)
    val connectionCount: StateFlow<Int> = _connectionCount.asStateFlow()

    override fun onWebsocketHandshakeReceivedAsServer(
        conn: WebSocket,
        draft: Draft,
        request: ClientHandshake
    ): ServerHandshakeBuilder {
        return FixedConnectionHandshake()
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        synchronized(clients) {
            clients += conn
            _connectionCount.value = clients.size
        }
        Log.i(TAG, "Client connected: ${conn.remoteSocketAddress}")
    }

    override fun onMessage(conn: WebSocket, message: String) {
        Log.d(TAG, "onMessage: $message")
        dispatch(message)
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        synchronized(clients) {
            clients -= conn
            _connectionCount.value = clients.size
            // If no sender is attached anymore (PhoneTube closed or was
            // killed), stop playback so the TV doesn't keep playing forever.
            if (clients.isEmpty()) {
                mainHandler.post { controller.stop() }
            }
        }
        Log.i(TAG, "Client disconnected: ${conn.remoteSocketAddress} ($code $reason)")
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        Log.e(TAG, "WebSocket error", ex)
    }

    override fun onStart() {
        Log.i(TAG, "CastReceiver started on port $port")
    }

    private fun dispatch(raw: String) {
        val message = try {
            json.decodeFromString<CastMessage>(raw)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse cast message: $raw", e)
            return
        }
        mainHandler.post {
            when (message.type) {
                "play" -> message.url?.let {
                    controller.play(
                        url = it,
                        title = message.title,
                        positionMs = message.position,
                        subtitles = message.subtitles,
                        quality = message.quality,
                        speed = message.speed,
                        activeSubtitleIndex = message.activeSubtitleIndex
                    )
                }
                "pause" -> controller.pause()
                "resume" -> controller.resume()
                "seek" -> message.position?.let { controller.seekTo(it) }
                "stop" -> controller.stop()
                "set_volume" -> message.volume?.let { controller.setVolume(it) }
                "set_speed" -> message.speed?.let { controller.setSpeed(it) }
                "set_quality" -> message.quality?.let { controller.setQuality(it) }
                "set_subtitle" -> controller.setSubtitle(message.subtitleIndex)
                else -> Log.w(TAG, "Unknown message type: ${message.type}")
            }
        }
    }

    fun broadcastStatus(status: CastStatus) {
        val payload = json.encodeToString(status)
        synchronized(clients) {
            clients.toList().forEach { client ->
                if (client.isOpen) {
                    client.send(payload)
                }
            }
        }
    }

    /**
     * Tells the sender (PhoneTube) that the user ended the cast from the TV's
     * remote, so it can end its session and resume local playback.
     */
    fun notifyStopped() {
        val payload = "{\"type\":\"stopped\"}"
        Log.i(TAG, "notifyStopped: informing senders playback ended on the TV")
        synchronized(clients) {
            clients.toList().forEach { client ->
                if (client.isOpen) {
                    client.send(payload)
                }
            }
        }
    }

    override fun start() {
        super.start()
        Log.i(TAG, "CastReceiver starting on port $port")
    }

    override fun stop() {
        try {
            stop(1000, "Server shutting down")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping CastReceiver", e)
        }
        synchronized(clients) {
            clients.clear()
            _connectionCount.value = 0
        }
    }

    companion object {
        private const val TAG = "CastReceiver"
    }
}