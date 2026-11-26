package com.evasi.downloader.media

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import org.junit.Assert.*
import org.junit.Test

class QualityAndFormatTest {
    @Test fun albumOnlyOffersSizesAvailableToEveryItem() {
        val original = Quality(1920, 1080)
        val portrait = Quality(1080, 1920)
        val preview = Quality(320, 180)
        fun item(vararg sizes: Quality) = MediaItem(MediaKind.PHOTO,
            sizes.map { MediaSource("https://media.example/${it.width}.jpg", quality = it) })
        assertEquals(listOf(original, preview), MediaPost(listOf(item(original, preview))).qualities)
        assertEquals(listOf(preview), MediaPost(listOf(item(original, preview), item(portrait, preview))).qualities)
        assertTrue(MediaPost(listOf(item(original), item(portrait))).qualities.isEmpty())
    }

    @Test fun sniffPreservesSourceBytesAndUsesActualContainer() {
        val fixtures = listOf(
            "GIF89a" + "0".repeat(64) to "image/gif",
            "\u0089PNG\r\n\u001a\n" + "0".repeat(64) to "image/png",
            "\u00ff\u00d8\u00ff\u00e0" + "0".repeat(64) to "image/jpeg",
            "RIFF0000WEBPVP8X" + "0".repeat(64) to "image/webp",
            "\u0000\u0000\u0000\u0018ftypisom" + "0".repeat(64) to "video/mp4",
            "\u0000\u0000\u0000\u0018ftypavif" + "0".repeat(64) to "image/avif",
        )
        for ((header, expected) in fixtures) {
            val bytes = header.toByteArray(StandardCharsets.ISO_8859_1)
            val input = ByteArrayInputStream(bytes).buffered()
            assertEquals(expected, MediaFormat.inspect(input, "application/octet-stream").mime)
            assertArrayEquals(bytes, input.readBytes())
        }
    }

    @Test fun transportStreamsRequireRepeatedPacketHeadersAndPreserveBytes() {
        for ((stride, offset) in listOf(188 to 0, 192 to 4, 204 to 0)) {
            val bytes = ByteArray(3 * stride)
            repeat(3) { packet ->
                bytes[packet * stride + offset] = 0x47
                bytes[packet * stride + offset + 3] = 0x10
            }
            val stream = bytes.inputStream().buffered()
            assertEquals("video/mp2t", MediaFormat.inspect(stream, "video/mp2t").mime)
            assertArrayEquals(bytes, stream.readBytes())
            bytes[stride + offset] = 0
            assertThrows(MediaFailure::class.java) { MediaFormat.inspect(bytes.inputStream().buffered(), "video/mp2t") }
        }
        assertThrows(MediaFailure::class.java) {
            MediaFormat.inspect("Gateway error".byteInputStream().buffered(), "video/mp2t")
        }
    }

    @Test fun aWronglyLabeledErrorPageOrEmptyStreamIsRejected() {
        for (body in listOf("", "<html><body>Sign in</body></html>")) {
            val ex = assertThrows(MediaFailure::class.java) {
                MediaFormat.inspect(body.byteInputStream().buffered(), "video/mp4")
            }
            assertEquals(FailureReason.INVALID, ex.reason)
        }
    }
}
