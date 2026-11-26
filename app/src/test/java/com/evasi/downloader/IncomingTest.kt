package com.evasi.downloader

import org.junit.Assert.*
import org.junit.Test

class IncomingTest {
    @Test fun extractsPostFromCaptionWithHashtagsAndOtherLinks() {
        val post = "https://mastodon.social/@example/12345"
        assertEquals(post, SharedLinks.extract("Read https://example.org/ then watch ($post). #video"))
        assertEquals(post, SharedLinks.extract(post))
        assertNull(SharedLinks.extract("an ordinary caption #video"))
    }

    @Test fun ignoresTheOldClipboardAfterProcessingOrReceivingAShare() {
        val history = ClipboardRecord("https://example.org/a", 1000, 2000)
        assertFalse(history.isNew("https://example.org/a", 1000))
        assertFalse(history.isNew("https://example.org/another-old-clip", 1500))
        assertTrue(history.isNew("https://example.org/a", 3000))
        assertTrue(history.isNew("https://example.org/b", 3001))
        assertFalse(ClipboardRecord("same", 0, -1).isNew("same", 0))
    }
}
