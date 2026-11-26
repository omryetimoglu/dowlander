package com.evasi.downloader

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences as StoredPreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import coil.imageLoader
import com.evasi.downloader.media.DEFAULT_ALBUM
import com.evasi.downloader.ui.AppAppearance
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal val qualityLimits = listOf(0, 2160, 1440, 1080, 720, 480, 360)

internal data class AppSettings(
    val language: String = "en",
    val autoStart: Boolean = false,
    val appearance: AppAppearance = AppAppearance(),
    val defaultQuality: Int = 0,
    val album: String = DEFAULT_ALBUM,
    val wifiOnly: Boolean = false,
    val concurrentDownloads: Int = 1,
)

// One MediaStore directory component; reject traversal, hidden paths and FAT-reserved characters.
internal fun validAlbumName(value: String): Boolean {
    val name = value.trim()
    return name.isNotEmpty() && name.length <= 64 && !name.startsWith('.') && !name.endsWith('.') &&
        name.none { it.isISOControl() || it in "/\\:*?\"<>|" } &&
        name.toByteArray(Charsets.UTF_8).size <= 192
}

private object Keys {
    val migrated = booleanPreferencesKey("migrated_evasi_settings")
    val language = stringPreferencesKey("language")
    val autoStart = booleanPreferencesKey("auto_start")
    val theme = stringPreferencesKey("theme")
    val accent = stringPreferencesKey("accent")
    val glass = booleanPreferencesKey("glass")
    val blur = floatPreferencesKey("blur")
    val animations = booleanPreferencesKey("animations")
    val compact = booleanPreferencesKey("compact")
    val quality = intPreferencesKey("quality")
    val album = stringPreferencesKey("album")
    val wifi = booleanPreferencesKey("wifi_only")
    val concurrent = intPreferencesKey("concurrent")
}

// The language and auto-start bootstrap values are also needed before the first Flow emission.
// Clipboard timestamps and fingerprints are operational state, separate from user settings.
private val Context.preferenceStore by preferencesDataStore(
    name = "evasi_settings",
    produceMigrations = { listOf(legacySettingsMigration(it)) },
)

internal fun legacySettingsMigration(context: Context): DataMigration<StoredPreferences> {
    val old = context.getSharedPreferences("evasi", Context.MODE_PRIVATE)
    return object : DataMigration<StoredPreferences> {
        override suspend fun shouldMigrate(currentData: StoredPreferences) = currentData[Keys.migrated] != true
        override suspend fun migrate(currentData: StoredPreferences): StoredPreferences =
            currentData.toMutablePreferences().apply {
                if (this[Keys.language] == null) this[Keys.language] = if (old.getString("language", "en") == "tr") "tr" else "en"
                if (this[Keys.autoStart] == null) this[Keys.autoStart] = old.getBoolean("auto_start", false)
                this[Keys.migrated] = true
            }
        override suspend fun cleanUp() = Unit
    }
}

private fun AppSettings.normalized() = copy(
    language = if (language == "tr") "tr" else "en",
    appearance = appearance.copy(
        theme = appearance.theme.takeIf { it in setOf("system", "light", "dark") } ?: "system",
        accent = appearance.accent.takeIf { it in setOf("blue", "purple", "teal", "rose") } ?: "blue",
        blur = appearance.blur.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.5f,
    ),
    defaultQuality = defaultQuality.takeIf { it in qualityLimits } ?: 0,
    album = album.trim().takeIf(::validAlbumName) ?: DEFAULT_ALBUM,
    concurrentDownloads = concurrentDownloads.coerceIn(1, 3),
)

private fun StoredPreferences.decode(): AppSettings {
    val defaults = AppAppearance()
    return AppSettings(
        language = this[Keys.language] ?: "en",
        autoStart = this[Keys.autoStart] ?: false,
        appearance = AppAppearance(
            theme = this[Keys.theme] ?: defaults.theme, accent = this[Keys.accent] ?: defaults.accent,
            glass = this[Keys.glass] ?: defaults.glass, blur = this[Keys.blur] ?: defaults.blur,
            animations = this[Keys.animations] ?: defaults.animations, compact = this[Keys.compact] ?: defaults.compact,
        ),
        defaultQuality = this[Keys.quality] ?: 0, album = this[Keys.album] ?: DEFAULT_ALBUM,
        wifiOnly = this[Keys.wifi] ?: false, concurrentDownloads = this[Keys.concurrent] ?: 1,
    ).normalized()
}

internal class Preferences(context: Context, private val settingsStore: DataStore<StoredPreferences>? = null) {
    private val app = context.applicationContext ?: context
    private val store = app.getSharedPreferences("evasi", Context.MODE_PRIVATE)
    private val dataStore get() = settingsStore ?: app.preferenceStore
    val settings: Flow<AppSettings> get() = dataStore.data.map { it.decode() }.distinctUntilChanged()

    init {
        store.getString("last_link", null)?.let { previous ->
            store.edit().putString("last_link_hash", ClipboardRecord.fingerprint(previous)).remove("last_link").apply()
        }
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) = writes.withLock {
        val saved = dataStore.edit { values ->
            val next = transform(values.decode()).normalized()
            values[Keys.language] = next.language
            values[Keys.autoStart] = next.autoStart
            values[Keys.theme] = next.appearance.theme
            values[Keys.accent] = next.appearance.accent
            values[Keys.glass] = next.appearance.glass
            values[Keys.blur] = next.appearance.blur
            values[Keys.animations] = next.appearance.animations
            values[Keys.compact] = next.appearance.compact
            values[Keys.quality] = next.defaultQuality
            values[Keys.album] = next.album
            values[Keys.wifi] = next.wifiOnly
            values[Keys.concurrent] = next.concurrentDownloads
        }
        store.edit().putString("language", saved[Keys.language])
            .putBoolean("auto_start", saved[Keys.autoStart] == true).apply()
    }

    var language: String
        get() = if (store.getString("language", "en") == "tr") "tr" else "en"
        set(value) {
            val tag = if (value == "tr") "tr" else "en"
            store.edit().putString("language", tag).apply()
            scope.launch(start = CoroutineStart.UNDISPATCHED) { update { it.copy(language = tag) } }
        }
    var autoStart: Boolean
        get() = store.getBoolean("auto_start", false)
        set(value) {
            store.edit().putBoolean("auto_start", value).apply()
            scope.launch(start = CoroutineStart.UNDISPATCHED) { update { it.copy(autoStart = value) } }
        }

    val clipboard: ClipboardRecord
        get() = ClipboardRecord(store.getString("last_link_hash", null),
            store.getLong("last_clip_time", -1), store.getLong("share_time", -1))

    fun markClipboard(link: String, timestamp: Long) {
        store.edit().putString("last_link_hash", ClipboardRecord.fingerprint(link)).putLong("last_clip_time", timestamp).apply()
    }

    fun markShared(timestamp: Long) { store.edit().putLong("share_time", timestamp).apply() }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val writes = Mutex()

        fun localized(context: Context, language: String = Preferences(context).language): Context {
            val config = Configuration(context.resources.configuration)
            config.setLocales(LocaleList(Locale.forLanguageTag(language)))
            return context.createConfigurationContext(config)
        }
    }
}

internal suspend fun clearPreviewCache(context: Context) = withContext(Dispatchers.IO) {
    context.imageLoader.memoryCache?.clear()
    context.imageLoader.diskCache?.clear()
    Unit
}
