package com.openbibleaudio.openbibleaudio.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
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
import com.openbibleaudio.openbibleaudio.plans.DayQueue
import com.openbibleaudio.openbibleaudio.plans.EnrollmentSummary
import com.openbibleaudio.openbibleaudio.plans.EpisodeAvailability
import com.openbibleaudio.openbibleaudio.plans.DayStatus
import com.openbibleaudio.openbibleaudio.data.db.PodcastDao
import com.openbibleaudio.openbibleaudio.data.db.PodcastEpisodeEntity
import com.openbibleaudio.openbibleaudio.podcasts.EpisodeDownloads
import com.openbibleaudio.openbibleaudio.podcasts.EpisodePlayback
import com.openbibleaudio.openbibleaudio.podcasts.PodcastStorage
import com.openbibleaudio.openbibleaudio.podcasts.PodcastRepository
import com.openbibleaudio.openbibleaudio.plans.EpisodeUnavailable
import com.openbibleaudio.openbibleaudio.plans.PlanQueueBuilder
import com.openbibleaudio.openbibleaudio.plans.PlanRepository
import com.openbibleaudio.openbibleaudio.plans.PlanResumption
import com.openbibleaudio.openbibleaudio.plans.PlanVoice
import com.openbibleaudio.openbibleaudio.plans.QueueEntry
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.withContext
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
    @Inject lateinit var planRepository: PlanRepository
    @Inject lateinit var podcasts: PodcastRepository
    @Inject lateinit var episodeDownloads: EpisodeDownloads
    @Inject lateinit var podcastStorage: PodcastStorage
    @Inject lateinit var podcastDao: PodcastDao

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val snapshot = MutableStateFlow<LibrarySnapshot?>(null)
    private val enrollments = MutableStateFlow<List<EnrollmentSummary>?>(null)

    /** Day queues as built for the player, by (enrollment, day): completion uses the queue as built (§6.3). */
    private val builtDays = mutableMapOf<Pair<Long, Int>, BuiltDay>()

    private class BuiltDay(val queue: DayQueue, val versionId: String)

    /** Where each queued episode item starts when the queue reaches it (spec 0003 §8.5), by media ID. */
    private val episodeStarts = mutableMapOf<String, Long>()
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
        items = MediaItemFactory(books) { ChapterArtProvider.uri(this, it) }
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
        scope.launch { planRepository.enrollments.collect(::onEnrollments) }
        scope.launch {
            // A download finishing (or failing) changes Today's episode subtitle (spec 0003 §9.1, AD-13).
            var first = true
            podcastDao.observeDownloadStates().collect {
                if (first) first = false else notifyPlanNodes(enrollments.value.orEmpty())
            }
        }
        scope.launch {
            settingsRepository.settings.collect { s ->
                settings = s
                applySpeed()
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
        val versionId = when (val current = player.currentMediaItem?.let { MediaId.parse(it.mediaId) }) {
            is MediaId.Chapter -> current.versionId
            is MediaId.PlanItem -> builtDays[current.enrollmentId to current.day]?.versionId
            else -> null
        }
        if (versionId != null && new.version(versionId) == null) {
            player.stop()
            player.clearMediaItems()
        }
    }

    private fun onEnrollments(new: List<EnrollmentSummary>) {
        val old = enrollments.value
        enrollments.value = new
        if (old == null || old == new) return
        notifyPlanNodes(old + new)
    }

    /** Progress, enrollments or downloads changed: refresh Today, Plans and each enrollment (spec 0003 §9.1, AD-13). */
    private fun notifyPlanNodes(summaries: List<EnrollmentSummary>) {
        val ids = buildSet {
            add(MediaId.ROOT)
            add(MediaId.TODAY)
            add(MediaId.PLAN)
            summaries.forEach { e ->
                add(MediaId.Enrollment(e.enrollment.id).format())
                tree.dayGroups(e).forEach { (from, to) -> add(MediaId.DayGroup(e.enrollment.id, from, to).format()) }
            }
        }
        ids.forEach { session.notifyChildrenChanged(it, Int.MAX_VALUE, null) }
    }

    private suspend fun planState(): PlanBrowseState {
        val summaries = enrollments.filterNotNull().first()
        val episodes = summaries.mapNotNull { e ->
            val day = e.position.currentDay ?: return@mapNotNull null
            if (e.plan.podcast == null) return@mapNotNull null
            e.enrollment.id to EpisodePlayback.browseState(dayEpisode(e, day).availability)
        }.toMap()
        return PlanBrowseState(summaries, resumeEntry(), episodes)
    }

    /** The saved item as a "Continue: …" entry on the Today tab, if it can still be played. */
    private suspend fun resumeEntry(): ResumeEntry? {
        val saved = resumption.get() ?: return null
        val title = when (val id = MediaId.parse(saved.mediaId)) {
            is MediaId.Chapter -> id.takeIf { snap().has(it.versionId, it.ref) }?.let { books.chapter(it.ref) }
            is MediaId.PlanItem -> enrollments.value?.firstOrNull { it.enrollment.id == id.enrollmentId }
                ?.let { getString(R.string.plan_queue_album, it.plan.name, id.day) }
            else -> null
        } ?: return null
        return ResumeEntry(saved.mediaId, title)
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

    // --- Plan day queues (spec 0003 §6.2, §9.2) ------------------------------------------------

    /**
     * Day [day] of [enrollmentId] (null = the current day), starting at queue index [startIndex].
     * Items are always `plan/{e}/day/{n}/{i}` so completion and resumption know their day.
     */
    private suspend fun planQueue(enrollmentId: Long, day: Int?, startIndex: Int = 0, startPositionMs: Long = 0): MediaSession.MediaItemsWithStartPosition {
        val summary = planRepository.summary(enrollmentId) ?: throw NotFound(getString(R.string.error_plan_missing))
        val d = day ?: summary.position.currentDay ?: throw NotFound(getString(R.string.error_plan_finished, summary.plan.name))
        val mediaItems = planItems(summary, d)
        val start = mediaItems.indexOfFirst { (MediaId.parse(it.mediaId) as? MediaId.PlanItem)?.index == startIndex }
        val position = when {
            start < 0 -> 0
            // Starting on an episode with no explicit position: where it stopped last time (§8.5).
            startPositionMs <= 0 -> episodeStarts[mediaItems[start].mediaId] ?: 0
            else -> startPositionMs
        }
        return MediaSession.MediaItemsWithStartPosition(mediaItems, start.coerceAtLeast(0), position)
    }

    private class DayEpisode(val availability: EpisodeAvailability, val row: PodcastEpisodeEntity?)

    /** The day's episode and whether it can play: downloaded file, else stream (if allowed and online), else why not. */
    private suspend fun dayEpisode(summary: EnrollmentSummary, day: Int): DayEpisode {
        if (summary.plan.podcast == null) return DayEpisode(EpisodeAvailability.Unavailable(EpisodeUnavailable.NO_PODCAST), null)
        val row = podcasts.episodeForDay(summary.enrollment, summary.plan, day)
        // The app-chosen path, not the stored localPath, so nothing outside podcasts/ is ever played.
        val uri = row?.let { podcastStorage.file(it.feedId, it.guidHash) }
            ?.takeIf { withContext(Dispatchers.IO) { it.isFile } }
            ?.toURI()?.toString()
        return DayEpisode(EpisodePlayback.availability(row, uri, settings.allowEpisodeStreaming, isOnline()), row)
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private suspend fun planItems(summary: EnrollmentSummary, day: Int): List<MediaItem> {
        val s = snap()
        val version = planVersion(summary, s) ?: throw NotFound(getString(R.string.browse_no_library))
        val episode = dayEpisode(summary, day)
        val queue = PlanQueueBuilder.build(summary.enrollment.id, summary.plan, day, s.chapters(version.versionId), episode.availability)
            ?: throw NotFound(getString(R.string.error_plan_missing))
        val chapters = queue.entries.filterIsInstance<QueueEntry.Chapter>()
        val resolved = audioSource.resolveAll(version.versionId, chapters.map { it.ref })
        val album = getString(R.string.plan_queue_album, summary.plan.name, day)
        val feedTitle = if (episode.row != null) podcasts.feedTitle(summary.plan) else null
        val mediaItems = queue.entries.mapNotNull { entry ->
            when (entry) {
                is QueueEntry.Chapter -> resolved[entry.ref]?.let { items.planChapter(entry.mediaId, entry.ref, version, it, album) }
                is QueueEntry.Episode -> episode.row?.let { row ->
                    episodeStarts[entry.mediaId.format()] = EpisodePlayback.startPositionMs(row)
                    items.planEpisode(
                        entry.mediaId, entry.uri,
                        title = getString(R.string.episode_item_title, day, feedTitle ?: summary.plan.name),
                        subtitle = if (entry.streaming) getString(R.string.episode_streaming, row.title) else row.title,
                        artist = feedTitle, albumTitle = album, durationMs = row.durationMs,
                    )
                }
            }
        }
        if (mediaItems.isEmpty()) throw NotFound(getString(R.string.error_plan_day_empty, day, version.abbreviation))
        builtDays[summary.enrollment.id to day] = BuiltDay(queue, version.versionId)
        return mediaItems
    }

    /** The enrollment's Bible version, or the default at play time when it has none (or it's gone). */
    private suspend fun planVersion(summary: EnrollmentSummary, s: LibrarySnapshot): VersionInfo? =
        summary.enrollment.versionId?.let { s.version(it) }
            ?: preferredVersion()?.let { s.version(it) }
            ?: s.versions.firstOrNull()

    private suspend fun preferredVersion(): String? = when (val id = resumption.get()?.let { MediaId.parse(it.mediaId) }) {
        is MediaId.Chapter -> id.versionId
        is MediaId.PlanItem -> builtDays[id.enrollmentId to id.day]?.versionId
        else -> null
    }

    /** The enrollment of the saved plan item, for "today" by voice (spec 0003 §9.2). */
    private suspend fun lastPlayedEnrollment(): Long? = (resumption.get()?.let { MediaId.parse(it.mediaId) } as? MediaId.PlanItem)?.enrollmentId

    /** Resolves a playable plan ID to its queue, or null for other IDs. */
    private suspend fun planRequest(id: MediaId?, startPositionMs: Long): MediaSession.MediaItemsWithStartPosition? = when (id) {
        is MediaId.EnrollmentToday -> planQueue(id.enrollmentId, null)
        is MediaId.PlanDay -> planQueue(id.enrollmentId, id.day)
        is MediaId.PlanItem -> planQueue(id.enrollmentId, id.day, id.index, startPositionMs)
        else -> null
    }

    /** The saved chapter and position, or the first available chapter (spec 0002 §7.5). */
    private suspend fun resumptionQueue(): MediaSession.MediaItemsWithStartPosition {
        val s = snap()
        val saved = resumption.get()
        val savedPlan = saved?.let { MediaId.parse(it.mediaId) } as? MediaId.PlanItem
        if (savedPlan != null) {
            val summary = planRepository.summary(savedPlan.enrollmentId)
            val target = summary?.let {
                PlanResumption.target(savedPlan.day, savedPlan.index, ResumptionPolicy.startPosition(saved.positionMs), it.statuses, it.position.currentDay)
            }
            if (target != null) {
                runCatching { return planQueue(savedPlan.enrollmentId, target.day, target.index, target.positionMs) }
            }
        }
        val savedId = saved?.let { MediaId.parse(it.mediaId) } as? MediaId.Chapter
        if (savedId != null && s.has(savedId.versionId, savedId.ref)) {
            return queueFor(savedId, ResumptionPolicy.startPosition(saved.positionMs))
        }
        val version = s.versions.firstOrNull() ?: throw NotFound(getString(R.string.browse_no_library))
        val first = s.chapters(version.versionId).first()
        return queueFor(MediaId.Chapter(version.versionId, first), 0)
    }

    private suspend fun voiceQueue(spoken: String): MediaSession.MediaItemsWithStartPosition {
        val query = VoiceSearch.stripAppName(spoken)
        // "today", "my plan" or a plan's name come before reference parsing (spec 0003 §9.2).
        PlanVoice.match(query, enrollments.filterNotNull().first(), lastPlayedEnrollment())?.let { return planQueue(it, null) }
        val preferred = preferredVersion()
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Anything else would fail the future silently and leave Auto / the assistant waiting.
            Log.e(TAG, "Request failed", e)
            session.setPlaybackException(PlaybackException(getString(R.string.error_nothing_to_play), e, PlaybackException.ERROR_CODE_UNSPECIFIED))
            throw e
        }
    }

    // --- Session callback ------------------------------------------------------------------

    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val result = super.onConnect(session, controller)
            Log.i(TAG, "Connect from ${controller.packageName} (legacy=${controller.controllerVersion == MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION}, trusted=${controller.isTrusted}): accepted=${result.isAccepted}")
            return result
        }

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
            val children = id?.let { tree.children(it, snap(), planState()) }
                ?: return@future LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            LibraryResult.ofItemList(page(children, page, pageSize).map(items::browseItem), params)
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
            val node = MediaId.parse(mediaId)?.let { tree.node(it, snap(), planState()) }
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
            Log.i(TAG, "Add ${mediaItems.size} item(s) from ${controller.packageName}: query=${mediaItems.firstOrNull()?.requestMetadata?.searchQuery?.let { "\"$it\"" }}")
            mediaItems.mapNotNull { request ->
                val query = request.requestMetadata.searchQuery
                if (query != null) {
                    voiceQueue(query).mediaItems.firstOrNull()
                } else {
                    when (val id = MediaId.parse(request.mediaId)) {
                        is MediaId.Chapter -> singleItem(id)
                        is MediaId.PlanItem -> planQueue(id.enrollmentId, id.day, id.index).let { it.mediaItems[it.startIndex] }
                        is MediaId.EnrollmentToday, is MediaId.PlanDay -> planRequest(id, 0)?.mediaItems?.firstOrNull()
                        else -> null
                    }
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
            Log.i(TAG, "Set ${mediaItems.size} item(s) from ${controller.packageName}: ${single?.mediaId?.ifEmpty { null } ?: "-"} query=${query?.let { "\"$it\"" }}")
            val singleId = single?.let { MediaId.parse(it.mediaId) }
            val chapter = singleId as? MediaId.Chapter
            val position = if (startPositionMs == C.TIME_UNSET) 0 else startPositionMs
            val saved = resumption.get()
            when {
                query != null -> voiceQueue(query)
                // The Today tab's "Continue: …" item carries the saved ID: resume where it stopped.
                single != null && saved != null && single.mediaId == saved.mediaId && startPositionMs == C.TIME_UNSET -> resumptionQueue()
                singleId is MediaId.EnrollmentToday || singleId is MediaId.PlanDay || singleId is MediaId.PlanItem ->
                    planRequest(singleId, position)!!
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

    private suspend fun searchResults(spoken: String): List<BrowseNode> {
        val query = VoiceSearch.stripAppName(spoken)
        val s = snap()
        val plans = planState()
        PlanVoice.match(query, plans.enrollments, lastPlayedEnrollment())?.let { e ->
            return listOfNotNull(tree.node(MediaId.EnrollmentToday(e), s, plans))
        }
        return VoiceSearch.results(query, s, preferredVersion()).mapNotNull { tree.node(it, s) }
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
        val id = MediaId.parse(item.mediaId)
        if (id !is MediaId.Chapter && id !is MediaId.PlanItem) return null
        return ResumptionState(item.mediaId, player.currentPosition, System.currentTimeMillis())
    }

    private fun saveAsync() {
        val episode = episodePosition()
        if (episode != null) scope.launch { saveEpisode(episode) }
        val state = currentState() ?: return
        scope.launch { resumption.save(state) }
    }

    private fun saveNow() {
        val episode = episodePosition()
        val state = currentState()
        runBlocking {
            episode?.let { saveEpisode(it) }
            state?.let { resumption.save(it) }
        }
    }

    private class EpisodePosition(val mediaId: String, val entry: QueueEntry.Episode, val positionMs: Long)

    /** The playing episode and its position, if an episode is playing. */
    private fun episodePosition(): EpisodePosition? {
        val item = player.currentMediaItem ?: return null
        if (!isEpisode(item)) return null
        val id = MediaId.parse(item.mediaId) as? MediaId.PlanItem ?: return null
        val entry = builtDays[id.enrollmentId to id.day]?.queue?.entries?.firstOrNull { it.mediaId == id } as? QueueEntry.Episode ?: return null
        return EpisodePosition(item.mediaId, entry, player.currentPosition)
    }

    /** Per-episode resume (spec 0003 §8.5): saved with the resumption state, shared by every enrollment. */
    private suspend fun saveEpisode(p: EpisodePosition) {
        episodeStarts[p.mediaId] = (p.positionMs - EpisodePlayback.RESUME_BACK_MS).coerceAtLeast(0)
        podcastDao.setPosition(p.entry.feedId, p.entry.guidHash, p.positionMs, System.currentTimeMillis())
    }

    private fun record(mediaId: String?) {
        when (val id = mediaId?.let { MediaId.parse(it) }) {
            is MediaId.Chapter -> scope.launch(Dispatchers.IO) {
                completions.insert(ChapterCompletionEntity(versionId = id.versionId, book = id.ref.book, chapter = id.ref.chapter, completedAt = System.currentTimeMillis()))
            }
            is MediaId.PlanItem -> recordPlanItem(id)
            else -> Unit
        }
    }

    /**
     * Writes `enrollment_item_completion` and recomputes the day (spec 0003 §9.2). Chapters also go
     * into the global `chapter_completion` log (§5).
     */
    private fun recordPlanItem(id: MediaId.PlanItem) {
        val built = builtDays[id.enrollmentId to id.day]
        val entry = built?.queue?.entries?.firstOrNull { it.mediaId == id }
        if (built == null || entry == null) {
            Log.w(TAG, "No built queue for ${id.format()}; completion not recorded")
            return
        }
        scope.launch {
            when (entry) {
                is QueueEntry.Chapter -> completions.insert(
                    ChapterCompletionEntity(versionId = built.versionId, book = entry.ref.book, chapter = entry.ref.chapter, completedAt = System.currentTimeMillis()),
                )
                // Played: the next start is from the beginning (spec 0003 §8.5).
                is QueueEntry.Episode -> {
                    podcastDao.markPlayed(entry.feedId, entry.guidHash, System.currentTimeMillis())
                    episodeStarts[id.format()] = 0
                }
            }
            val status = planRepository.recordItemCompleted(built.queue, entry.itemKey)
            if (status == DayStatus.COMPLETE && summaryHasPodcast(id.enrollmentId)) {
                // The rolling window moves on, and finished episodes may now be deletable (spec 0003 §8.1, §8.4).
                episodeDownloads.applyRetention()
                episodeDownloads.start(id.enrollmentId)
            }
        }
    }

    private suspend fun summaryHasPodcast(enrollmentId: Long): Boolean =
        planRepository.summary(enrollmentId)?.plan?.podcast != null

    /**
     * With "continue to next day" on, appends the next open day when the last item of a day's
     * queue starts, whatever the calendar says (spec 0003 §6.2).
     */
    private fun maybeAppendNextDay(mediaItem: MediaItem?) {
        val id = mediaItem?.let { MediaId.parse(it.mediaId) } as? MediaId.PlanItem ?: return
        if (player.currentMediaItemIndex != player.mediaItemCount - 1) return
        scope.launch {
            // Read the setting now rather than the cached copy: a switch flipped just before
            // playback starts may not have reached the collector yet.
            if (!settingsRepository.current().continueToNextDay) return@launch
            val next = planRepository.nextDayAfter(id.enrollmentId, id.day) ?: return@launch
            val summary = planRepository.summary(id.enrollmentId) ?: return@launch
            val nextItems = runCatching { planItems(summary, next) }.getOrNull() ?: return@launch
            // Still on the same last item? Then the queue hasn't changed under us.
            if (player.currentMediaItem?.mediaId == mediaItem.mediaId && player.currentMediaItemIndex == player.mediaItemCount - 1) {
                val ended = player.playbackState == Player.STATE_ENDED
                val firstNew = player.mediaItemCount
                player.addMediaItems(nextItems)
                // A short last item can end before the next day is built; carry on into it then.
                if (ended) {
                    player.seekTo(firstNew, episodeStarts[nextItems.first().mediaId] ?: 0)
                    player.play()
                }
            }
        }
    }

    private fun isEpisode(item: MediaItem?) = item?.mediaMetadata?.mediaType == MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE

    /** Bible chapters and episodes each play at their own speed (spec 0003 §9.2, AD-2). */
    private fun applySpeed() {
        val target = if (isEpisode(player.currentMediaItem)) settings.podcastSpeed else settings.bibleSpeed
        if (player.playbackParameters.speed != target) player.setPlaybackSpeed(target)
    }

    private inner class PlayerListener : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            record(tracker.end(naturalEnd = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO))
            tracker.begin(mediaItem?.mediaId, mediaItem?.mediaMetadata?.durationMs ?: 0)
            // The queue moved on into a partly played episode: pick up where it stopped (spec 0003 §8.5).
            if (isEpisode(mediaItem) && player.currentPosition < 1_000 &&
                (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
            ) {
                episodeStarts[mediaItem!!.mediaId]?.takeIf { it > 0 }?.let { player.seekTo(it) }
            }
            applySpeed()
            maybeAppendNextDay(mediaItem)
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
            // The speed chip edits the setting for the current item's type.
            val speed = playbackParameters.speed
            if (isEpisode(player.currentMediaItem)) {
                if (speed != settings.podcastSpeed) scope.launch { settingsRepository.setPodcastSpeed(speed) }
            } else if (speed != settings.bibleSpeed) {
                scope.launch { settingsRepository.setBibleSpeed(speed) }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val id = player.currentMediaItem?.mediaId
            Log.e(TAG, "Playback error on $id", error)
            if (isEpisode(player.currentMediaItem)) {
                session.setPlaybackException(PlaybackException(getString(R.string.error_episode_failed), error, error.errorCode))
                return
            }
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
