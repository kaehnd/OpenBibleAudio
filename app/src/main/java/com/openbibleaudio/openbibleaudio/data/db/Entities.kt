package com.openbibleaudio.openbibleaudio.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.openbibleaudio.core.model.Book

/** Import lifecycle of a [BibleVersionEntity] (spec 0002 §5.1). */
enum class VersionState { IMPORTING, READY, FAILED }

@Entity(tableName = "bible_version")
data class BibleVersionEntity(
    @PrimaryKey val versionId: String,
    val abbreviation: String,
    val name: String,
    val language: String,
    val versification: String,
    val narrator: String?,
    val attribution: String?,
    val state: VersionState,
    val totalBytes: Long,
    /** Epoch ms; null until READY. */
    val importedAt: Long?,
    /** Persisted SAF grant (or file:// in tests) while IMPORTING; cleared on READY. */
    val sourceTreeUri: String?,
    val moveSource: Boolean,
    /** Why the last import attempt failed, shown on the Library card. */
    val failureReason: String? = null,
)

@Entity(
    tableName = "audio_file",
    foreignKeys = [ForeignKey(BibleVersionEntity::class, ["versionId"], ["versionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["versionId", "path"], unique = true)],
)
data class AudioFileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val versionId: String,
    val path: String,
    val sizeBytes: Long,
    val sha256: String?,
    val durationMs: Long?,
    /** A verified copy is in app storage. */
    val imported: Boolean,
)

@Entity(
    tableName = "chapter_audio",
    primaryKeys = ["versionId", "book", "chapter"],
    foreignKeys = [
        ForeignKey(BibleVersionEntity::class, ["versionId"], ["versionId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(AudioFileEntity::class, ["id"], ["fileId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("fileId")],
)
data class ChapterAudioEntity(
    val versionId: String,
    val book: Book,
    val chapter: Int,
    val fileId: Long,
    val startMs: Long,
    val endMs: Long?,
    /** Effective span duration. */
    val durationMs: Long?,
)

@Entity(tableName = "chapter_completion", indices = [Index("versionId", "book", "chapter")])
data class ChapterCompletionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val versionId: String,
    val book: Book,
    val chapter: Int,
    val completedAt: Long,
)

/** A chapter joined with its file, for playback (spec 0002 §7.1). */
data class ChapterSource(
    val path: String,
    val startMs: Long,
    val endMs: Long?,
    val durationMs: Long?,
)

/** [ChapterSource] with its chapter, for building whole queues in one query. */
data class ChapterSourceRow(
    val book: Book,
    val chapter: Int,
    val path: String,
    val startMs: Long,
    val endMs: Long?,
    val durationMs: Long?,
)

data class VersionChapter(val versionId: String, val book: Book, val chapter: Int)

data class VersionStats(val versionId: String, val chapters: Int, val bytesOnDisk: Long, val completions: Int)
