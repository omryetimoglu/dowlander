package com.evasi.downloader.media

import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PackagedExtractorTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun reusesHealthyInstallAndReplacesOnApkChangeOrTruncation() {
        val target = File(temp.root, "yt-dlp")
        fun install(apk: String, value: String) = installPackagedExtractor(target, apk) { value.byteInputStream() }
        install("install-1", "first")
        installPackagedExtractor(target, "install-1") { error("Reopened resource on unchanged install") }
        assertEquals("first", target.readText())

        assertThrows(IOException::class.java) {
            installPackagedExtractor(target, "install-2") { throw IOException("Unavailable resource") }
        }
        assertEquals("first", target.readText())
        install("install-2", "later")
        assertEquals("later", target.readText())
        target.writeText("l")
        install("install-2", "later")
        assertEquals("later", target.readText())
        assertTrue(target.delete())
        install("install-2", "later")
        assertEquals("later", target.readText())
    }
}
