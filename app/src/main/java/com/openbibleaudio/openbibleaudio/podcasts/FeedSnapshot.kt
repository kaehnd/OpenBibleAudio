package com.openbibleaudio.openbibleaudio.podcasts

import com.openbibleaudio.core.model.PodcastIds
import com.openbibleaudio.core.model.plan.PlanPodcast
import com.openbibleaudio.core.podcast.Assignment
import com.openbibleaudio.core.podcast.Cycle
import com.openbibleaudio.core.podcast.EpisodeMatcher
import com.openbibleaudio.core.podcast.FeedItem
import com.openbibleaudio.core.podcast.MatchSource
import com.openbibleaudio.openbibleaudio.data.db.PodcastEpisodeEntity

/**
 * The feed-owned columns of a `podcast_episode` row. A refresh writes only these, so it never
 * overwrites download state, position or completion written by the worker or the player.
 */
data class EpisodeMetadata(
    val feedId: String,
    val guidHash: String,
    val guid: String,
    val title: String,
    val pubDate: Long,
    val enclosureUrl: String,
    val enclosureLength: Long?,
    val durationMs: Long?,
    val episodeType: String?,
    val cycle: Int?,
    val matchedDay: Int?,
    val matchSource: MatchSource?,
    val inFeed: Boolean,
) {
    fun toNewEntity() = PodcastEpisodeEntity(
        feedId, guidHash, guid, title, pubDate, enclosureUrl, enclosureLength, durationMs,
        episodeType, cycle, matchedDay, matchSource, inFeed,
    )
}

/**
 * Turns a fetched feed plus the stored rows into the new snapshot (spec 0003 §7.3). Pure Kotlin.
 *
 * - Items in the feed are re-matched with the plan's rules (title, then plan overrides).
 * - Rows that have dropped out of the feed are kept with `inFeed = false` and their match as it
 *   was. That snapshot keeps an older cycle playable after it leaves the feed's item window.
 * - Then, per (cycle, day), one row wins: a plan override beats a title match, and otherwise the
 *   latest `pubDate` wins. The others lose their day and are listed for pinning (§7.4).
 */
object FeedSnapshot {
    fun merge(
        feedId: String,
        podcast: PlanPodcast,
        dayCount: Int,
        items: List<FeedItem>,
        stored: List<PodcastEpisodeEntity>,
    ): List<EpisodeMetadata> {
        val live = items.distinctBy { it.guid }
        val assigned = HashMap<String, Match>()
        for (cycle in EpisodeMatcher.catalog(live, podcast, dayCount)) {
            for ((day, a) in cycle.byDay) assigned[a.item.guid] = Match(cycle.key, day, a.source)
        }
        val storedByGuid = stored.associateBy { it.guid }
        val liveGuids = HashSet<String>()

        val rows = ArrayList<EpisodeMetadata>(live.size + stored.size)
        for (item in live) {
            liveGuids += item.guid
            val old = storedByGuid[item.guid]
            val match = assigned[item.guid]
            rows += EpisodeMetadata(
                feedId = feedId,
                guidHash = PodcastIds.guidHash(item.guid),
                guid = item.guid,
                title = item.title,
                pubDate = item.pubDateMs ?: old?.pubDate ?: 0L,
                enclosureUrl = item.enclosureUrl,
                enclosureLength = item.enclosureLength,
                durationMs = item.durationMs ?: old?.durationMs,
                episodeType = item.episodeType,
                cycle = match?.cycle ?: cycleOf(item, podcast),
                matchedDay = match?.day,
                matchSource = match?.source,
                inFeed = true,
            )
        }
        for (old in stored) {
            if (old.guid in liveGuids) continue
            rows += EpisodeMetadata(
                old.feedId, old.guidHash, old.guid, old.title, old.pubDate, old.enclosureUrl, old.enclosureLength,
                old.durationMs, old.episodeType, old.cycle, old.matchedDay, old.matchSource, inFeed = false,
            )
        }
        return resolveConflicts(rows)
    }

    /** The cycles stored for a feed, newest key first, for the enroll dialog and defaults (§7.2). */
    fun cycles(podcast: PlanPodcast, dayCount: Int, rows: List<PodcastEpisodeEntity>): List<Cycle> {
        val matched = rows.filter { it.matchedDay != null && it.matchedDay in 1..dayCount }
        val noCycleBlock = podcast.cycle == null
        return matched.groupBy { if (noCycleBlock) null else it.cycle }
            .filterKeys { noCycleBlock || it != null }
            .map { (key, cycleRows) ->
                val byDay = cycleRows.groupBy { it.matchedDay!! }
                    .mapValues { (_, r) -> r.maxBy { it.pubDate }.let { Assignment(it.toFeedItem(), it.matchSource ?: MatchSource.TITLE) } }
                Cycle(key, dayCount, byDay, duplicates = emptyMap())
            }
            .sortedByDescending { it.key ?: Int.MAX_VALUE }
    }

    fun PodcastEpisodeEntity.toFeedItem() = FeedItem(
        guid = guid, title = title, pubDateMs = pubDate, enclosureUrl = enclosureUrl, enclosureLength = enclosureLength,
        enclosureType = null, durationMs = durationMs, episodeType = episodeType, season = cycle, episode = matchedDay,
    )

    private data class Match(val cycle: Int?, val day: Int, val source: MatchSource)

    private fun cycleOf(item: FeedItem, podcast: PlanPodcast): Int? =
        if (podcast.cycle == null) null else EpisodeMatcher.cycleOf(item, podcast)

    private fun resolveConflicts(rows: List<EpisodeMetadata>): List<EpisodeMetadata> {
        val winners = rows.filter { it.matchedDay != null }
            .groupBy { it.cycle to it.matchedDay }
            .values
            .associate { claim ->
                val best = claim.sortedWith(
                    compareByDescending<EpisodeMetadata> { it.matchSource == MatchSource.PLAN_OVERRIDE }
                        .thenByDescending { it.pubDate }
                        .thenBy { it.guid },
                ).first()
                (best.cycle to best.matchedDay) to best.guid
            }
        return rows.map { r ->
            if (r.matchedDay == null || winners[r.cycle to r.matchedDay] == r.guid) r
            else r.copy(matchedDay = null, matchSource = null)
        }
    }
}
