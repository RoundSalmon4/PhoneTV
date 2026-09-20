package com.roundsalmon4.phonetv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class CreditEntry(
    val name: String,
    val description: String,
    val url: String,
    val license: String
)

private val credits = listOf(
    CreditEntry(
        name = "Media3 ExoPlayer",
        description = "Video playback engine",
        url = "https://developer.android.com/media/media3",
        license = "Apache 2.0"
    ),
    CreditEntry(
        name = "OkHttp",
        description = "HTTP client for stream fetching",
        url = "https://github.com/square/okhttp",
        license = "Apache 2.0"
    ),
    CreditEntry(
        name = "Java-WebSocket",
        description = "WebSocket server for the cast connection",
        url = "https://github.com/TooTallNate/Java-WebSocket",
        license = "Apache 2.0"
    ),
    CreditEntry(
        name = "Jetpack Compose",
        description = "UI toolkit for the interface",
        url = "https://developer.android.com/jetpack/compose",
        license = "Apache 2.0"
    ),
    CreditEntry(
        name = "Kotlin Coroutines",
        description = "Async runtime",
        url = "https://github.com/Kotlin/kotlinx.coroutines",
        license = "Apache 2.0"
    ),
    CreditEntry(
        name = "kotlinx.serialization",
        description = "JSON message protocol between the two apps",
        url = "https://github.com/Kotlin/kotlinx.serialization",
        license = "Apache 2.0"
    )
)

@Composable
fun CreditsScreen(onBackClick: () -> Unit) {
    BackHandler(onBack = onBackClick)
    val scrollState = rememberScrollState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0D0D))
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionUp -> {
                        scrollState.scrollBy(-120f); true
                    }
                    Key.DirectionDown -> {
                        scrollState.scrollBy(120f); true
                    }
                    else -> false
                }
            }
            .verticalScroll(scrollState)
            .padding(horizontal = 48.dp, vertical = 32.dp)
    ) {
        Text("Credits", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
        Text(
            "Open source libraries used by PhoneTV",
            color = Color(0xFFAAAAAA),
            fontSize = 20.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 20.dp)
        )

        credits.forEachIndexed { index, credit ->
            Column(modifier = Modifier.padding(vertical = 12.dp)) {
                Text(
                    text = "${credit.name}  •  ${credit.license}",
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = credit.description,
                    color = Color(0xFFCCCCCC),
                    fontSize = 18.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Text(
                    text = credit.url,
                    color = Color(0xFF888888),
                    fontSize = 16.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            if (index < credits.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier.fillMaxWidth(),
                    thickness = 1.dp
                )
            }
        }

        Text(
            text = "PhoneTV itself is MIT licensed. See the project repo for details.",
            color = Color(0xFF666666),
            fontSize = 16.sp,
            modifier = Modifier.padding(top = 24.dp)
        )
    }
}