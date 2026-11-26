package com.evasi.downloader.auth

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.KeyStore
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class XVaultDeviceTest {
    @Test fun encryptedRecordSurvivesRecreationRejectsTamperingAndIsRemovedOnLogout() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val alias = "evasi-test-" + UUID.randomUUID()
        val directory = File(app.cacheDir, alias)
        check(directory.mkdir())
        val isolated = object : ContextWrapper(app) { override fun getNoBackupFilesDir() = directory }
        val grant = XGrant("fixture-account", "fixture-token-sensitive", 9000)
        val pending = XPending("fixture-state", "fixture-verifier-sensitive", 200)
        val record = LoginRecord(pending = pending, grant = grant)
        val vault = XVault(isolated, alias)
        try {
            assertEquals(LoginRecord(), vault.read())
            vault.write(record)
            val file = directory.listFiles()!!.single()
            val ciphertext = file.readBytes()
            val encryptedText = ciphertext.toString(Charsets.ISO_8859_1)
            for (sensitive in listOf(grant.username, grant.accessToken, pending.state, pending.verifier)) {
                assertFalse(encryptedText.contains(sensitive))
            }
            assertEquals(record, XVault(isolated, alias).read())
            ciphertext[ciphertext.lastIndex] = (ciphertext.last().toInt() xor 1).toByte()
            file.writeBytes(ciphertext)
            // Some Android providers wrap the authentication exception.
            assertThrows(Exception::class.java) { vault.read() }
            vault.write(LoginRecord())
            assertEquals(LoginRecord(), vault.read())
            assertTrue(directory.listFiles()!!.isEmpty())
        } finally {
            directory.deleteRecursively()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        }
    }
}
