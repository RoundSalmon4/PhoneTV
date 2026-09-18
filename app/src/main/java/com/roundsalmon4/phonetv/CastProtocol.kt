package com.roundsalmon4.phonetv

import kotlinx.serialization.Serializable

@Serializable
data class CastMessage(
    val type: String,
    val url: String? = null,
    val title: String? = null,
    val position: Long? = null,
    val volume: Float? = null,
    val speed: Float? = null,
    val subtitles: List<CastSubtitle>? = null,
    val quality: Int? = null,
    val activeSubtitleIndex: Int? = null,
    val subtitleIndex: Int? = null,
    val message: String? = null
)

@Serializable
data class CastSubtitle(
    val url: String,
    val languageCode: String = "",
    val name: String = "",
    val mimeType: String = "text/vtt"
)

@Serializable
data class CastStatus(
    val type: String = "status",
    val state: String = "idle",
    val position: Long = 0L,
    val duration: Long = 0L,
    val bufferedPosition: Long = 0L,
    val title: String? = null,
    val error: String? = null
)