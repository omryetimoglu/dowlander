package com.evasi.downloader.queue

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

internal data class FileMetadata(
    val uri: String, val name: String, val mime: String, val date: Long,
    val size: Long, val width: Int?, val height: Int?,
)

internal class HistoryFiles(context: Context) {
    private val app = context.applicationContext
    private val resolver = app.contentResolver
    private val owner = context.packageName
    private val images = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val videos = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val columns = arrayOf(
        MediaColumns._ID, MediaColumns.DISPLAY_NAME, MediaColumns.MIME_TYPE, MediaColumns.DATE_ADDED,
        MediaColumns.SIZE, MediaColumns.WIDTH, MediaColumns.HEIGHT,
    )

    private fun Cursor.record(uri: Uri, name: String, width: Int?, height: Int?) = FileMetadata(
        uri.toString(), getString(1) ?: name, getString(2) ?: "application/octet-stream",
        if (isNull(3)) System.currentTimeMillis() else getLong(3) * 1000L,
        getLong(4), getInt(5).takeIf { it > 0 } ?: width, getInt(6).takeIf { it > 0 } ?: height,
    )

    fun describe(uri: String, fallbackName: String, fallbackWidth: Int?, fallbackHeight: Int?): FileMetadata {
        val row = Uri.parse(uri)
        val info = try {
            resolver.query(row, columns, null, null, null)?.use {
                if (it.moveToFirst()) it.record(row, fallbackName, fallbackWidth, fallbackHeight) else null
            }
        } catch (_: RuntimeException) { null } ?: FileMetadata(
            uri, fallbackName, "application/octet-stream", System.currentTimeMillis(), 0, fallbackWidth, fallbackHeight,
        )
        if (info.width != null && info.height != null && info.size > 0) return info
        val actualSize = if (info.size > 0) info.size else try {
            resolver.openFileDescriptor(row, "r")?.use { it.statSize.coerceAtLeast(0) } ?: 0
        } catch (_: Exception) { 0L }
        if (info.width != null && info.height != null) return info.copy(size = actualSize)
        val dimensions = try {
            when {
                info.mime.startsWith("image/") -> {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    resolver.openInputStream(row)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                    bounds.outWidth to bounds.outHeight
                }
                info.mime.startsWith("video/") -> {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(app, row)
                        val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                        val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                        w to h
                    } finally { retriever.release() }
                }
                else -> null
            }
        } catch (_: Exception) { null }
        return info.copy(size = actualSize, width = dimensions?.first?.takeIf { it > 0 } ?: info.width,
            height = dimensions?.second?.takeIf { it > 0 } ?: info.height)
    }

    // null means the provider was unavailable. Keep the last known status in that case.
    fun existing(uri: String): Boolean? = try {
        resolver.query(Uri.parse(uri), arrayOf(MediaColumns._ID), null, null, null)?.use { it.moveToFirst() }
    } catch (_: SecurityException) { null } catch (_: IllegalArgumentException) { null } catch (_: android.database.SQLException) { null }

    fun delete(uri: String): Boolean = try {
        val row = Uri.parse(uri)
        row.scheme == "content" && row.authority == MediaStore.AUTHORITY &&
            (resolver.delete(row, null, null) > 0 || existing(uri) == false)
    } catch (_: RuntimeException) { false }

    fun legacy(): List<FileMetadata> {
        val folders = listOf("eVasi Downloader App", "eVasi Dowlander App")
        return listOf(images to "Pictures", videos to "Movies").flatMap { (collection, base) ->
            val ownership = if (Build.VERSION.SDK_INT >= 30) " AND ${MediaColumns.OWNER_PACKAGE_NAME} = ?" else ""
            val args = folders.map { "$base/$it/" } + if (Build.VERSION.SDK_INT >= 30) listOf(owner) else emptyList()
            val selection = "${MediaColumns.IS_PENDING} = 0 AND ${MediaColumns.RELATIVE_PATH} IN (?, ?)$ownership"
            resolver.query(collection, columns, selection, args.toTypedArray(), null)?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val row = ContentUris.withAppendedId(collection, cursor.getLong(0))
                        add(cursor.record(row, "", null, null))
                    }
                }
            } ?: throw IllegalStateException("Gallery unavailable")
        }
    }

    fun changes(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { trySend(Unit) }
        }
        resolver.registerContentObserver(Uri.parse("content://${MediaStore.AUTHORITY}"), true, observer)
        trySend(Unit)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }.conflate()
}
