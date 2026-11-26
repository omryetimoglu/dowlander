package com.evasi.downloader.media

enum class MediaKind { VIDEO, PHOTO, GIF }
enum class FailureReason { UNSUPPORTED, UNAVAILABLE, NETWORK, STORAGE, INVALID, DELETED, PRIVATE, TIMEOUT, NO_SPACE, PERMISSION }

class MediaFailure(val reason: FailureReason, cause: Throwable? = null) : Exception(reason.name, cause)

data class Quality(val width: Int, val height: Int)

data class MediaSource(
    val uri: String,
    val mime: String? = null,
    val quality: Quality? = null,
    val name: String? = null,
    val platformVideo: Boolean = false,
    val referer: String? = null,
    val playlistIndex: Int? = null,
    val platformImageFormat: String? = null,
    val deliveryCdn: MediaCdn? = null,
    val estimatedBytes: Long? = null,
    val nativeStreamCount: Int? = null,
)

data class MediaItem(val kind: MediaKind, val sources: List<MediaSource>)

data class MediaPost(
    val items: List<MediaItem>,
    val title: String = "",
    val description: String? = null,
    val previewUrl: String? = null,
) {
    /** Report an album estimate only when all selected sources have a meaningful estimate. */
    fun estimatedBytes(quality: Quality?): Long? {
        var sum = 0L
        for (item in items) {
            val source = if (quality == null) item.sources.firstOrNull() else item.sources.find { it.quality == quality }
            val bytes = source?.estimatedBytes?.takeIf { it > 0 && it <= Long.MAX_VALUE - sum } ?: return null
            sum += bytes
        }
        return sum.takeIf { it > 0 }
    }

    // An explicit size must be available for every attachment. Automatic always picks
    // each item's own original, including mixed-size albums and unknown dimensions.
    val qualities: List<Quality> = items.map { item -> item.sources.mapNotNull { it.quality }.toSet() }
        .reduceOrNull { common, sizes -> common intersect sizes }.orEmpty()
        .sortedByDescending { it.width.toLong() * it.height }
}
