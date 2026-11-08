package com.openbibleaudio.openbibleaudio.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.openbibleaudio.core.model.Book
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {
    @Query("SELECT * FROM bible_version ORDER BY name")
    fun observeVersions(): Flow<List<BibleVersionEntity>>

    @Query("SELECT * FROM bible_version WHERE versionId = :versionId")
    suspend fun version(versionId: String): BibleVersionEntity?

    @Upsert
    suspend fun upsertVersion(version: BibleVersionEntity)

    @Query("UPDATE bible_version SET state = :state, failureReason = :reason WHERE versionId = :versionId")
    suspend fun setState(versionId: String, state: VersionState, reason: String?)

    @Query("DELETE FROM bible_version WHERE versionId = :versionId")
    suspend fun deleteVersion(versionId: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFiles(files: List<AudioFileEntity>)

    @Query("SELECT * FROM audio_file WHERE versionId = :versionId ORDER BY id")
    suspend fun files(versionId: String): List<AudioFileEntity>

    @Query("UPDATE audio_file SET imported = 1, durationMs = :durationMs WHERE id = :fileId")
    suspend fun markImported(fileId: Long, durationMs: Long?)

    @Insert
    suspend fun insertChapters(chapters: List<ChapterAudioEntity>)

    @Query(
        "UPDATE bible_version SET state = 'READY', importedAt = :importedAt, sourceTreeUri = NULL, " +
            "failureReason = NULL WHERE versionId = :versionId"
    )
    suspend fun markReady(versionId: String, importedAt: Long)

    /** Chapters become visible only when the whole import finishes (spec 0002 §5.1). */
    @Transaction
    suspend fun finishImport(versionId: String, chapters: List<ChapterAudioEntity>, importedAt: Long) {
        insertChapters(chapters)
        markReady(versionId, importedAt)
    }

    @Query(
        "SELECT c.versionId, c.book, c.chapter FROM chapter_audio c " +
            "JOIN bible_version v ON v.versionId = c.versionId WHERE v.state = 'READY'"
    )
    fun observeReadyChapters(): Flow<List<VersionChapter>>

    @Query(
        "SELECT f.path, c.startMs, c.endMs, c.durationMs FROM chapter_audio c " +
            "JOIN audio_file f ON f.id = c.fileId " +
            "JOIN bible_version v ON v.versionId = c.versionId " +
            "WHERE c.versionId = :versionId AND c.book = :book AND c.chapter = :chapter AND v.state = 'READY'"
    )
    suspend fun chapterSource(versionId: String, book: Book, chapter: Int): ChapterSource?

    @Query(
        "SELECT c.book, c.chapter, f.path, c.startMs, c.endMs, c.durationMs FROM chapter_audio c " +
            "JOIN audio_file f ON f.id = c.fileId " +
            "JOIN bible_version v ON v.versionId = c.versionId " +
            "WHERE c.versionId = :versionId AND v.state = 'READY'"
    )
    suspend fun chapterSources(versionId: String): List<ChapterSourceRow>

    @Query(
        "SELECT v.versionId, " +
            "(SELECT COUNT(*) FROM chapter_audio c WHERE c.versionId = v.versionId) AS chapters, " +
            "(SELECT COALESCE(SUM(f.sizeBytes), 0) FROM audio_file f WHERE f.versionId = v.versionId AND f.imported = 1) AS bytesOnDisk, " +
            "(SELECT COUNT(*) FROM chapter_completion k WHERE k.versionId = v.versionId) AS completions " +
            "FROM bible_version v"
    )
    fun observeStats(): Flow<List<VersionStats>>
}

@Dao
interface CompletionDao {
    @Insert
    suspend fun insert(completion: ChapterCompletionEntity)

    @Query("SELECT COUNT(*) FROM chapter_completion WHERE versionId = :versionId")
    suspend fun count(versionId: String): Int
}
