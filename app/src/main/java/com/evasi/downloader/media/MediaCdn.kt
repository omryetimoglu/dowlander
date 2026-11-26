package com.evasi.downloader.media

import java.net.URI

/** Delivery hosts accepted both when parsing a provider response and when following media redirects. */
enum class MediaCdn(private vararg val domains: String) {
    X_PHOTO("pbs.twimg.com"),
    X_VIDEO("video.twimg.com"),
    META("cdninstagram.com", "fbcdn.net"),
    FACEBOOK("fbcdn.net", "fbsbx.com"),
    TIKTOK("tiktokcdn.com", "tiktokcdn-us.com", "tiktokcdn-eu.com", "tiktokcdn-in.com",
        "tiktokcdn-us-1.com", "tiktokv.com", "tiktok.com", "byteoversea.com", "ibytedtos.com");

    internal fun accepts(uri: URI): Boolean {
        val host = uri.host ?: return false
        if (!uri.scheme.equals("https", true) || uri.rawUserInfo != null || (uri.port != -1 && uri.port != 443)) return false
        return domains.any { domain ->
            when (this) {
                X_PHOTO, X_VIDEO -> host.equals(domain, ignoreCase = true)
                FACEBOOK -> host.endsWith(".$domain", ignoreCase = true)
                META, TIKTOK -> host.equals(domain, ignoreCase = true) || host.endsWith(".$domain", ignoreCase = true)
            }
        }
    }
}
