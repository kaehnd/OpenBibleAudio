package com.openbibleaudio.openbibleaudio.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.openbibleaudio.openbibleaudio.plans.DayStatus
import com.openbibleaudio.openbibleaudio.podcasts.EpisodeMetadata
import kotlinx.coroutines.flow.Flow

@Dao
interface PlanDao {
    @Upsert
    suspend fun upsertPlan(plan: PlanEntity)

    @Query("SELECT * FROM plan WHERE id = :id AND version = :version")
    suspend fun plan(id: String, version: Int): PlanEntity?

    @Query("SELECT * FROM plan")
    fun observePlans(): Flow<List<PlanEntity>>

    @Query("SELECT version FROM plan WHERE id = :id")
    suspend fun planVersions(id: String): List<Int>

    /** The newest version of each imported plan. */
    @Query("SELECT * FROM plan p WHERE version = (SELECT MAX(version) FROM plan q WHERE q.id = p.id) ORDER BY name")
    fun observeLatestPlans(): Flow<List<PlanEntity>>

    /** Drops a plan version that no enrollment uses any more. Returns rows deleted. */
    @Query(
        "DELETE FROM plan WHERE id = :id AND version = :version AND NOT EXISTS " +
            "(SELECT 1 FROM enrollment e WHERE e.planId = :id AND e.planVersion = :version)"
    )
    suspend fun deletePlanIfUnused(id: String, version: Int): Int

    @Insert
    suspend fun insertEnrollment(enrollment: EnrollmentEntity): Long

    @Update
    suspend fun updateEnrollment(enrollment: EnrollmentEntity)

    @Query("DELETE FROM enrollment WHERE id = :id")
    suspend fun deleteEnrollment(id: Long)

    @Query("SELECT * FROM enrollment WHERE id = :id")
    suspend fun enrollment(id: Long): EnrollmentEntity?

    @Query("SELECT * FROM enrollment WHERE state != 'ARCHIVED' ORDER BY createdAt")
    fun observeEnrollments(): Flow<List<EnrollmentEntity>>

    @Query("SELECT * FROM enrollment WHERE state = 'ACTIVE' ORDER BY createdAt")
    suspend fun activeEnrollments(): List<EnrollmentEntity>

    /** Every enrollment that isn't archived (ACTIVE, PAUSED, FINISHED). */
    @Query("SELECT * FROM enrollment WHERE state != 'ARCHIVED' ORDER BY createdAt")
    suspend fun liveEnrollments(): List<EnrollmentEntity>

    @Query("SELECT * FROM enrollment_day WHERE enrollmentId = :enrollmentId")
    suspend fun days(enrollmentId: Long): List<EnrollmentDayEntity>

    @Query("UPDATE enrollment SET downloadMode = :mode, rollingDays = :rollingDays WHERE id = :id")
    suspend fun setDownloadMode(id: Long, mode: DownloadMode, rollingDays: Int)

    @Query("SELECT day, status FROM enrollment_day WHERE enrollmentId = :enrollmentId")
    suspend fun dayStatuses(enrollmentId: Long): List<DayStatusRow>

    @Query("SELECT day, status FROM enrollment_day WHERE enrollmentId = :enrollmentId")
    fun observeDayStatuses(enrollmentId: Long): Flow<List<DayStatusRow>>

    @Query("SELECT enrollmentId, day, status FROM enrollment_day")
    fun observeAllDayStatuses(): Flow<List<EnrollmentDayStatusRow>>

    @Query("UPDATE enrollment SET state = :state WHERE id = :id")
    suspend fun setEnrollmentState(id: Long, state: EnrollmentState)

    @Query("DELETE FROM enrollment_day WHERE enrollmentId = :enrollmentId")
    suspend fun clearDays(enrollmentId: Long)

    @Query("DELETE FROM enrollment_item_completion WHERE enrollmentId = :enrollmentId")
    suspend fun clearAllCompletions(enrollmentId: Long)

    /** "Restart plan": every day back to PENDING (spec 0003 §6.3). */
    @Transaction
    suspend fun restart(enrollmentId: Long) {
        clearAllCompletions(enrollmentId)
        clearDays(enrollmentId)
        setEnrollmentState(enrollmentId, EnrollmentState.ACTIVE)
    }

    @Query("SELECT * FROM enrollment_day WHERE enrollmentId = :enrollmentId AND day = :day")
    suspend fun day(enrollmentId: Long, day: Int): EnrollmentDayEntity?

    @Upsert
    suspend fun upsertDays(days: List<EnrollmentDayEntity>)

    /** Recording the same item twice keeps the first time (spec 0003 §6.3). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCompletion(completion: EnrollmentItemCompletionEntity)

    @Query("SELECT itemKey FROM enrollment_item_completion WHERE enrollmentId = :enrollmentId AND day = :day")
    suspend fun completedItems(enrollmentId: Long, day: Int): List<String>

    @Query("DELETE FROM enrollment_item_completion WHERE enrollmentId = :enrollmentId AND day = :day")
    suspend fun clearCompletions(enrollmentId: Long, day: Int)

    /** Resets a day to PENDING, forgetting its item completions ("Mark not done"). */
    @Transaction
    suspend fun resetDay(enrollmentId: Long, day: Int) {
        clearCompletions(enrollmentId, day)
        upsertDays(listOf(EnrollmentDayEntity(enrollmentId, day, DayStatus.PENDING, null)))
    }

    @Upsert
    suspend fun upsertPin(pin: ManualPinEntity)

    @Delete
    suspend fun deletePin(pin: ManualPinEntity)

    @Query("SELECT * FROM manual_pin WHERE enrollmentId = :enrollmentId")
    suspend fun pins(enrollmentId: Long): List<ManualPinEntity>
}

@Dao
interface PodcastDao {
    @Upsert
    suspend fun upsertFeed(feed: PodcastFeedEntity)

    @Query("SELECT * FROM podcast_feed WHERE feedId = :feedId")
    suspend fun feed(feedId: String): PodcastFeedEntity?

    @Query("SELECT * FROM podcast_episode WHERE feedId = :feedId ORDER BY pubDate DESC")
    suspend fun episodes(feedId: String): List<PodcastEpisodeEntity>

    @Query("SELECT * FROM podcast_episode WHERE feedId = :feedId AND guidHash = :guidHash")
    suspend fun episode(feedId: String, guidHash: String): PodcastEpisodeEntity?

    /** The episode matched to [day] of [cycle] (null cycle = a plan with no cycle block). */
    @Query(
        "SELECT * FROM podcast_episode WHERE feedId = :feedId AND cycle IS :cycle AND matchedDay = :day " +
            "ORDER BY pubDate DESC LIMIT 1"
    )
    suspend fun episodeForDay(feedId: String, cycle: Int?, day: Int): PodcastEpisodeEntity?

    @Query("SELECT * FROM podcast_episode WHERE feedId = :feedId AND cycle IS :cycle AND matchedDay IS NOT NULL")
    fun observeCycle(feedId: String, cycle: Int?): Flow<List<PodcastEpisodeEntity>>

    @Upsert
    suspend fun upsertEpisodes(episodes: List<PodcastEpisodeEntity>)

    @Query("SELECT * FROM podcast_feed WHERE feedId = :feedId")
    fun observeFeed(feedId: String): Flow<PodcastFeedEntity?>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEpisodes(episodes: List<PodcastEpisodeEntity>)

    /** Writes only the feed-owned columns, leaving download, position and completion alone. */
    @Update(entity = PodcastEpisodeEntity::class)
    suspend fun updateEpisodeMetadata(rows: List<EpisodeMetadata>)

    @Query("SELECT guidHash FROM podcast_episode WHERE feedId = :feedId")
    suspend fun guidHashes(feedId: String): List<String>

    /** One refresh as a unit (spec 0003 §7.3): new rows inserted, known rows updated in place. */
    @Transaction
    suspend fun applyRefresh(feed: PodcastFeedEntity, rows: List<EpisodeMetadata>) {
        upsertFeed(feed)
        val known = guidHashes(feed.feedId).toHashSet()
        val (existing, added) = rows.partition { it.guidHash in known }
        if (added.isNotEmpty()) insertEpisodes(added.map { it.toNewEntity() })
        if (existing.isNotEmpty()) updateEpisodeMetadata(existing)
    }

    @Query(
        "UPDATE podcast_episode SET downloadState = :state, localPath = :localPath, " +
            "downloadedBytes = :bytes, downloadError = :error WHERE feedId = :feedId AND guidHash = :guidHash"
    )
    suspend fun setDownload(feedId: String, guidHash: String, state: DownloadState, localPath: String?, bytes: Long?, error: String?)

    /** A finished download. [durationMs] (from the file) is kept only when the feed gave none. */
    @Query(
        "UPDATE podcast_episode SET downloadState = 'DOWNLOADED', localPath = :localPath, downloadedBytes = :bytes, " +
            "downloadError = NULL, durationMs = COALESCE(durationMs, :durationMs) WHERE feedId = :feedId AND guidHash = :guidHash"
    )
    suspend fun setDownloaded(feedId: String, guidHash: String, localPath: String, bytes: Long, durationMs: Long?)

    @Query("SELECT * FROM podcast_episode WHERE downloadState = :state")
    suspend fun episodesIn(state: DownloadState): List<PodcastEpisodeEntity>

    @Query("SELECT COALESCE(SUM(downloadedBytes), 0) FROM podcast_episode WHERE feedId = :feedId AND downloadState = 'DOWNLOADED'")
    fun observeDownloadedBytes(feedId: String): Flow<Long>

    @Query("UPDATE podcast_episode SET positionMs = :positionMs, lastPlayedAt = :at WHERE feedId = :feedId AND guidHash = :guidHash")
    suspend fun setPosition(feedId: String, guidHash: String, positionMs: Long, at: Long)

    @Query("UPDATE podcast_episode SET completedAt = :at, positionMs = 0 WHERE feedId = :feedId AND guidHash = :guidHash")
    suspend fun markPlayed(feedId: String, guidHash: String, at: Long)
}
