package com.evasi.downloader

import com.evasi.downloader.media.SocialVideoLinks
import com.evasi.downloader.media.XPost
import java.net.URI

internal sealed interface Incoming {
    data class Link(val url: String) : Incoming
    data class Files(val uris: List<String>, val mime: String?) : Incoming
    data object Invalid : Incoming
}

internal object SharedLinks {
    private val candidate = Regex("""https?://[^\s<>"'\u200B\u2060]+""", RegexOption.IGNORE_CASE)
    private val status = Regex("""^/(?:web/)?(?:@[^/]+/\d+|users/[^/]+/statuses/\d+|statuses/\d+)(?:/|$)""")

    fun extract(text: CharSequence?): String? {
        if (text == null) return null
        val urls = candidate.findAll(text).mapNotNull {
            var url = it.value.trimEnd('.', ',', ';', ':', '!', '?', '…', '»', '”', '’')
            while (url.endsWith(')') && url.count { ch -> ch == ')' } > url.count { ch -> ch == '(' }) {
                url = url.dropLast(1)
            }
            url = url.trimEnd(']', '}')
            runCatching { URI(url) }.getOrNull()?.takeIf { uri -> uri.host != null && uri.rawUserInfo == null }
        }.take(20).toList()
        val post = urls.firstOrNull { uri ->
            XPost.statusId(uri) != null || SocialVideoLinks.match(uri) != null || status.containsMatchIn(uri.path.orEmpty())
        }
        return (post ?: urls.firstOrNull())?.toString()
    }
}

internal data class ClipboardRecord(val lastLink: String, val lastTimestamp: Long, val sharedAt: Long) {
    fun isNew(link: String, timestamp: Long): Boolean {
        if (timestamp > 0 && timestamp <= sharedAt) return false
        return link != lastLink || (timestamp > 0 && timestamp != lastTimestamp)
    }
}
