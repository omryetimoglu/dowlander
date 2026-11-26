package com.evasi.downloader.media

import java.net.URI

internal class PostRequest(val endpoint: URI, val parse: (String) -> MediaPost) {
    companion object {
        fun from(link: String): PostRequest {
            val uri = runCatching { URI(link) }.getOrNull() ?: throw MediaFailure(FailureReason.INVALID)
            val id = XPost.statusId(uri)
            return if (id != null) PostRequest(XPost.endpoint(id), XPost::parse)
                else PostRequest(Mastodon.endpoint(link), Mastodon::parse)
        }
    }
}
