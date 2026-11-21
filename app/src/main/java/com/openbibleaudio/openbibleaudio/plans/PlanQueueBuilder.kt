package com.openbibleaudio.openbibleaudio.plans

import com.openbibleaudio.core.model.ChapterRef
import com.openbibleaudio.core.model.MediaId
import com.openbibleaudio.core.model.plan.PlanDefinition
import com.openbibleaudio.core.model.plan.PodcastPosition

/** Why a day's queue has no episode (spec 0003 §6.2). */
enum class EpisodeUnavailable { NO_PODCAST, NOT_MATCHED, NOT_DOWNLOADED, NO_LONGER_AVAILABLE }

/** What the queue builder knows about the day's episode. */
sealed interface EpisodeAvailability {
    /** Downloaded: plays from [uri] (a local file). */
    data class Local(val feedId: String, val guidHash: String, val uri: String) : EpisodeAvailability

    /** Not downloaded, but streaming is allowed and the network is up (spec 0003 §8.6). */
    data class Stream(val feedId: String, val guidHash: String, val url: String) : EpisodeAvailability

    data class Unavailable(val reason: EpisodeUnavailable) : EpisodeAvailability
}

sealed interface QueueEntry {
    val itemKey: String
    val mediaId: MediaId.PlanItem

    data class Chapter(override val mediaId: MediaId.PlanItem, val ref: ChapterRef) : QueueEntry {
        override val itemKey get() = ItemKey.chapter(ref)
    }

    data class Episode(
        override val mediaId: MediaId.PlanItem,
        val feedId: String,
        val guidHash: String,
        val uri: String,
        val streaming: Boolean,
    ) : QueueEntry {
        override val itemKey get() = ItemKey.episode(guidHash)
    }
}

data class DayQueue(
    val enrollmentId: Long,
    val day: Int,
    val entries: List<QueueEntry>,
    /** Chapters of the day that the Bible version doesn't have; dropped from [entries]. */
    val missingChapters: List<ChapterRef>,
    /** Null when the episode is in [entries] (or the plan has no podcast and this is expected). */
    val episodeUnavailable: EpisodeUnavailable?,
) {
    val readingKeys: List<String> get() = entries.filterIsInstance<QueueEntry.Chapter>().map { it.itemKey }
    val episodeKey: String? get() = entries.filterIsInstance<QueueEntry.Episode>().firstOrNull()?.itemKey
}

/** Builds one plan day's queue (spec 0003 §6.2). Pure. */
object PlanQueueBuilder {
    fun build(
        enrollmentId: Long,
        plan: PlanDefinition,
        day: Int,
        available: Set<ChapterRef>,
        episode: EpisodeAvailability,
    ): DayQueue? {
        val planDay = plan.day(day) ?: return null
        val chapters = planDay.chapters()
        val present = chapters.filter { it in available }
        val missing = chapters.filterNot { it in available }

        val episodeEntry: ((Int) -> QueueEntry.Episode)? = when (episode) {
            is EpisodeAvailability.Local -> { i -> QueueEntry.Episode(MediaId.PlanItem(enrollmentId, day, i), episode.feedId, episode.guidHash, episode.uri, streaming = false) }
            is EpisodeAvailability.Stream -> { i -> QueueEntry.Episode(MediaId.PlanItem(enrollmentId, day, i), episode.feedId, episode.guidHash, episode.url, streaming = true) }
            is EpisodeAvailability.Unavailable -> null
        }
        val before = plan.podcast?.position == PodcastPosition.BEFORE_READINGS
        val entries = buildList {
            if (before) episodeEntry?.let { add(it(size)) }
            for (ref in present) add(QueueEntry.Chapter(MediaId.PlanItem(enrollmentId, day, size), ref))
            if (!before) episodeEntry?.let { add(it(size)) }
        }
        val unavailable = when {
            plan.podcast == null -> null
            episode is EpisodeAvailability.Unavailable -> episode.reason
            else -> null
        }
        return DayQueue(enrollmentId, day, entries, missing, unavailable)
    }
}
