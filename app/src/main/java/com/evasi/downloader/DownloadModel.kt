package com.evasi.downloader

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.evasi.downloader.media.MediaPost
import com.evasi.downloader.media.MediaRepository
import com.evasi.downloader.media.Quality
import com.evasi.downloader.queue.DownloadQueue
import com.evasi.downloader.ui.*
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DownloadModel(app: Application, private val savedState: SavedStateHandle) : AndroidViewModel(app) {
    internal val prefs = Preferences(app)
    internal val queue by lazy { DownloadQueue.get(app) }
    private val repo = MediaRepository(app)
    internal val settings = prefs.settings.catch { error ->
        if (error !is java.io.IOException) throw error
        emit(AppSettings(language = prefs.language, autoStart = false, wifiOnly = true))
    }.stateIn(viewModelScope, SharingStarted.Eagerly,
        AppSettings(language = prefs.language, autoStart = prefs.autoStart))

    internal val session = DownloadSession(viewModelScope, object : HomeBackend {
        override suspend fun resolve(input: Incoming.Link) = repo.resolve(input.url)
        override suspend fun enqueue(input: Incoming, quality: Quality?, post: MediaPost?) = queue.enqueue(input, quality, post)
        override fun observe(id: String) = queue.watchJob(id).filterNotNull().distinctUntilChanged()
        override suspend fun outputs(id: String): SavedOutput {
            val uris = queue.outputUris(id)
            val last = queue.history.first().find { it.uri == uris.lastOrNull() }
            val size = last?.width?.let { w -> last.height?.let { h -> Quality(w, h) } }
            return SavedOutput(uris, last?.title, size, last?.mime)
        }
        override suspend fun retry(id: String) { queue.retry(id) }
        override suspend fun cancel(id: String) { queue.cancel(id) }
    }, prefs.language, prefs.autoStart, SystemClock::elapsedRealtime) { id -> savedState["home_job"] = id }

    init {
        savedState.get<String>("home_job")?.let(session::restore)
        viewModelScope.launch {
            settings.collect {
                session.preferredSize = it.defaultQuality
                session.event(UiEvent.SetAutoStart(it.autoStart))
                session.event(UiEvent.SetLanguage(it.language))
            }
        }
        // The queue owns transfer notifications and reconciles its workers on application start.
        queue
    }

    internal fun handle(event: UiEvent) {
        when (event) {
            is UiEvent.SetLanguage -> prefs.language = event.tag
            is UiEvent.SetAutoStart -> prefs.autoStart = event.enabled
            else -> Unit
        }
        session.event(event)
    }
}
