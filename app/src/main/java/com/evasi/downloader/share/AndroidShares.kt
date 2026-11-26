package com.evasi.downloader.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.evasi.downloader.R
import com.evasi.downloader.auth.XSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** Share published files, keeping the same order and read grants for every receiving app. */
internal suspend fun mediaShareChooser(context: Context, uris: List<String>): Intent =
    prepareChooser(context, uris, null)

internal suspend fun captionedShareChooser(context: Context, uris: List<String>, text: String, account: XSession): Intent {
    require(text.isNotBlank())
    val chooser = prepareChooser(context, uris, text)
    if (!account.authorized()) throw SecurityException("Session expired")
    return chooser
}

private suspend fun prepareChooser(context: Context, values: List<String>, text: String?): Intent = withContext(Dispatchers.IO) {
    val files = values.distinct().map { value ->
        val uri = Uri.parse(value)
        if (uri.scheme != "content" || uri.authority != "media") throw IOException("Invalid gallery item")
        val type = context.contentResolver.getType(uri) ?: throw IOException("Unavailable gallery item")
        if (!type.startsWith("image/") && !type.startsWith("video/")) throw IOException("Unsupported media type")
        val descriptor = context.contentResolver.openAssetFileDescriptor(uri, "r") ?: throw IOException("File missing")
        descriptor.close()
        uri to type
    }
    if (files.isEmpty()) throw IOException("No gallery items")
    val send = mediaSendIntent(files, text)
    Intent.createChooser(send, context.getString(R.string.share_picker_title))
}

internal fun mediaSendIntent(files: List<Pair<Uri, String>>, text: String?): Intent {
    require(files.isNotEmpty())
    val types = files.map { it.second }.distinct()
    val mime = when {
        types.size == 1 -> types.single()
        types.all { it.startsWith("image/") } -> "image/*"
        types.all { it.startsWith("video/") } -> "video/*"
        else -> "*/*"
    }
    val uris = ArrayList(files.map { it.first })
    val clip = ClipData(null, types.toTypedArray(), ClipData.Item(uris.first()))
    for (i in 1 until uris.size) clip.addItem(ClipData.Item(uris[i]))
    return Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
        type = mime
        clipData = clip
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.single())
        else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        text?.let { putExtra(Intent.EXTRA_TEXT, it) }
    }
}
