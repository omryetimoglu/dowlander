package com.evasi.downloader.ui

import com.evasi.downloader.media.MediaKind

enum class Phase { Idle, Preparing, Ready, Queued, Downloading, Paused, Saved, Failed, Cancelled }
enum class Source { None, Manual, Clipboard, Share }
enum class UiError { Network, Unavailable, Unsupported, NoMedia, Read, Write, InvalidLink, Gallery, Unknown,
    Deleted, Private, Timeout, NoSpace, Permission }

data class ScreenState(
    val phase: Phase = Phase.Idle,
    val source: Source = Source.None,
    val link: String = "",
    val title: String = "",
    val qualityOptions: List<String> = emptyList(),
    val qualitySizes: Map<String, Long> = emptyMap(),
    val quality: String? = null,
    val qualitySheet: Boolean = false,
    val controlsGeneration: Long = 0L,
    val resolving: Boolean = false,
    val item: Int = 0,
    val total: Int = 0,
    val progress: Float? = null,
    val savedCount: Int = 0,
    val error: UiError? = null,
    val language: String = "en",
    val autoStart: Boolean = false,
    val downloadedBytes: Long = 0,
    val totalBytes: Long? = null,
    val bytesPerSecond: Long? = null,
    val remainingSeconds: Long? = null,
    val fileName: String = "",
    val mediaKind: MediaKind? = null,
    val previewUri: String? = null,
    val resolution: String? = null,
    val savedUris: List<String> = emptyList(),
    val description: String? = null,
)

sealed interface UiEvent {
    data class EditLink(val text: String) : UiEvent
    object Paste : UiEvent
    object QualityTouched : UiEvent
    object OpenQuality : UiEvent
    object CloseQuality : UiEvent
    object SelectOriginal : UiEvent
    object Start : UiEvent
    object Cancel : UiEvent
    object Retry : UiEvent
    object OpenGallery : UiEvent
    object NotificationSettings : UiEvent
    data class SelectQuality(val value: String) : UiEvent
    data class SetLanguage(val tag: String) : UiEvent
    data class SetAutoStart(val enabled: Boolean) : UiEvent
}
