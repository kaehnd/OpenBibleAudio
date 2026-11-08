package com.openbibleaudio.openbibleaudio.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.openbibleaudio.core.model.MediaId
import com.openbibleaudio.openbibleaudio.MainActivity
import com.openbibleaudio.openbibleaudio.R
import com.openbibleaudio.openbibleaudio.data.LibraryRepository
import com.openbibleaudio.openbibleaudio.data.ResumptionState
import com.openbibleaudio.openbibleaudio.data.ResumptionStore
import com.openbibleaudio.openbibleaudio.data.Settings
import com.openbibleaudio.openbibleaudio.data.SettingsRepository
import com.openbibleaudio.openbibleaudio.data.db.ChapterCompletionEntity
import com.openbibleaudio.openbibleaudio.data.db.CompletionDao
import com.openbibleaudio.openbibleaudio.library.BibleAudioSource
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/**
 * The one playback service (AD-2, spec 0002 §7). Android Auto and the phone UI are both
 * clients of its [MediaLibraryService.MediaLibrarySession].
 */
// Media3 marks content-style extras, CBR seeking, media-button slots and setPlaybackException as
// unstable API; they're the documented way to build an Auto media app.
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class PlaybackService : MediaLibraryService() {
    @Inject lateinit var library: LibraryRepository
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var resumption: ResumptionStore
    @Inject lateinit var audioSource: BibleAudioSource
    @Inject lateinit var completions: CompletionDao

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val snapshot = MutableStateFlow<LibrarySnapshot?>(null)
    private val tracker = CompletionTracker()
    private var settings = Settings()

    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibrarySession
    private lateinit var books: BookTitles
    private lateinit var tree: BrowseTree
    private lateinit var items: MediaItemFactory

    override fun onCreate() {
        super.onCreate()
        books = BookTitles(this)
        tree = BrowseTree(ResourceBrowseTitles(this, books))
        items = MediaItemFactory(books)
        // Seek increments are fixed when the player is built, so read settings once up front.
        // A small DataStore read; changes to the increments apply the next time the service starts.
        settings = runBlocking { settingsRepository.current() }

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                // BSB files are CBR MP3 without a Xing header; without this, seeking and duration are unreliable (§7.2).
                DefaultMediaSourceFactory(this, DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)),
            )
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setSeekBackIncrementMs(settings.seekBackMs)
            .setSeekForwardIncrementMs(settings.seekForwardMs)
            .build()
        player.setPlaybackSpeed(settings.bibleSpeed)
        player.addListener(PlayerListener())

        val activity = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(activity)
            .setMediaButtonPreferences(mediaButtons())
            .build()

        scope.launch { library.snapshot.collect(::onSnapshot) }
        scope.launch {
            settingsRepository.settings.collect { s ->
                settings = s
                if (player.playbackParameters.speed != s.bibleSpeed) player.setPlaybackSpeed(s.bibleSpeed)
            }
        }
        scope.launch { tick() }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        saveNow()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        saveNow()
        scope.cancel()
        session.release()
        player.release()
        super.onDestroy()
    }

    // --- Library changes -------------------------------------------------------------------

    private fun onSnapshot(new: LibrarySnapshot) {
        val old = snapshot.value
        snapshot.value = new
        if (old == null || old == new) return
        // Tell Auto to refresh what may have changed (AD-13).
        val ids = buildSet {
            add(MediaId.ROOT)
            add(MediaId.BIBLE)
            (old.versions + new.versions).forEach { v ->
                add(MediaId.Version(v.versionId).format())
                com.openbibleaudio.core.model.Testament.entries.forEach { add(MediaId.TestamentNode(v.versionId, it).format()) }
            }
        }
        ids.forEach { session.notifyChildrenChanged(it, Int.MAX_VALUE, null) }

        // A version deleted while playing from it: stop and clear (spec 0002 §7.9).
        val current = player.currentMediaItem?.let { MediaId.parse(it.mediaId) } as? MediaId.Chapter
        if (current != null && new.version(current.versionId) == null) {
            player.stop()
            player.clearMediaItems()
        }
    }

    private suspend fun snap(): LibrarySnapshot = snapshot.filterNotNull().first()

    // --- Queues ----------------------------------------------------------------------------

    private suspend fun queueFor(start: MediaId.Chapter, startPositionMs: Long): MediaSession.MediaItemsWithStartPosition {
        val s = snap()
        val version = s.version(start.versionId) ?: throw NotFound(getString(R.string.error_version_missing, start.versionId))
        val refs = QueueBuilder.build(start.ref, settings.continuousPlayScope, s.chapters(start.versionId))
        if (refs.isEmpty()) throw NotFound(getString(R.string.error_chapter_missing, books.chapter(start.ref), version.abbreviation))
        val first = audioSource.resolve(start.versionId, start.ref)
        if (first == null || !first.exists) {
            throw NotFound(getString(R.string.error_audio_missing, books.chapter(start.ref), version.abbreviation))
        }
        val resolved = audioSource.resolveAll(start.versionId, refs)
        val perBook = s.chapters(start.versionId).groupingBy { it.book }.eachCount()
        val mediaItems = refs.mapNotNull { ref ->
            resolved[ref]?.let { items.playable(MediaId.Chapter(start.versionId, ref), version, it, perBook[ref.book] ?: 0) }
        }
        return MediaSession.MediaItemsWithStartPosition(mediaItems, 0, startPositionMs.coerceAtLeast(0))
    }

    private suspend fun singleItem(id: MediaId.Chapter): MediaItem? {
        val s = snap()
        val version = s.version(id.versionId) ?: return null
        val resolved = audioSource.resolve(id.versionId, id.ref)?.takeIf { it.exists } ?: return null
        return items.playable(id, version, resolved, s.chapters(id.versionId, id.ref.book).size)
    }

    /** The saved chapter and position, or the first available chapter (spec 0002 §7.5). */
    private suspend fun resumptionQueue(): MediaSession.MediaItemsWithStartPosition {
        val s = snap()
        val saved = resumption.get()
        val savedId = saved?.let { MediaId.parse(it.mediaId) } as? MediaId.Chapter
        if (savedId != null && s.has(savedId.versionId, savedId.ref)) {
            return queueFor(savedId, ResumptionPolicy.startPosition(saved.positionMs))
        }
        val version = s.versions.firstOrNull() ?: throw NotFound(getString(R.string.browse_no_library))
        val first = s.chapters(version.versionId).first()
        return queueFor(MediaId.Chapter(version.versionId, first), 0)
    }

    private suspend fun voiceQueue(query: String): MediaSession.MediaItemsWithStartPosition {
        val preferred = (resumption.get()?.let { MediaId.parse(it.mediaId) } as? MediaId.Chapter)?.versionId
        return when (val request = VoiceSearch.resolve(query, snap(), preferred)) {
            VoiceRequest.Resume -> resumptionQueue()
            is VoiceRequest.Play -> queueFor(request.chapter, 0)
            is VoiceRequest.NotFound -> throw NotFound(getString(R.string.error_search_not_found, query, request.versionAbbreviation ?: getString(R.string.app_name)))
        }
    }

    /** A user-facing failure; its message is shown (and read out) by Auto. */
    private class NotFound(message: String) : Exception(message)

    private fun <T> withErrors(block: suspend () -> T): ListenableFuture<T> = scope.future {
        try {
            block()
        } catch (e: NotFound) {
            Log.w(TAG, e.message.orEmpty())
            session.setPlaybackException(PlaybackException(e.message, null, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND))
            throw e
        }
    }

    // --- Session callback ------------------------------------------------------------------

    private inner class LibraryCallback : MediaLibrarySession.Callback {
        private fun rootParams() = LibraryParams.Builder().setExtras(
            Bundle().apply {
                putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
                putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
            },
        ).build()

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(items.browseItem(tree.root()), rootParams()))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val id = MediaId.parse(parentId)
            val children = id?.let { tree.children(it, snap()) }
                ?: return@future LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            LibraryResult.ofItemList(page(children, page, pageSize).map(items::browseItem), params)
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
            val node = MediaId.parse(mediaId)?.let { tree.node(it, snap()) }
                ?: return@future LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            LibraryResult.ofItem(items.browseItem(node), null)
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = scope.future {
            val count = searchResults(query).size
            session.notifySearchResultChanged(browser, query, count, params)
            LibraryResult.ofVoid()
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            LibraryResult.ofItemList(page(searchResults(query), page, pageSize).map(items::browseItem), params)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = withErrors {
            mediaItems.mapNotNull { request ->
                val query = request.requestMetadata.searchQuery
                if (query != null) {
                    voiceQueue(query).mediaItems.firstOrNull()
                } else {
                    (MediaId.parse(request.mediaId) as? MediaId.Chapter)?.let { singleItem(it) }
                }
            }.toMutableList()
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = withErrors {
            val single = mediaItems.singleOrNull()
            val query = single?.requestMetadata?.searchQuery
            val chapter = single?.let { MediaId.parse(it.mediaId) } as? MediaId.Chapter
            when {
                query != null -> voiceQueue(query)
                // One chapter: expand it into a continuous-play queue (§7.3).
                chapter != null -> queueFor(chapter, if (startPositionMs == C.TIME_UNSET) 0 else startPositionMs)
                else -> {
                    val resolved = mediaItems.mapNotNull { (MediaId.parse(it.mediaId) as? MediaId.Chapter)?.let { id -> singleItem(id) } }
                    if (resolved.isEmpty()) throw NotFound(getString(R.string.error_nothing_to_play))
                    MediaSession.MediaItemsWithStartPosition(resolved, startIndex.coerceIn(0, resolved.size - 1), startPositionMs)
                }
            }
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = withErrors { resumptionQueue() }
    }

    private suspend fun searchResults(query: String) = snap().let { s ->
        val preferred = (resumption.get()?.let { MediaId.parse(it.mediaId) } as? MediaId.Chapter)?.versionId
        VoiceSearch.results(query, s, preferred).mapNotNull { tree.node(it, s) }
    }

    private fun <T> page(list: List<T>, page: Int, pageSize: Int): List<T> {
        if (pageSize <= 0 || page < 0) return list
        val from = page.toLong() * pageSize
        if (from >= list.size) return emptyList()
        return list.subList(from.toInt(), minOf(list.size.toLong(), from + pageSize).toInt())
    }

    private fun mediaButtons(): List<CommandButton> = listOf(
        CommandButton.Builder(seekBackIcon(settings.seekBackMs))
            .setDisplayName(getString(R.string.seek_back, settings.seekBackMs / 1000))
            .setPlayerCommand(Player.COMMAND_SEEK_BACK)
            .setSlots(CommandButton.SLOT_BACK_SECONDARY)
            .build(),
        CommandButton.Builder(seekForwardIcon(settings.seekForwardMs))
            .setDisplayName(getString(R.string.seek_forward, settings.seekForwardMs / 1000))
            .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
            .setSlots(CommandButton.SLOT_FORWARD_SECONDARY)
            .build(),
    )

    private fun seekBackIcon(ms: Long) = when (ms) {
        5_000L -> CommandButton.ICON_SKIP_BACK_5
        10_000L -> CommandButton.ICON_SKIP_BACK_10
        15_000L -> CommandButton.ICON_SKIP_BACK_15
        30_000L -> CommandButton.ICON_SKIP_BACK_30
        else -> CommandButton.ICON_SKIP_BACK
    }

    private fun seekForwardIcon(ms: Long) = when (ms) {
        5_000L -> CommandButton.ICON_SKIP_FORWARD_5
        10_000L -> CommandButton.ICON_SKIP_FORWARD_10
        15_000L -> CommandButton.ICON_SKIP_FORWARD_15
        30_000L -> CommandButton.ICON_SKIP_FORWARD_30
        else -> CommandButton.ICON_SKIP_FORWARD
    }

    // --- Position saving & completions (§7.6, §7.8) ----------------------------------------

    private suspend fun tick() {
        var n = 0
        while (scope.isActive) {
            delay(1_000)
            if (player.isPlaying) {
                tracker.onPosition(player.currentPosition, player.duration.takeIf { it != C.TIME_UNSET } ?: 0)
                if (++n % 5 == 0) saveAsync()
            }
        }
    }

    private fun currentState(): ResumptionState? {
        val item = player.currentMediaItem ?: return null
        if (MediaId.parse(item.mediaId) !is MediaId.Chapter) return null
        return ResumptionState(item.mediaId, player.currentPosition, System.currentTimeMillis())
    }

    private fun saveAsync() {
        val state = currentState() ?: return
        scope.launch { resumption.save(state) }
    }

    private fun saveNow() {
        val state = currentState() ?: return
        runBlocking { resumption.save(state) }
    }

    private fun record(mediaId: String?) {
        val id = mediaId?.let { MediaId.parse(it) } as? MediaId.Chapter ?: return
        scope.launch(Dispatchers.IO) {
            completions.insert(ChapterCompletionEntity(versionId = id.versionId, book = id.ref.book, chapter = id.ref.chapter, completedAt = System.currentTimeMillis()))
        }
    }

    private inner class PlayerListener : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            record(tracker.end(naturalEnd = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO))
            tracker.begin(mediaItem?.mediaId, mediaItem?.mediaMetadata?.durationMs ?: 0)
            saveAsync()
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) record(tracker.end(naturalEnd = true))
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // Clear a stale user-facing error once something plays again.
            if (isPlaying) session.setPlaybackException(null) else saveAsync()
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (oldPosition.mediaItemIndex == newPosition.mediaItemIndex) {
                tracker.onPosition(oldPosition.positionMs, player.duration.takeIf { it != C.TIME_UNSET } ?: 0)
            }
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            if (playbackParameters.speed != settings.bibleSpeed) {
                scope.launch { settingsRepository.setBibleSpeed(playbackParameters.speed) }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val id = player.currentMediaItem?.mediaId
            Log.e(TAG, "Playback error on $id", error)
            val chapter = id?.let { MediaId.parse(it) } as? MediaId.Chapter ?: return
            val abbreviation = snapshot.value?.version(chapter.versionId)?.abbreviation ?: chapter.versionId
            session.setPlaybackException(
                PlaybackException(getString(R.string.error_audio_missing, books.chapter(chapter.ref), abbreviation), error, error.errorCode),
            )
        }
    }

    private companion object {
        const val TAG = "PlaybackService"
    }
}
