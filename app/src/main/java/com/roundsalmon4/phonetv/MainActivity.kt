package com.roundsalmon4.phonetv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.compose.ExperimentalMedia3UiComposeApi
import androidx.media3.ui.compose.PlayerSurface
import kotlinx.coroutines.delay
import java.net.Inet4Address
import java.net.NetworkInterface
class MainActivity : ComponentActivity() {
    private lateinit var controller: TvPlayerController
    private lateinit var receiver: CastReceiver

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = TvPlayerController(this)
        receiver = CastReceiver(controller)
        receiver.start()
        enableEdgeToEdge()

        setContent {
            MaterialTheme(colorScheme = remember { darkColorScheme() }) {
                val status by controller.status.collectAsStateWithLifecycle()
                val clients by receiver.connectionCount.collectAsState()

                LaunchedEffect(Unit) {
                    controller.status.collect { receiver.broadcastStatus(it) }
                }
                AnimatedContent(
                    targetState = status.state == "idle",
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "screen"
                ) { isIdle ->
                    if (isIdle) PairingScreen(clients > 0) else PlayerScreen(controller, status)
                }
            }
        }
    }

    override fun onDestroy() {
        receiver.stop()
        controller.release()
        super.onDestroy()
    }
}

@Composable
private fun PairingScreen(connected: Boolean) {
    var ipAddress by remember { mutableStateOf(getLocalIpAddress()) }

    LaunchedEffect(Unit) {
        while (true) {
            ipAddress = getLocalIpAddress()
            delay(5000)
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF0D0D0D)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Cast,
                contentDescription = "Cast",
                tint = Color(0xFFE91E63),
                modifier = Modifier.size(96.dp)
            )
            Text("PhoneTV", color = Color.White, fontSize = 56.sp, fontWeight = FontWeight.Bold)
            Text(
                text = if (connected) "Connected to PhoneTube" else "Waiting for connection...",
                color = if (connected) Color(0xFF4CAF50) else Color(0xFFFFC107),
                fontSize = 28.sp
            )
            Text("Open PhoneTube and cast to this device:", color = Color(0xFFAAAAAA), fontSize = 20.sp)
            Text(
                text = "$ipAddress : 8484",
                color = Color(0xFF80D8FF),
                fontSize = 40.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
@OptIn(ExperimentalMedia3UiComposeApi::class)
private fun PlayerScreen(controller: TvPlayerController, status: CastStatus) {
    val player = remember { controller.getPlayer() }
    var controlsVisible by remember { mutableStateOf(true) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(controlsVisible) {
        if (controlsVisible) { delay(3000); controlsVisible = false }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                var handled = true
                when (event.key) {
                    Key.DpadCenter, Key.Enter ->
                        if (controller.isPlaying) controller.pause() else controller.resume()
                    Key.DpadLeft ->
                        player?.let { controller.seekTo(it.currentPosition - 10_000) }
                    Key.DpadRight ->
                        player?.let { controller.seekTo(it.currentPosition + 10_000) }
                    else -> handled = false
                }
                if (handled) controlsVisible = true
                handled
            }
    ) {
        player?.let { PlayerSurface(player = it, modifier = Modifier.fillMaxSize()) }
        AnimatedVisibility(
            visible = controlsVisible,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xAA000000))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = status.title ?: "Now Playing",
                    color = Color.White,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Text(
                    text = "Connected to PhoneTube  \u2022  ${formatTime(status.position)} / ${formatTime(status.duration)}",
                    color = Color(0x99FFFFFF),
                    fontSize = 18.sp
                )
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) {
        String.format("%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    } else {
        String.format("%d:%02d", s / 60, s % 60)
    }
}

private fun getLocalIpAddress(): String = try {
    val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "0.0.0.0"
    while (interfaces.hasMoreElements()) {
        val networkInterface = interfaces.nextElement()
        if (networkInterface.isLoopback) continue
        val addresses = networkInterface.inetAddresses
        while (addresses.hasMoreElements()) {
            val address = addresses.nextElement()
            if (address is Inet4Address && !address.isLoopbackAddress) {
                return address.hostAddress ?: continue
            }
        }
    }
    "0.0.0.0"
} catch (e: Exception) {
    "0.0.0.0"
}