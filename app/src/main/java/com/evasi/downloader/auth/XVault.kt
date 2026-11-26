package com.evasi.downloader.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class LoginRecord(val pending: XPending? = null, val grant: XGrant? = null)
internal interface XStore {
    fun read(): LoginRecord
    fun write(record: LoginRecord)
}

/** The verifier must survive browser redirects and Android process recreation. */
internal class XVault(context: Context, private val alias: String = "evasi-x-account-v1") : XStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, "x-account-v1"))
    private fun key(): SecretKey {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keys.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }

    override fun read(): LoginRecord {
        val bytes = try { file.readFully() } catch (_: java.io.FileNotFoundException) { return LoginRecord() }
        require(bytes.size in 29..131072)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
        val data = JSONObject(cipher.doFinal(bytes, 12, bytes.size - 12).toString(Charsets.UTF_8))
        return LoginRecord(
            data.optJSONObject("pending")?.let { XPending(it.getString("state"), it.getString("verifier"), it.getLong("started")) },
            data.optJSONObject("grant")?.let { XGrant(it.getString("username"), it.getString("token"), it.getLong("expires")) },
        )
    }

    override fun write(record: LoginRecord) {
        if (record == LoginRecord()) {
            file.delete()
            check(!file.baseFile.exists())
            return
        }
        val data = JSONObject()
        record.pending?.let { data.put("pending", JSONObject().put("state", it.state).put("verifier", it.verifier).put("started", it.createdAt)) }
        record.grant?.let { data.put("grant", JSONObject().put("username", it.username).put("token", it.accessToken).put("expires", it.expiresAt)) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(data.toString().toByteArray(Charsets.UTF_8))
        require(cipher.iv.size == 12)
        val output = file.startWrite()
        try {
            output.write(cipher.iv)
            output.write(encrypted)
            file.finishWrite(output)
        } catch (e: Exception) {
            file.failWrite(output)
            throw e
        }
    }
}
