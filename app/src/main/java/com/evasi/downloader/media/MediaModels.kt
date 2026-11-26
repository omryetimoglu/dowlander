package com.evasi.downloader.media

enum class MediaKind { VIDEO, PHOTO, GIF }
enum class FailureReason { UNSUPPORTED, UNAVAILABLE, NETWORK, STORAGE, INVALID }

class MediaFailure(val reason: FailureReason, cause: Throwable? = null) : Exception(reason.name, cause)

data class Quality(val width: Int, val height: Int)

data class MediaSource(
    val uri: String,
    val mime: String? = null,
    val quality: Quality? = null,
    val name: String? = null,
)

data class MediaItem(val kind: MediaKind, val sources: List<MediaSource>)

data class MediaPost(val items: List<MediaItem>, val title: String = "") {
    // An explicit size must be available for every attachment. Automatic always picks
    // each item's own original, including mixed-size albums and unknown dimensions.
    val qualities: List<Quality> = items.map { item -> item.sources.mapNotNull { it.quality }.toSet() }
        .reduceOrNull { common, sizes -> common intersect sizes }.orEmpty()
        .sortedByDescending { it.width.toLong() * it.height }
}
