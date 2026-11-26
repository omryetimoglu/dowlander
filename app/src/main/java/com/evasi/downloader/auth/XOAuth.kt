package com.evasi.downloader.auth

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

internal data class XConfig(val clientId: String, val redirect: String) {
    val available: Boolean get() = clientId.isNotBlank() && runCatching {
        val uri = URI(redirect)
        uri.isAbsolute && !uri.host.isNullOrBlank() && uri.rawQuery == null && uri.rawFragment == null &&
            uri.rawUserInfo == null && !uri.rawPath.isNullOrEmpty() &&
            (uri.scheme == "https" || (uri.scheme.contains('.') && uri.port == -1))
    }.getOrDefault(false)
}

internal data class XPending(val state: String, val verifier: String, val createdAt: Long)
internal data class XGrant(val username: String, val accessToken: String, val expiresAt: Long)
internal enum class CallbackResult { IGNORE, DENIED, EXCHANGE }
internal data class XCallback(val result: CallbackResult, val code: String = "")

internal object XProtocol {
    const val scope = "tweet.read users.read"
    private val random = SecureRandom()
    private fun base64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun nonce(): String = base64(ByteArray(32).also(random::nextBytes))
    fun pending(now: Long) = XPending(nonce(), nonce(), now)
    fun challenge(verifier: String) = base64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    fun encode(fields: Map<String, String>) = fields.entries.joinToString("&") {
        URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
    }

    fun authorize(config: XConfig, pending: XPending): URI {
        require(config.available)
        return URI("https://x.com/i/oauth2/authorize?" + encode(mapOf(
            "response_type" to "code", "client_id" to config.clientId, "redirect_uri" to config.redirect,
            "scope" to scope, "state" to pending.state, "code_challenge" to challenge(pending.verifier),
            "code_challenge_method" to "S256",
        )))
    }

    fun matchesRedirect(config: XConfig, callback: URI): Boolean {
        if (!config.available) return false
        val target = URI(config.redirect)
        return callback.scheme == target.scheme && callback.rawAuthority == target.rawAuthority &&
            callback.rawPath == target.rawPath && callback.rawFragment == null
    }

    fun callback(config: XConfig, pending: XPending, callback: URI, now: Long): XCallback {
        val ignore = XCallback(CallbackResult.IGNORE)
        if (now - pending.createdAt !in 0L..600_000L || !matchesRedirect(config, callback)) return ignore
        val fields = mutableMapOf<String, String>()
        for (entry in callback.rawQuery.orEmpty().split('&')) {
            val key = URLDecoder.decode(entry.substringBefore('='), "UTF-8")
            val value = URLDecoder.decode(entry.substringAfter('=', ""), "UTF-8")
            if (fields.put(key, value) != null) return ignore
        }
        if (fields["state"] != pending.state) return ignore
        if (fields.containsKey("error")) return XCallback(CallbackResult.DENIED)
        val code = fields["code"]?.takeIf { it.isNotBlank() } ?: return ignore
        return XCallback(CallbackResult.EXCHANGE, code)
    }
}

internal interface XApi {
    fun verifyGrant(config: XConfig, pending: XPending, code: String): XGrant
    fun revoke(config: XConfig, token: String)
}

internal class XHttpApi(
    private val now: () -> Long = System::currentTimeMillis,
    private val connection: (String) -> HttpURLConnection = { URI(it).toURL().openConnection() as HttpURLConnection },
) : XApi {
    override fun verifyGrant(config: XConfig, pending: XPending, code: String): XGrant {
        val request = mapOf("grant_type" to "authorization_code", "client_id" to config.clientId,
            "redirect_uri" to config.redirect, "code_verifier" to pending.verifier, "code" to code)
        val token = JSONObject(call("https://api.x.com/2/oauth2/token", request))
        check(token.getString("token_type").equals("bearer", true))
        val access = token.getString("access_token")
        require(access.isNotBlank() && '\r' !in access && '\n' !in access)
        val lifetime = token.getLong("expires_in")
        val issuedAt = now()
        require(lifetime in 1..(Long.MAX_VALUE - issuedAt) / 1000)
        val expiry = issuedAt + lifetime * 1000
        val profile = JSONObject(call("https://api.x.com/2/users/me", bearer = access)).getJSONObject("data")
        require(profile.getString("id").isNotBlank())
        val username = profile.getString("username")
        require(username.isNotBlank())
        return XGrant(username, access, expiry)
    }

    override fun revoke(config: XConfig, token: String) {
        call("https://api.x.com/2/oauth2/revoke", mapOf("client_id" to config.clientId, "token" to token,
            "token_type_hint" to "access_token"))
    }

    private fun call(url: String, form: Map<String, String>? = null, bearer: String? = null): String {
        val connection = connection(url)
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/json")
            bearer?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (form != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connection.outputStream.use { it.write(XProtocol.encode(form).toByteArray(Charsets.UTF_8)) }
            }
            check(connection.responseCode in 200..299)
            return connection.inputStream.bufferedReader().use { reader ->
                val text = StringBuilder()
                val buffer = CharArray(2048)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    require(text.length + count <= 64 * 1024)
                    text.append(buffer, 0, count)
                }
                text.toString()
            }
        } finally {
            connection.disconnect()
        }
    }
}
