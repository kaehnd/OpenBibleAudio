package com.openbibleaudio.openbibleaudio.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.openbibleaudio.core.podcast.MatchSource
import com.openbibleaudio.openbibleaudio.plans.DayStatus

// Schema v2 tables for reading plans and companion podcasts (spec 0003 §5).

/** How an enrollment's episodes are fetched ahead of time (spec 0003 §8.1). */
enum class DownloadMode { ROLLING, ALL, OFF }

enum class EnrollmentState { ACTIVE, PAUSED, FINISHED, ARCHIVED }

enum class DownloadState { NONE, QUEUED, DOWNLOADING, DOWNLOADED, FAILED }

/** An imported plan file. An older version stays while enrollments still use it. */
@Entity(tableName = "plan", primaryKeys = ["id", "version"])
data class PlanEntity(
    val id: String,
    val version: Int,
    val name: String,
    /** The plan file as imported (PlanCodec JSON); decoded when a day is played. */
    val definitionJson: String,
    val importedAt: Long,
)

@Entity(
    tableName = "enrollment",
    foreignKeys = [ForeignKey(PlanEntity::class, ["id", "version"], ["planId", "planVersion"])],
    indices = [Index("planId", "planVersion")],
)
data class EnrollmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val planId: String,
    val planVersion: Int,
    /** Bible version; null means whichever version is the default at play time. */
    val versionId: String?,
    /** ISO local date. Calendar position = today − startDate + 1 (spec 0003 §6.3). */
    val startDate: String,
    /** Pinned podcast cycle key (e.g. 7); null when there's no podcast or no cycle block. */
    val podcastCycle: Int?,
    val downloadMode: DownloadMode,
    val rollingDays: Int = DEFAULT_ROLLING_DAYS,
    val state: EnrollmentState,
    val createdAt: Long,
) {
    companion object {
        const val DEFAULT_ROLLING_DAYS = 14
    }
}

@Entity(
    tableName = "enrollment_day",
    primaryKeys = ["enrollmentId", "day"],
    foreignKeys = [ForeignKey(EnrollmentEntity::class, ["id"], ["enrollmentId"], onDelete = ForeignKey.CASCADE)],
)
data class EnrollmentDayEntity(
    val enrollmentId: Long,
    val day: Int,
    val status: DayStatus,
    val completedAt: Long?,
)

/**
 * One finished item of a day. [itemKey] is `ch/GEN/1` or `ep/{guidHash}` ([com.openbibleaudio.openbibleaudio.plans.ItemKey]),
 * which stays stable when the queue index changes.
 */
@Entity(
    tableName = "enrollment_item_completion",
    primaryKeys = ["enrollmentId", "day", "itemKey"],
    foreignKeys = [ForeignKey(EnrollmentEntity::class, ["id"], ["enrollmentId"], onDelete = ForeignKey.CASCADE)],
)
data class EnrollmentItemCompletionEntity(
    val enrollmentId: Long,
    val day: Int,
    val itemKey: String,
    val completedAt: Long,
)

@Entity(tableName = "podcast_feed")
data class PodcastFeedEntity(
    @PrimaryKey val feedId: String,
    val url: String,
    val title: String?,
    val etag: String?,
    val lastModified: String?,
    val lastFetchedAt: Long?,
    val lastError: String?,
)

/**
 * A snapshotted feed item. Rows are per feed, not per enrollment, so enrollments on the same cycle
 * share files. A row is kept after the item drops out of the feed ([inFeed] = false).
 */
@Entity(
    tableName = "podcast_episode",
    primaryKeys = ["feedId", "guidHash"],
    foreignKeys = [ForeignKey(PodcastFeedEntity::class, ["feedId"], ["feedId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("feedId", "cycle", "matchedDay")],
)
data class PodcastEpisodeEntity(
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
    val downloadState: DownloadState = DownloadState.NONE,
    val localPath: String? = null,
    val downloadedBytes: Long? = null,
    val downloadError: String? = null,
    val positionMs: Long = 0,
    val lastPlayedAt: Long? = null,
    val completedAt: Long? = null,
)

/** A phone-side "use this episode for day N" choice (spec 0003 §7.4). */
@Entity(
    tableName = "manual_pin",
    primaryKeys = ["enrollmentId", "day"],
    foreignKeys = [ForeignKey(EnrollmentEntity::class, ["id"], ["enrollmentId"], onDelete = ForeignKey.CASCADE)],
)
data class ManualPinEntity(
    val enrollmentId: Long,
    val day: Int,
    val feedId: String,
    val guidHash: String,
)

/** Day status row joined for progress computation. */
data class DayStatusRow(val day: Int, val status: DayStatus)

data class EnrollmentDayStatusRow(val enrollmentId: Long, val day: Int, val status: DayStatus)
