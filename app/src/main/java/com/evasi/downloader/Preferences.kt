package com.evasi.downloader

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

internal class Preferences(context: Context) {
    private val store = context.getSharedPreferences("evasi", Context.MODE_PRIVATE)

    var language: String
        get() = if (store.getString("language", "en") == "tr") "tr" else "en"
        set(value) { store.edit().putString("language", value).apply() }
    var autoStart: Boolean
        get() = store.getBoolean("auto_start", true)
        set(value) { store.edit().putBoolean("auto_start", value).apply() }

    val clipboard: ClipboardRecord
        get() = ClipboardRecord(store.getString("last_link", "").orEmpty(),
            store.getLong("last_clip_time", -1), store.getLong("share_time", -1))

    fun markClipboard(link: String, timestamp: Long) {
        store.edit().putString("last_link", link).putLong("last_clip_time", timestamp).apply()
    }

    fun markShared(timestamp: Long) { store.edit().putLong("share_time", timestamp).apply() }

    companion object {
        fun localized(context: Context): Context {
            val locale = Locale.forLanguageTag(Preferences(context).language)
            val config = Configuration(context.resources.configuration)
            config.setLocales(LocaleList(locale))
            return context.createConfigurationContext(config)
        }
    }
}
