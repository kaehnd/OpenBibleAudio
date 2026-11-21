package com.openbibleaudio.openbibleaudio.podcasts

import com.openbibleaudio.core.model.plan.PlanDefinition
import com.openbibleaudio.core.podcast.EpisodeMatcher
import com.openbibleaudio.openbibleaudio.data.db.ManualPinEntity
import com.openbibleaudio.openbibleaudio.data.db.PodcastEpisodeEntity
import com.openbibleaudio.openbibleaudio.podcasts.FeedSnapshot.toFeedItem

/** The episode a day plays, and whether the listener pinned it (spec 0003 §7.4). */
data class DayEpisode(val episode: PodcastEpisodeEntity, val pinned: Boolean)

/**
 * One enrollment's view of its feed (spec 0003 §7.3, §7.4): the episode for each day, the days
 * whose title matched more than one upload, and the candidates for pinning.
 */
data class EnrollmentEpisodeView(
    val byDay: Map<Int, DayEpisode>,
    /** Days with more than one matching upload in the cycle (the latest is used). */
    val duplicatedDays: Set<Int>,
    /** The cycle's episodes that no day uses, newest first: the first list in the pin dialog. */
    val unassigned: List<PodcastEpisodeEntity>,
    /** Every episode of the feed, newest first ("Show all"). */
    val all: List<PodcastEpisodeEntity>,
) {
    fun unmatchedDays(dayCount: Int): List<Int> = (1..dayCount).filterNot { it in byDay }

    companion object {
        val EMPTY = EnrollmentEpisodeView(emptyMap(), emptySet(), emptyList(), emptyList())
    }
}

/** Builds [EnrollmentEpisodeView]. Pure, and shared by the screens and the download planner. */
object DayEpisodes {
    /**
     * [cycle] is the enrollment's pinned cycle. For a plan with a cycle block and no cycle yet
     * (enrolled offline), no day has an episode.
     */
    fun view(plan: PlanDefinition, cycle: Int?, rows: List<PodcastEpisodeEntity>, pins: List<ManualPinEntity>): EnrollmentEpisodeView {
        val podcast = plan.podcast ?: return EnrollmentEpisodeView.EMPTY
        val all = rows.sortedByDescending { it.pubDate }
        if (podcast.cycle != null && cycle == null) return EnrollmentEpisodeView(emptyMap(), emptySet(), emptyList(), all)
        val inCycle = all.filter { it.cycle == cycle }

        val byDay = HashMap<Int, DayEpisode>()
        inCycle.filter { it.matchedDay != null && it.matchedDay in 1..plan.dayCount }
            .groupBy { it.matchedDay!! }
            .forEach { (day, r) -> byDay[day] = DayEpisode(r.maxBy { it.pubDate }, pinned = false) }
        val byHash = all.associateBy { it.guidHash }
        for (pin in pins) {
            if (pin.day !in 1..plan.dayCount) continue
            byHash[pin.guidHash]?.let { byDay[pin.day] = DayEpisode(it, pinned = true) }
        }

        // A duplicate lost its day in the snapshot (§7.3); its title still says which day it was for.
        val used = byDay.values.mapTo(HashSet()) { it.episode.guidHash }
        val unassigned = inCycle.filter { it.guidHash !in used }
        val duplicated = unassigned.mapNotNullTo(HashSet()) { EpisodeMatcher.dayOf(it.toFeedItem(), podcast, plan.dayCount) }
            .filterTo(HashSet()) { it in byDay }
        return EnrollmentEpisodeView(byDay, duplicated, unassigned, all)
    }
}
