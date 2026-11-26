package com.evasi.downloader.auth

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import org.junit.Assert.*
import org.junit.Test

class XProtocolTest {
    private val config = XConfig("public-client", "com.evasi.downloader://oauth/x")
    private val pending = XPending("expected", "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk", 100)

    @Test fun rfc7636ChallengeAndPublicReadOnlyBrowserRequest() {
        val address = XProtocol.authorize(config, pending)
        assertEquals("https://x.com/i/oauth2/authorize", address.toString().substringBefore('?'))
        val form = fields(address.rawQuery)
        assertEquals("S256", form["code_challenge_method"])
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", form["code_challenge"])
        assertEquals(setOf("users.read", "tweet.read"), form.getValue("scope").split(" ").toSet())
        assertEquals(config.redirect, form["redirect_uri"])
        assertEquals("expected", form["state"])
        assertFalse(form.containsKey("client_secret"))
        assertFalse(form.containsKey("code_verifier"))
        val fresh = XProtocol.pending(100)
        assertTrue(fresh.verifier.matches(Regex("[A-Za-z0-9_-]{43}")))
        assertNotEquals(fresh.verifier, fresh.state)
        assertNotEquals(fresh, XProtocol.pending(100))
    }

    @Test fun validatesCallbackRouteAgeStateAndUniquenessBeforeAcceptingCode() {
        val valid = config.redirect + "?state=expected&code=a%2Bb"
        assertEquals(XCallback(CallbackResult.EXCHANGE, "a+b"), XProtocol.callback(config, pending, URI(valid), 200))
        for (invalid in listOf(valid.replace("//oauth/", "//other/"), valid.replace("/x?", "/wrong?"),
            valid.replace("expected", "wrong"), valid.replace("state=", "missing="), valid + "&state=expected",
            valid + "#fragment", valid.replace("com.evasi.downloader:", "other.app:"))) {
            assertEquals(invalid, CallbackResult.IGNORE, XProtocol.callback(config, pending, URI(invalid), 200).result)
        }
        assertEquals(CallbackResult.IGNORE, XProtocol.callback(config, pending, URI(valid), 600_101).result)
        assertEquals(CallbackResult.DENIED, XProtocol.callback(config, pending,
            URI(config.redirect + "?state=expected&error=access_denied"), 200).result)
        assertFalse(XConfig("", config.redirect).available)
        assertFalse(XConfig(config.clientId, "").available)
        assertFalse(XConfig(config.clientId, "http://localhost/x").available)
    }

    @Test fun tokenExchangeVerifiesCurrentUserAndRevokesAsPublicClient() {
        val token = Reply("https://api.x.com/2/oauth2/token", 200,
            """{"token_type":"bearer","access_token":"test-only","expires_in":7200}""")
        val user = Reply("https://api.x.com/2/users/me", 200, """{"data":{"username":"tester","id":"42"}}""")
        val revoke = Reply("https://api.x.com/2/oauth2/revoke", 200, """{"revoked":true}""")
        val calls = ArrayDeque(listOf(token, user, revoke))
        val api = XHttpApi({ 2000 }) { url -> calls.removeFirst().also { assertEquals(url, it.url.toString()) } }
        assertEquals(XGrant("tester", "test-only", 7_202_000), api.verifyGrant(config, pending, "c+1"))
        assertEquals("POST", token.requestMethod)
        assertEquals(mapOf("client_id" to config.clientId, "grant_type" to "authorization_code",
            "redirect_uri" to config.redirect, "code" to "c+1", "code_verifier" to pending.verifier), fields(token.sent.toString("UTF-8")))
        assertEquals("GET", user.requestMethod)
        assertEquals("Bearer test-only", user.getRequestProperty("Authorization"))
        assertFalse(user.instanceFollowRedirects)
        api.revoke(config, "test-only")
        assertEquals(mapOf("client_id" to config.clientId, "token" to "test-only", "token_type_hint" to "access_token"),
            fields(revoke.sent.toString("UTF-8")))
        assertTrue(calls.isEmpty())
        assertTrue(listOf(user, token, revoke).all { it.closed })
    }

    @Test fun rejectedProfileCannotCreateLoginEvenAfterSuccessfulTokenExchange() {
        val api = XHttpApi({ 2000 }) { url -> if (url.endsWith("/token"))
            Reply(url, 200, """{"token_type":"Bearer","access_token":"test-only","expires_in":20}""")
            else Reply(url, 403, "{}") }
        assertThrows(IllegalStateException::class.java) { api.verifyGrant(config, pending, "code") }
    }

    private fun fields(form: String) = form.split('&').associate {
        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
    }

    private class Reply(address: String, private val status: Int, private val body: String) : HttpURLConnection(URL(address)) {
        val sent = ByteArrayOutputStream()
        var closed = false
        override fun connect() {}
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getOutputStream() = sent
        override fun getInputStream() = body.byteInputStream()
    }
}
