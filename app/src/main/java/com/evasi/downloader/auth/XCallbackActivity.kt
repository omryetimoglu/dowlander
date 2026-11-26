package com.evasi.downloader.auth

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.evasi.downloader.MainActivity
import java.net.URI

class XCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val address = intent.dataString?.takeIf { it.length <= 8192 }
            ?.let { runCatching { URI(it) }.getOrNull() }
        if (intent.action == Intent.ACTION_VIEW && address != null &&
            XProtocol.matchesRedirect(XAccounts.get(applicationContext).config, address)) {
            XAccounts.receive(applicationContext, address)
            startActivity(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        finish()
    }
}
