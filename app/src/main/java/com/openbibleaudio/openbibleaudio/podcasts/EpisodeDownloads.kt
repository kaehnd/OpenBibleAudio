package com.openbibleaudio.openbibleaudio.podcasts

import com.openbibleaudio.core.model.plan.PlanDefinition
import com.openbibleaudio.openbibleaudio.data.SettingsRepository
import com.openbibleaudio.openbibleaudio.data.db.DownloadMode
import com.openbibleaudio.openbibleaudio.data.db.DownloadState
import com.openbibleaudio.openbibleaudio.data.db.EnrollmentEntity
import com.openbibleaudio.openbibleaudio.data.db.PlanDao
import com.openbibleaudio.openbibleaudio.data.db.PodcastDao
import com.openbibleaudio.openbibleaudio.data.db.PodcastEpisodeEntity
import com.openbibleaudio.openbibleaudio.plans.PlanRepository
import java.time.Clock

/**
 * Glue between enrollments and the download worker (spec 0003 §8): what to download for a
 * feed, when to start the worker, and retention. [schedule] starts (or appends to) the feed's
 * unique download work. It's a function so tests can capture it without WorkManager.
 */
class EpisodeDownloads(
    private val plans: PlanRepository,
    private val planDao: PlanDao,
    private val podcastDao: PodcastDao,
    private val storage: PodcastStorage,
    private val settings: SettingsRepository,
    private val schedule: (feedId: String, unmeteredOnly: Boolean) -> Unit,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    /** The feed's download queue right now, from every ACTIVE enrollment that uses it. */
    suspend fun queue(feedId: String): List<PodcastEpisodeEntity> =
        DownloadPlanner.queue(enrollmentEpisodes(planDao.activeEnrollments(), feedId))

    /** Starts the download worker for every feed that an ACTIVE enrollment downloads from. */
    suspend fun startAll() {
        val unmeteredOnly = settings.current().podcastUnmeteredOnly
        val feeds = planDao.activeEnrollments()
            .filter { it.downloadMode != DownloadMode.OFF }
            .mapNotNull { e -> plans.plan(e.planId, e.planVersion)?.podcast?.let(PodcastRepository::feedId) }
            .toSet()
        feeds.forEach { schedule(it, unmeteredOnly) }
    }

    /** Starts the worker for [enrollmentId]'s feed, e.g. after enrolling or when a day completes. */
    suspend fun start(enrollmentId: Long) {
        val e = planDao.enrollment(enrollmentId) ?: return
        val podcast = plans.plan(e.planId, e.planVersion)?.podcast ?: return
        schedule(PodcastRepository.feedId(podcast), settings.current().podcastUnmeteredOnly)
    }

    suspend fun setMode(enrollmentId: Long, mode: DownloadMode, rollingDays: Int = EnrollmentEntity.DEFAULT_ROLLING_DAYS) {
        planDao.setDownloadMode(enrollmentId, mode, rollingDays.coerceAtLeast(1))
        // Switching to a smaller mode deletes nothing; retention tidies up (spec 0003 §8.1).
        if (mode != DownloadMode.OFF) start(enrollmentId)
    }

    /**
     * Deletes files that are no longer needed (spec 0003 §8.4). Rows stay, with their position and
     * completion, as `downloadState = NONE`. Returns how many files were deleted.
     */
    suspend fun applyRetention(): Int {
        val s = settings.current()
        val downloaded = podcastDao.episodesIn(DownloadState.DOWNLOADED)
        if (downloaded.isEmpty()) return 0
        val live = planDao.liveEnrollments()
        val refs = downloaded.map { it.feedId }.distinct().flatMap { enrollmentEpisodes(live, it) }
        val doomed = DownloadPlanner.deletable(downloaded, refs, clock.millis(), s.podcastRetentionDays, s.keepPlayedEpisodes)
        for (ep in doomed) {
            storage.delete(ep.feedId, ep.guidHash)
            podcastDao.setDownload(ep.feedId, ep.guidHash, DownloadState.NONE, null, null, null)
        }
        return doomed.size
    }

    private suspend fun enrollmentEpisodes(enrollments: List<EnrollmentEntity>, feedId: String): List<EnrollmentEpisodes> {
        var feedRows: List<PodcastEpisodeEntity>? = null
        val out = mutableListOf<EnrollmentEpisodes>()
        for (e in enrollments) {
            val plan = plans.plan(e.planId, e.planVersion) ?: continue
            val podcast = plan.podcast ?: continue
            if (PodcastRepository.feedId(podcast) != feedId) continue
            val summary = plans.summary(e.id) ?: continue
            val all = feedRows ?: podcastDao.episodes(feedId).also { feedRows = it }
            out += EnrollmentEpisodes(
                enrollmentId = e.id,
                mode = e.downloadMode,
                rollingDays = e.rollingDays,
                currentDay = summary.position.currentDay,
                dayCount = plan.dayCount,
                days = planDao.days(e.id).associate { it.day to DayState(it.status, it.completedAt) },
                episodes = episodesByDay(e, plan, all),
            )
        }
        return out
    }

    /** Day → episode for [e]: manual pins first, then the cycle's matches (spec 0003 §7.3). */
    private suspend fun episodesByDay(e: EnrollmentEntity, plan: PlanDefinition, all: List<PodcastEpisodeEntity>): Map<Int, PodcastEpisodeEntity> {
        val cycle = if (plan.podcast?.cycle == null) null else e.podcastCycle ?: return emptyMap()
        val byDay = all.filter { it.cycle == cycle && it.matchedDay != null }
            .groupBy { it.matchedDay!! }
            .mapValues { (_, r) -> r.maxBy { it.pubDate } }
            .toMutableMap()
        val byHash = all.associateBy { it.guidHash }
        for (pin in planDao.pins(e.id)) byHash[pin.guidHash]?.let { byDay[pin.day] = it }
        return byDay
    }
}
