package com.evasi.downloader.media

import java.net.URI
import org.json.JSONObject

internal fun JSONObject?.caption(key: String): String? {
    if (this == null || isNull(key)) return null
    return (opt(key) as? String)?.trim()?.takeIf { it.isNotEmpty() }?.take(10_000)
}

internal fun JSONObject?.bytes(key: String): Long? = this?.optLong(key)?.takeIf { it > 0 }

internal fun preview(value: String?, cdn: MediaCdn? = null): String? {
    val uri = value?.let { runCatching { URI(it) }.getOrNull() } ?: return null
    return value.takeIf { uri.scheme.equals("https", true) && uri.host != null && uri.rawUserInfo == null &&
        (cdn == null || cdn.accepts(uri)) }
}
