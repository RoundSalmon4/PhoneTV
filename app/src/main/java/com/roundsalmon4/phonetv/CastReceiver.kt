package com.roundsalmon4.phonetv

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress

class CastReceiver(
    private val controller: TvPlayerController,
    port: Int = 8484
) : WebSocketServer(InetSocketAddress(port)) {

    private val json = Json { ignoreUnknownKeys = true }
    private val clients = mutableSetOf<WebSocket>()

    private val _connectionCount = MutableStateFlow(0)
    val connectionCount: StateFlow<Int> = _connectionCount.asStateFlow()

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        synchronized(clients) {
            clients += conn
            _connectionCount.value = clients.size
        }
        Log.i(TAG, "Client connected: ${conn.remoteSocketAddress}")
    }

    override fun onMessage(conn: WebSocket, message: String) {
        dispatch(message)
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        synchronized(clients) {
            clients -= conn
            _connectionCount.value = clients.size
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
        when (message.type) {
            "play" -> message.url?.let { controller.play(it, message.title, message.position) }
            "pause" -> controller.pause()
            "resume" -> controller.resume()
            "seek" -> message.position?.let { controller.seekTo(it) }
            "stop" -> controller.stop()
            "set_volume" -> message.volume?.let { controller.setVolume(it) }
            "set_speed" -> message.speed?.let { controller.setSpeed(it) }
            else -> Log.w(TAG, "Unknown message type: ${message.type}")
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