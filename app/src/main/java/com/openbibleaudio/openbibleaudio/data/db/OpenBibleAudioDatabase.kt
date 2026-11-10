package com.openbibleaudio.openbibleaudio.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.openbibleaudio.core.model.Book
import com.openbibleaudio.core.podcast.MatchSource
import com.openbibleaudio.openbibleaudio.plans.DayStatus

/**
 * Schema v2: v1 (spec 0002 §5.1) plus the plan and podcast tables (spec 0003 §5). Exported to
 * `app/schemas/`. Every change needs a Migration plus a test; there's no destructive fallback (AD-7).
 */
@Database(
    entities = [
        BibleVersionEntity::class, AudioFileEntity::class, ChapterAudioEntity::class, ChapterCompletionEntity::class,
        PlanEntity::class, EnrollmentEntity::class, EnrollmentDayEntity::class, EnrollmentItemCompletionEntity::class,
        PodcastFeedEntity::class, PodcastEpisodeEntity::class, ManualPinEntity::class,
    ],
    version = OpenBibleAudioDatabase.VERSION,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class OpenBibleAudioDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
    abstract fun completionDao(): CompletionDao
    abstract fun planDao(): PlanDao
    abstract fun podcastDao(): PodcastDao

    companion object {
        const val NAME = "openbibleaudio.db"
        const val VERSION = 2
    }
}

/** Books are stored as USFM codes (AD-11). */
class Converters {
    @TypeConverter
    fun bookToUsfm(book: Book): String = book.usfm

    @TypeConverter
    fun usfmToBook(code: String): Book = requireNotNull(Book.fromUsfm(code)) { "unknown book '$code'" }

    @TypeConverter
    fun stateToString(state: VersionState): String = state.name

    @TypeConverter
    fun stringToState(value: String): VersionState = VersionState.valueOf(value)

    @TypeConverter
    fun dayStatusToString(value: DayStatus): String = value.name

    @TypeConverter
    fun stringToDayStatus(value: String): DayStatus = DayStatus.valueOf(value)

    @TypeConverter
    fun downloadModeToString(value: DownloadMode): String = value.name

    @TypeConverter
    fun stringToDownloadMode(value: String): DownloadMode = DownloadMode.valueOf(value)

    @TypeConverter
    fun enrollmentStateToString(value: EnrollmentState): String = value.name

    @TypeConverter
    fun stringToEnrollmentState(value: String): EnrollmentState = EnrollmentState.valueOf(value)

    @TypeConverter
    fun downloadStateToString(value: DownloadState): String = value.name

    @TypeConverter
    fun stringToDownloadState(value: String): DownloadState = DownloadState.valueOf(value)

    @TypeConverter
    fun matchSourceToString(value: MatchSource?): String? = value?.name

    @TypeConverter
    fun stringToMatchSource(value: String?): MatchSource? = value?.let { MatchSource.valueOf(it) }
}
