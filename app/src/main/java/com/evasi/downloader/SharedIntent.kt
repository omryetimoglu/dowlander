package com.evasi.downloader

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build

@Suppress("DEPRECATION")
internal fun sharedInput(intent: Intent): Incoming {
    val multiple = intent.action == Intent.ACTION_SEND_MULTIPLE
    if (!multiple && intent.action != Intent.ACTION_SEND) return Incoming.Invalid
    val mime = intent.type?.let(Intent::normalizeMimeType) ?: return Incoming.Invalid
    if (mime.startsWith("text/")) return if (multiple) Incoming.Invalid else sharedLink(intent)
    val media = mime.startsWith("image/") || mime.startsWith("video/") || mime == "*/*" || mime == "application/octet-stream"
    if (!media) return Incoming.Invalid
    val streams = if (multiple) {
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
    } else {
        val stream = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        listOfNotNull(stream)
    }
    if (streams.any { it.scheme != "content" || it.authority.isNullOrBlank() }) return Incoming.Invalid
    val clipItems = intent.clipData.items()
    val uris = linkedSetOf<String>()
    for (uri in streams.asSequence() + sequenceOf(intent.data).filterNotNull() + clipItems.mapNotNull { it.uri }) {
        if (uri.scheme == "content") {
            if (uri.authority.isNullOrBlank()) return Incoming.Invalid
            uris += uri.toString()
        } else if (uri.scheme != "http" && uri.scheme != "https") {
            return Incoming.Invalid
        }
    }
    if (uris.isNotEmpty()) return Incoming.Files(if (multiple) uris.toList() else listOf(uris.first()), mime)
    return if (multiple) Incoming.Invalid else sharedLink(intent)
}

private fun sharedLink(intent: Intent): Incoming {
    // A Sharesheet preview can place a content:// thumbnail alongside the shared text.
    fun Uri?.webAddress(): String? = this?.takeIf {
        it.scheme.equals("http", ignoreCase = true) || it.scheme.equals("https", ignoreCase = true)
    }?.toString()
    val texts = sequenceOf(intent.getCharSequenceExtra(Intent.EXTRA_TEXT), intent.data.webAddress()) +
        intent.clipData.items().flatMap { sequenceOf(it.text, it.uri.webAddress()) } + sequenceOf(intent.getStringExtra(Intent.EXTRA_SUBJECT))
    val link = SharedLinks.extractFrom(texts)
    return link?.let(Incoming::Link) ?: Incoming.Invalid
}

internal fun ClipData?.items(): Sequence<ClipData.Item> =
    if (this == null) emptySequence() else (0 until itemCount).asSequence().map { getItemAt(it) }
