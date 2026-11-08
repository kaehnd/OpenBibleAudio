package com.openbibleaudio.openbibleaudio.playback

import com.openbibleaudio.core.model.ChapterRef
import com.openbibleaudio.core.model.MediaId
import com.openbibleaudio.core.model.ReferenceParser

/** Resumption rewinds a little so the listener gets some context back (spec 0002 §7.6). */
object ResumptionPolicy {
    const val REWIND_MS = 2_000L

    fun startPosition(savedPositionMs: Long): Long = (savedPositionMs - REWIND_MS).coerceAtLeast(0)
}

/**
 * Decides when a chapter counts as completed (AD-2, spec 0002 §7.8): it ended naturally, or the
 * furthest position reached was at least 95 % of its duration. One record per play-through.
 */
class CompletionTracker {
    private var mediaId: String? = null
    private var furthestMs = 0L
    private var durationMs = 0L
    private var recorded = false

    /** Start tracking a new current item. */
    fun begin(mediaId: String?, durationMs: Long) {
        this.mediaId = mediaId
        this.durationMs = durationMs
        furthestMs = 0
        recorded = false
    }

    fun onPosition(positionMs: Long, durationMs: Long) {
        if (durationMs > 0) this.durationMs = durationMs
        if (positionMs > furthestMs) furthestMs = positionMs
    }

    /**
     * The current item stopped being current (or playback ended). Returns its media ID if it
     * should be recorded as completed, at most once per [begin].
     */
    fun end(naturalEnd: Boolean): String? {
        val id = mediaId ?: return null
        if (recorded) return null
        val done = naturalEnd || (durationMs > 0 && furthestMs * 100 >= durationMs * THRESHOLD_PERCENT)
        if (!done) return null
        recorded = true
        return id
    }

    companion object {
        const val THRESHOLD_PERCENT = 95
    }
}

/** What a voice query asks for (spec 0002 §7.7). */
sealed interface VoiceRequest {
    data class Play(val chapter: MediaId.Chapter) : VoiceRequest
    data object Resume : VoiceRequest
    data class NotFound(val query: String, val versionAbbreviation: String?) : VoiceRequest
}

object VoiceSearch {
    /**
     * [preferredVersion] is the version in the resumption state, if any; otherwise the first
     * READY version is used.
     */
    fun resolve(query: String, snap: LibrarySnapshot, preferredVersion: String?): VoiceRequest {
        if (query.isBlank()) return VoiceRequest.Resume
        val version = snap.version(preferredVersion ?: "") ?: snap.versions.firstOrNull()
            ?: return VoiceRequest.NotFound(query, null)
        val parsed = ReferenceParser.parse(query) ?: return VoiceRequest.NotFound(query, version.abbreviation)
        val available = snap.chapters(version.versionId)
        val ref = if (parsed.chapter == null) {
            QueueBuilder.firstChapter(parsed.book, available)
        } else {
            ChapterRef(parsed.book, parsed.chapter!!).takeIf { it in available }
        } ?: return VoiceRequest.NotFound(query, version.abbreviation)
        return VoiceRequest.Play(MediaId.Chapter(version.versionId, ref))
    }

    /** Up to 10 results for onGetSearchResult: the match, plus the book's first chapter for book-only queries. */
    fun results(query: String, snap: LibrarySnapshot, preferredVersion: String?): List<MediaId.Chapter> {
        val request = resolve(query, snap, preferredVersion) as? VoiceRequest.Play ?: return emptyList()
        val parsed = ReferenceParser.parse(query) ?: return listOf(request.chapter)
        if (parsed.chapter != null) return listOf(request.chapter)
        return snap.chapters(request.chapter.versionId, parsed.book).take(10).map { MediaId.Chapter(request.chapter.versionId, it) }
    }
}
