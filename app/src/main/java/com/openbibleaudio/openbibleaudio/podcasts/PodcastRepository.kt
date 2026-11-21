package com.openbibleaudio.openbibleaudio.podcasts

import com.openbibleaudio.core.model.PodcastIds
import com.openbibleaudio.core.model.plan.PlanDefinition
import com.openbibleaudio.core.model.plan.PlanPodcast
import com.openbibleaudio.core.podcast.Cycle
import com.openbibleaudio.core.podcast.EpisodeMatcher
import com.openbibleaudio.openbibleaudio.data.db.EnrollmentEntity
import com.openbibleaudio.openbibleaudio.data.db.PlanDao
import com.openbibleaudio.openbibleaudio.data.db.PodcastDao
import com.openbibleaudio.openbibleaudio.data.db.PodcastEpisodeEntity
import com.openbibleaudio.openbibleaudio.data.db.PodcastFeedEntity
import com.openbibleaudio.openbibleaudio.plans.PlanRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration

sealed interface FeedRefreshResult {
    /** The feed was fetched and [episodes] rows now exist for it. */
    data class Updated(val episodes: Int) : FeedRefreshResult

    data object NotModified : FeedRefreshResult

    /** Fetched recently enough that nothing was sent (spec 0003 §7.1: 12 h). */
    data object Fresh : FeedRefreshResult

    data class Failed(val message: String, val retryable: Boolean) : FeedRefreshResult
}

/** A plan's podcast feed: refresh, snapshot and cycles (spec 0003 §7). */
class PodcastRepository(
    private val dao: PodcastDao,
    private val planDao: PlanDao,
    private val plans: PlanRepository,
    private val fetcher: FeedFetcher,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    // Refreshes are rare and short; one at a time keeps the screen, enroll and worker paths simple.
    private val refreshLock = Mutex()

    fun observeFeed(podcast: PlanPodcast): Flow<PodcastFeedEntity?> = dao.observeFeed(feedId(podcast))

    /**
     * Fetches [plan]'s feed unless it was fetched within [maxAge], and stores the snapshot.
     * Matching uses this plan's rules. Feeds are keyed by URL, so plans sharing a feed share rows,
     * and the most recent refresh's plan decides the matches.
     */
    suspend fun refresh(plan: PlanDefinition, maxAge: Duration = STALE_AFTER): FeedRefreshResult {
        val podcast = plan.podcast ?: return FeedRefreshResult.Failed("This plan has no podcast", retryable = false)
        val feedId = feedId(podcast)
        return refreshLock.withLock {
            val stored = dao.feed(feedId)
            val now = clock.millis()
            val last = stored?.lastFetchedAt
            if (stored != null && stored.url == podcast.feedUrl && stored.lastError == null && last != null &&
                now - last in 0 until maxAge.toMillis()
            ) {
                return@withLock FeedRefreshResult.Fresh
            }
            // Validators belong to one URL; don't send them for a different one.
            val sameUrl = stored?.url == podcast.feedUrl
            val base = stored?.takeIf { sameUrl } ?: PodcastFeedEntity(feedId, podcast.feedUrl, null, null, null, null, null)
            when (val r = fetcher.fetch(podcast.feedUrl, base.etag, base.lastModified)) {
                is FeedFetchResult.Fetched -> {
                    val rows = FeedSnapshot.merge(feedId, podcast, plan.dayCount, r.feed.items, dao.episodes(feedId))
                    dao.applyRefresh(
                        base.copy(title = r.feed.title ?: base.title, etag = r.etag, lastModified = r.lastModified, lastFetchedAt = now, lastError = null),
                        rows,
                    )
                    FeedRefreshResult.Updated(rows.size)
                }
                FeedFetchResult.NotModified -> {
                    dao.upsertFeed(base.copy(lastFetchedAt = now, lastError = null))
                    FeedRefreshResult.NotModified
                }
                is FeedFetchResult.Failed -> {
                    // Keep the old snapshot and validators; only record the error.
                    dao.upsertFeed(base.copy(lastError = r.message))
                    FeedRefreshResult.Failed(r.message, r.retryable)
                }
            }
        }
    }

    /**
     * Refreshes the feed of every active enrollment's plan. Returns false if any refresh failed
     * in a way worth retrying soon.
     */
    suspend fun refreshActive(maxAge: Duration = STALE_AFTER): Boolean {
        var ok = true
        for (plan in plans.activePodcastPlans()) {
            val r = refresh(plan, maxAge)
            if (r is FeedRefreshResult.Failed && r.retryable) ok = false
        }
        assignMissingCycles()
        return ok
    }

    /**
     * An enrollment made while the feed was unreachable has no cycle yet. It gets the plan's
     * default as soon as one exists. A chosen cycle is never changed (spec 0003 §7.2).
     */
    private suspend fun assignMissingCycles() {
        for (e in planDao.activeEnrollments()) {
            if (e.podcastCycle != null) continue
            val plan = plans.plan(e.planId, e.planVersion) ?: continue
            if (plan.podcast?.cycle == null) continue
            val key = defaultCycle(plan, cycles(plan))?.key ?: continue
            planDao.updateEnrollment(e.copy(podcastCycle = key))
        }
    }

    /** The stored cycles for [plan], newest key first (spec 0003 §7.2). Empty before the first refresh. */
    suspend fun cycles(plan: PlanDefinition): List<Cycle> {
        val podcast = plan.podcast ?: return emptyList()
        return FeedSnapshot.cycles(podcast, plan.dayCount, dao.episodes(feedId(podcast)))
    }

    /** The cycle a new enrollment defaults to: the plan's `select` rule over the stored cycles. */
    fun defaultCycle(plan: PlanDefinition, cycles: List<Cycle>): Cycle? =
        plan.podcast?.let { EpisodeMatcher.select(cycles, it) }

    /**
     * The episode for [day] of [enrollment]: its manual pin if it has one, otherwise the row
     * matched to that day in the enrollment's cycle (spec 0003 §7.3 precedence).
     */
    suspend fun episodeForDay(enrollment: EnrollmentEntity, plan: PlanDefinition, day: Int): PodcastEpisodeEntity? {
        val podcast = plan.podcast ?: return null
        val feedId = feedId(podcast)
        planDao.pins(enrollment.id).firstOrNull { it.day == day && it.feedId == feedId }
            ?.let { pin -> dao.episode(feedId, pin.guidHash)?.let { return it } }
        val cycle = if (podcast.cycle == null) null else enrollment.podcastCycle ?: return null
        return dao.episodeForDay(feedId, cycle, day)
    }

    companion object {
        val STALE_AFTER: Duration = Duration.ofHours(12)

        fun feedId(podcast: PlanPodcast): String = PodcastIds.feedId(podcast.feedUrl)
    }
}
