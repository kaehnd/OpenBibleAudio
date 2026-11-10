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
import kotlinx.coroutines.flow.Flow

@Dao
interface PlanDao {
    @Upsert
    suspend fun upsertPlan(plan: PlanEntity)

    @Query("SELECT * FROM plan WHERE id = :id AND version = :version")
    suspend fun plan(id: String, version: Int): PlanEntity?

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

    @Query("SELECT day, status FROM enrollment_day WHERE enrollmentId = :enrollmentId")
    suspend fun dayStatuses(enrollmentId: Long): List<DayStatusRow>

    @Query("SELECT day, status FROM enrollment_day WHERE enrollmentId = :enrollmentId")
    fun observeDayStatuses(enrollmentId: Long): Flow<List<DayStatusRow>>

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

    @Query(
        "UPDATE podcast_episode SET downloadState = :state, localPath = :localPath, " +
            "downloadedBytes = :bytes, downloadError = :error WHERE feedId = :feedId AND guidHash = :guidHash"
    )
    suspend fun setDownload(feedId: String, guidHash: String, state: DownloadState, localPath: String?, bytes: Long?, error: String?)

    @Query("UPDATE podcast_episode SET positionMs = :positionMs, lastPlayedAt = :at WHERE feedId = :feedId AND guidHash = :guidHash")
    suspend fun setPosition(feedId: String, guidHash: String, positionMs: Long, at: Long)

    @Query("UPDATE podcast_episode SET completedAt = :at, positionMs = 0 WHERE feedId = :feedId AND guidHash = :guidHash")
    suspend fun markPlayed(feedId: String, guidHash: String, at: Long)
}
