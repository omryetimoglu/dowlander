package com.evasi.downloader.ui

enum class Phase { Idle, Preparing, Ready, Downloading, Saved, Failed, Cancelled }
enum class Source { None, Clipboard, Share }
enum class UiError { Network, Unavailable, Unsupported, NoMedia, Read, Write, InvalidLink, Gallery, Unknown }

data class ScreenState(
    val phase: Phase = Phase.Idle,
    val source: Source = Source.None,
    val link: String = "",
    val title: String = "",
    val qualityOptions: List<String> = emptyList(),
    val quality: String? = null,
    val qualitySheet: Boolean = false,
    val controlsGeneration: Long = 0L,
    val countdown: Float? = null,
    val resolving: Boolean = false,
    val item: Int = 0,
    val total: Int = 0,
    val progress: Float? = null,
    val savedCount: Int = 0,
    val error: UiError? = null,
    val language: String = "en",
    val autoStart: Boolean = true,
)

internal const val DEFAULT_AUTO_START_MS = 1500L

sealed interface UiEvent {
    object Paste : UiEvent
    object QualityTouched : UiEvent
    object OpenQuality : UiEvent
    object CloseQuality : UiEvent
    object Start : UiEvent
    object Cancel : UiEvent
    object Retry : UiEvent
    object OpenGallery : UiEvent
    object NotificationSettings : UiEvent
    data class ControlsReady(val generation: Long, val timeoutMillis: Long = DEFAULT_AUTO_START_MS) : UiEvent
    data class ControlsHidden(val generation: Long) : UiEvent
    data class SelectQuality(val value: String) : UiEvent
    data class SetLanguage(val tag: String) : UiEvent
    data class SetAutoStart(val enabled: Boolean) : UiEvent
}
