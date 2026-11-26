package com.evasi.downloader.media

import org.junit.Assert.assertEquals
import org.junit.Test

class SyndicationTokenTest {
    @Test fun matchesTheJavascriptEmbedToken() {
        // Reference results from the embed formula evaluated in Node.js.
        assertEquals("55ih682sflo", syndicationToken("2125825261702635789"))
        assertEquals("6dq1a2xwd93", syndicationToken("20"))
        assertEquals("14pqoxyapl", syndicationToken("10000000000041"))
        assertEquals("18ps5lqos4lc", syndicationToken("18446744073709551615"))
    }
}
