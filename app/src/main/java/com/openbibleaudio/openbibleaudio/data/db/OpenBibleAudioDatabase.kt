package com.openbibleaudio.openbibleaudio.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.openbibleaudio.core.model.Book

/**
 * Schema v1 (spec 0002 §5.1), exported to `app/schemas/`. From the first release onward every
 * change needs a Migration plus a test; there's no destructive fallback (AD-7).
 */
@Database(
    entities = [BibleVersionEntity::class, AudioFileEntity::class, ChapterAudioEntity::class, ChapterCompletionEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class OpenBibleAudioDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
    abstract fun completionDao(): CompletionDao

    companion object {
        const val NAME = "openbibleaudio.db"
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
}
