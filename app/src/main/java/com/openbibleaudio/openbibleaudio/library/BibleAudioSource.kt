package com.openbibleaudio.openbibleaudio.library

import android.net.Uri
import com.openbibleaudio.core.model.ChapterRef
import com.openbibleaudio.openbibleaudio.data.BibleStorage
import com.openbibleaudio.openbibleaudio.data.LibraryRepository

/** Where a chapter's audio is (AD-10, spec 0002 §7.1). Streaming sources come later. */
interface BibleAudioSource {
    val requiresNetwork: Boolean

    suspend fun resolve(versionId: String, ref: ChapterRef): ResolvedChapter?

    /** Resolves many chapters at once (whole queues); chapters that can't be resolved are left out. */
    suspend fun resolveAll(versionId: String, refs: List<ChapterRef>): Map<ChapterRef, ResolvedChapter>
}

data class ResolvedChapter(val uri: Uri, val clipStartMs: Long, val clipEndMs: Long?, val durationMs: Long?, val exists: Boolean)

/** Imported files in app storage (AD-6). Null if the version isn't READY or lacks the chapter. */
class LocalBibleAudioSource(
    private val library: LibraryRepository,
    private val storage: BibleStorage,
) : BibleAudioSource {
    override val requiresNetwork = false

    override suspend fun resolve(versionId: String, ref: ChapterRef): ResolvedChapter? {
        val source = library.chapterSource(versionId, ref) ?: return null
        val file = storage.file(versionId, source.path)
        return ResolvedChapter(Uri.fromFile(file), source.startMs, source.endMs, source.durationMs, file.isFile)
    }

    override suspend fun resolveAll(versionId: String, refs: List<ChapterRef>): Map<ChapterRef, ResolvedChapter> {
        val sources = library.chapterSources(versionId)
        return refs.mapNotNull { ref ->
            sources[ref]?.let { s ->
                val file = storage.file(versionId, s.path)
                // Existence is checked lazily for queue items; only the start item is checked eagerly.
                ref to ResolvedChapter(Uri.fromFile(file), s.startMs, s.endMs, s.durationMs, exists = true)
            }
        }.toMap()
    }
}
