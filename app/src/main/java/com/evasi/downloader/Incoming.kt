package com.evasi.downloader

import com.evasi.downloader.media.SocialVideoLinks
import com.evasi.downloader.media.XPost
import java.net.URI
import java.net.URISyntaxException
import java.security.MessageDigest
import java.util.Base64

internal sealed interface Incoming {
    data class Link(val url: String) : Incoming
    data class Files(val uris: List<String>, val mime: String?) : Incoming
    data object Invalid : Incoming
}

internal object SharedLinks {
    private val candidate = Regex("""https?://[^\s<>"'\u200B\u2060]+""", RegexOption.IGNORE_CASE)
    private val status = Regex("""^/(?:web/)?(?:@[^/]+/\d+|users/[^/]+/statuses/\d+|statuses/\d+)(?:/|$)""")

    fun extract(text: CharSequence?) = extractFrom(sequenceOf(text))

    fun extractFrom(fields: Sequence<CharSequence?>): String? {
        var fallback: String? = null
        var seen = 0
        for (text in fields.filterNotNull()) for (match in candidate.findAll(text)) {
            val url = trimPunctuation(match.value)
            val uri = try { URI(url) } catch (_: URISyntaxException) { continue }
            if (uri.host == null || uri.rawUserInfo != null) continue
            if (XPost.statusId(uri) != null || SocialVideoLinks.match(uri) != null || status.containsMatchIn(uri.path.orEmpty())) {
                return url
            }
            if (fallback == null) fallback = url
            if (++seen == 20) return fallback
        }
        return fallback
    }

    private fun trimPunctuation(value: String): String {
        var url = value.trimEnd('.', ',', ';', ':', '!', '?', '…', '»', '”', '’')
        if (url.endsWith(')')) {
            val extra = url.count { it == ')' } - url.count { it == '(' }
            if (extra > 0) {
                val trailing = url.length - url.indexOfLast { it != ')' } - 1
                url = url.dropLast(minOf(extra, trailing))
            }
        }
        return url.trimEnd(']', '}')
    }
}

internal data class ClipboardRecord(val lastFingerprint: String?, val lastTimestamp: Long, val sharedAt: Long) {
    fun isNew(link: String, timestamp: Long): Boolean {
        if (timestamp > 0 && timestamp <= sharedAt) return false
        return (timestamp > 0 && timestamp != lastTimestamp) || fingerprint(link) != lastFingerprint
    }

    companion object {
        fun fingerprint(link: String): String =
            Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(link.toByteArray(Charsets.UTF_8)))
    }
}
