package com.evasi.downloader

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.evasi.downloader.media.MediaItem
import com.evasi.downloader.media.MediaPost
import com.evasi.downloader.media.MediaRepository
import com.evasi.downloader.media.Quality
import com.evasi.downloader.ui.*
import kotlinx.coroutines.launch

class DownloadModel(app: Application) : AndroidViewModel(app) {
    internal val prefs = Preferences(app)
    private val repo = MediaRepository(app)
    private val notifications = DownloadNotifications(app)
    internal val session = DownloadSession(viewModelScope, object : TransferBackend {
        override suspend fun resolve(input: Incoming): MediaPost = when (input) {
            is Incoming.Link -> repo.resolve(input.url)
            is Incoming.Files -> {
                val posts = input.uris.map { repo.resolveShared(Uri.parse(it), input.mime) }
                posts.singleOrNull() ?: MediaPost(posts.flatMap { it.items })
            }
            Incoming.Invalid -> error("Missing input")
        }
        override suspend fun save(item: MediaItem, quality: Quality?, progress: (Long, Long?) -> Unit): String =
            repo.save(item, quality, progress).toString()
    }, prefs.language, prefs.autoStart, SystemClock::elapsedRealtime)

    init {
        viewModelScope.launch { repo.recoverInterrupted() }
        viewModelScope.launch { session.state.collect { notifications.update(it, session.galleryUri) } }
    }

    internal fun handle(event: UiEvent) {
        when (event) {
            is UiEvent.SetLanguage -> prefs.language = event.tag
            is UiEvent.SetAutoStart -> prefs.autoStart = event.enabled
            else -> Unit
        }
        session.event(event)
    }

    override fun onCleared() { notifications.clearProgress() }
}
