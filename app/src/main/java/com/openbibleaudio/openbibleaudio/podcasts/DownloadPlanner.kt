package com.openbibleaudio.openbibleaudio.podcasts

import com.openbibleaudio.openbibleaudio.data.db.DownloadMode
import com.openbibleaudio.openbibleaudio.data.db.DownloadState
import com.openbibleaudio.openbibleaudio.data.db.PodcastEpisodeEntity
import com.openbibleaudio.openbibleaudio.plans.DayStatus

/** What the planner needs to know about one enrollment of a podcast plan. */
data class EnrollmentEpisodes(
    val enrollmentId: Long,
    val mode: DownloadMode,
    val rollingDays: Int,
    /** Null when the enrollment is finished. */
    val currentDay: Int?,
    val dayCount: Int,
    val days: Map<Int, DayState>,
    /** The episode each day plays (manual pin, else the cycle's match). Days without one are absent. */
    val episodes: Map<Int, PodcastEpisodeEntity>,
)

data class DayState(val status: DayStatus, val completedAt: Long?)

/**
 * Which episodes to download and which files to delete (spec 0003 §8.1, §8.4). Pure Kotlin, so
 * the rules are unit-tested without WorkManager or files.
 */
object DownloadPlanner {

    /** Days whose episode the enrollment wants downloaded, in download order. */
    fun wantedDays(e: EnrollmentEpisodes): List<Int> {
        val current = e.currentDay ?: return emptyList()
        val open = { day: Int -> e.days[day]?.status.let { it == null || it == DayStatus.PENDING || it == DayStatus.IN_PROGRESS } }
        return when (e.mode) {
            DownloadMode.OFF -> emptyList()
            // From the current day to the end, then the earlier days.
            DownloadMode.ALL -> ((current..e.dayCount) + (1 until current)).filter(open)
            DownloadMode.ROLLING -> (current..e.dayCount).filter(open).take(e.rollingDays.coerceAtLeast(1))
        }
    }

    /**
     * The download queue for one feed: episodes the listener asked for ([requested], e.g. a day's
     * "Download"), then every enrollment's wanted episodes, interleaved so each enrollment's next
     * day comes early. No duplicates. Downloaded episodes and ones that failed for good
     * ([DownloadState.FAILED]) are left out.
     */
    fun queue(enrollments: List<EnrollmentEpisodes>, requested: List<PodcastEpisodeEntity> = emptyList()): List<PodcastEpisodeEntity> {
        val ranked = enrollments.flatMap { e -> wantedDays(e).mapIndexedNotNull { rank, day -> e.episodes[day]?.let { rank to it } } }
        return (requested + ranked.sortedBy { it.first }.map { it.second })
            .distinctBy { it.guidHash }
            .filter { it.downloadState != DownloadState.DOWNLOADED && it.downloadState != DownloadState.FAILED }
    }

    /**
     * Downloaded episodes whose files can go (spec 0003 §8.4). An episode is kept while any
     * enrollment that references it still has its day open, or finished it less than
     * [retentionDays] ago. An episode no enrollment references any more goes straight away.
     * [keepPlayed] keeps every referenced episode.
     */
    fun deletable(
        downloaded: List<PodcastEpisodeEntity>,
        enrollments: List<EnrollmentEpisodes>,
        nowMs: Long,
        retentionDays: Int,
        keepPlayed: Boolean,
    ): List<PodcastEpisodeEntity> {
        val cutoff = nowMs - retentionDays.coerceAtLeast(0) * DAY_MS
        val refs = HashMap<String, MutableList<DayState?>>() // guidHash → state of each referencing day
        for (e in enrollments) for ((day, ep) in e.episodes) refs.getOrPut(ep.guidHash) { mutableListOf() } += e.days[day]
        return downloaded.filter { ep ->
            val states = refs[ep.guidHash] ?: return@filter true
            !keepPlayed && states.all { s ->
                when (s?.status) {
                    DayStatus.SKIPPED -> true
                    DayStatus.COMPLETE -> (s.completedAt ?: 0L) <= cutoff
                    else -> false
                }
            }
        }
    }

    /**
     * Bytes for downloading episodes with these `enclosureLength`s. A missing length counts as the
     * average of the others, so a feed with gaps still gives a sensible estimate.
     */
    fun estimateBytes(lengths: Collection<Long?>): Long {
        val known = lengths.mapNotNull { it?.takeIf { n -> n > 0 } }
        if (known.isEmpty()) return 0
        val average = known.sum() / known.size
        return known.sum() + (lengths.size - known.size) * average
    }

    /** The enroll dialog's preselection: ALL when it fits with 1 GB to spare, else ROLLING (spec 0003 §8.1). */
    fun defaultMode(totalBytes: Long, freeBytes: Long): DownloadMode =
        if (totalBytes > 0 && freeBytes >= totalBytes + ONE_GB) DownloadMode.ALL else DownloadMode.ROLLING

    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val ONE_GB = 1L shl 30
}
