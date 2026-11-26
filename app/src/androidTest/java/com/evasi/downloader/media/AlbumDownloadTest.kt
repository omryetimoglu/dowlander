package com.evasi.downloader.media

import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.evasi.downloader.SharedLinks
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AlbumDownloadTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun copiedXAlbumPublishesTwoDistinctOriginalPhotos() {
        val link = "https://x.com/example/status/123?s=20"
        val first = Asset("https://pbs.twimg.com/media/Red?format=jpg&name=orig", "image/jpeg", jpeg(Color.RED))
        val second = Asset("https://pbs.twimg.com/media/Blue?format=jpg&name=orig", "image/jpeg", jpeg(Color.BLUE))
        val metadata = """{"id_str":"123","mediaDetails":[
            {"type":"photo","media_url_https":"https://pbs.twimg.com/media/Red.jpg"},
            {"type":"photo","media_url_https":"https://pbs.twimg.com/media/Blue.jpg"}
        ]}"""
        checkAlbum(link, Asset(XPost.endpoint("123").toString(), "application/json", metadata.toByteArray()),
            listOf(first, second))
    }

    @Test fun copiedInstagramCarouselPublishesPhotoVideoPhotoInOrder() {
        val link = "https://www.instagram.com/p/ABC123/?img_index=2"
        val base = "https://scontent.cdninstagram.com"
        val first = Asset("$base/one.jpg", "image/jpeg", jpeg(Color.RED))
        val video = Asset("$base/two.mp4", "video/mp4", instrumentation.context.assets.open("dash/video.mp4").use { it.readBytes() })
        val third = Asset("$base/three.jpg", "image/jpeg", jpeg(Color.BLUE))
        val html = """<script type="application/json" data-sjs>{"data":{"xig_polaris_media":{"if_not_gated_logged_out":{
            "code":"ABC123","media_type":8,"carousel_media_count":3,"carousel_media":[
              {"media_type":1,"image_versions2":{"candidates":[{"url":"${first.url}","width":8,"height":8}]}},
              {"media_type":2,"video_versions":[{"url":"${video.url}","width":128,"height":96}]},
              {"media_type":1,"image_versions2":{"candidates":[{"url":"${third.url}","width":8,"height":8}]}}
            ]}}}}</script>"""
        checkAlbum(link, Asset("https://www.instagram.com/p/ABC123/", "text/html", html.toByteArray()),
            listOf(first, video, third))
    }

    @Test fun copiedTikTokPhotoPostPublishesEverySlide() {
        val link = "https://www.tiktok.com/@example/photo/1234567890123456789"
        val first = Asset("https://p16-sign-va.tiktokcdn.com/slide1.jpeg?sig=a", "image/jpeg", jpeg(Color.RED))
        val second = Asset("https://p16-sign-va.tiktokcdn.com/slide2.jpeg?sig=b", "image/jpeg", jpeg(Color.BLUE))
        val html = """<script type="application/json" id="__UNIVERSAL_DATA_FOR_REHYDRATION__">
            {"__DEFAULT_SCOPE__":{"webapp.video-detail":{"statusCode":0,"itemInfo":{"itemStruct":{
              "id":"1234567890123456789","imagePost":{"images":[
                {"imageURL":{"urlList":["${first.url}"]}},{"imageURL":{"urlList":["${second.url}"]}}
              ]}}}}}}</script>"""
        checkAlbum(link, Asset(link.replace("/photo/", "/video/"), "text/html", html.toByteArray()),
            listOf(first, second))
    }

    @Test fun copiedFacebookPostPublishesEveryFullSizePhoto() {
        val link = "https://www.facebook.com/example/posts/123/"
        val first = Asset("https://scontent.xx.fbcdn.net/first.jpg?sig=a", "image/jpeg", jpeg(Color.BLUE))
        val second = Asset("https://scontent.xx.fbcdn.net/next.jpg?sig=b", "image/jpeg", jpeg(Color.RED))
        val html = """<script type="application/json">{"data":{"node":{"post_id":"123","attachments":[
          {"all_subattachments":{"count":2,"nodes":[
            {"media":{"id":"11","__typename":"Photo","photo_image":{"uri":"${first.url}","width":8,"height":8}}},
            {"media":{"id":"22","__typename":"Photo","photo_image":{"uri":"${second.url}","width":8,"height":8}}}
          ]}}
        ]}}}</script>"""
        checkAlbum(link, Asset(link, "text/html", html.toByteArray()), listOf(first, second))
    }

    @Test fun copiedThreadsPostPublishesBothPhotosFromRequestedThread() {
        val first = Asset("https://scontent.xx.fbcdn.net/red.jpg", "image/jpeg", jpeg(Color.RED))
        val second = Asset("https://scontent.xx.fbcdn.net/blue.jpg", "image/jpeg", jpeg(Color.BLUE))
        val html = """<script type="application/json">{"data":{"thread":{"thread_items":[
          {"post":{"code":"OtherPost","media_type":1,"image_versions2":{"candidates":[{"url":"https://scontent.xx.fbcdn.net/reply.jpg"}]}}},
          {"post":{"code":"ABC123","media_type":8,"carousel_media_count":2,"carousel_media":[
            {"media_type":1,"image_versions2":{"candidates":[{"url":"${first.url}","width":8,"height":8}]}},
            {"media_type":1,"image_versions2":{"candidates":[{"url":"${second.url}","width":8,"height":8}]}}
          ]}}
        ]}}}</script>"""
        checkAlbum("https://threads.net/@example/post/ABC123?xmt=AQ",
            Asset("https://www.threads.com/@example/post/ABC123", "text/html", html.toByteArray()),
            listOf(first, second))
    }

    private data class Asset(val url: String, val mime: String, val bytes: ByteArray) {
        fun connection(uri: URI) = object : HttpURLConnection(uri.toURL()) {
            override fun connect() {}
            override fun disconnect() {}
            override fun usingProxy() = false
            override fun getResponseCode() = HTTP_OK
            override fun getContentType() = mime
            override fun getContentLengthLong() = bytes.size.toLong()
            override fun getInputStream() = bytes.inputStream()
        }
    }

    private fun checkAlbum(link: String, page: Asset, expected: List<Asset>) = runBlocking {
        val assets = (listOf(page) + expected).associateBy { it.url }
        val requests = mutableListOf<String>()
        val repo = MediaRepository(context) { uri ->
            val url = uri.toString()
            requests += url
            assets[url]?.connection(uri) ?: throw IOException("Unexpected album request: $uri")
        }
        val rows = mutableListOf<Uri>()
        try {
            val copied = requireNotNull(SharedLinks.extract("Album: $link"))
            val post = repo.resolve(copied)
            assertEquals(expected.size, post.items.size)
            post.items.forEach { item -> rows += repo.save(item, null) { _, _ -> } }
            assertEquals(listOf(page.url) + expected.map { it.url }, requests)
            assertEquals(expected.size, rows.distinct().size)
            expected.zip(rows).forEach { (asset, row) -> assertPublished(asset, row) }
        } finally {
            rows.forEach { context.contentResolver.delete(it, null, null) }
        }
    }

    private fun assertPublished(asset: Asset, row: Uri) {
        val video = asset.mime.startsWith("video/")
        val collection = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        assertEquals(ContentUris.withAppendedId(collection, ContentUris.parseId(row)), row)
        val resolver = context.contentResolver
        val columns = arrayOf(MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.IS_PENDING)
        requireNotNull(resolver.query(row, columns, null, null, null)).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(asset.mime, cursor.getString(0))
            val folder = if (video) "Movies" else "Pictures"
            assertEquals("$folder/eVasi Video Downloader/", cursor.getString(1))
            assertEquals(0, cursor.getInt(2))
        }
        assertArrayEquals(asset.url, asset.bytes, requireNotNull(resolver.openInputStream(row)).use { it.readBytes() })
    }

    private fun jpeg(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(color)
            ByteArrayOutputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it))
                it.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }
}
