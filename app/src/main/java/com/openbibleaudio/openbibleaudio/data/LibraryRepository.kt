package com.openbibleaudio.openbibleaudio.data

import com.openbibleaudio.core.model.ChapterRef
import com.openbibleaudio.core.model.MediaId
import com.openbibleaudio.openbibleaudio.data.db.BibleVersionEntity
import com.openbibleaudio.openbibleaudio.data.db.ChapterSource
import com.openbibleaudio.openbibleaudio.data.db.LibraryDao
import com.openbibleaudio.openbibleaudio.data.db.VersionState
import com.openbibleaudio.openbibleaudio.data.db.VersionStats
import com.openbibleaudio.openbibleaudio.playback.LibrarySnapshot
import com.openbibleaudio.openbibleaudio.playback.VersionInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import java.io.File
import java.util.TreeSet

/** Storage layout (AD-6): `filesDir/bibles/{versionId}/{path}`. */
class BibleStorage(private val filesDir: File) {
    val root: File get() = File(filesDir, "bibles")

    fun versionDir(versionId: String): File = File(root, versionId)

    fun file(versionId: String, path: String): File = File(versionDir(versionId), path)

    fun manifest(versionId: String): File = File(versionDir(versionId), "manifest.json")
}

class LibraryRepository(
    private val dao: LibraryDao,
    private val storage: BibleStorage,
    private val resumption: ResumptionStore,
) {
    val versions: Flow<List<BibleVersionEntity>> = dao.observeVersions()

    val stats: Flow<List<VersionStats>> = dao.observeStats()

    /** READY versions and their chapters; drives the browse tree (spec 0002 §7.4). */
    val snapshot: Flow<LibrarySnapshot> = combine(dao.observeVersions(), dao.observeReadyChapters()) { versions, chapters ->
        val ready = versions.filter { it.state == VersionState.READY }
        val byVersion = chapters.groupBy { it.versionId }
        LibrarySnapshot(
            versions = ready.map { VersionInfo(it.versionId, it.abbreviation, it.name) },
            chapters = ready.associate { v ->
                v.versionId to (byVersion[v.versionId].orEmpty().mapNotNullTo(TreeSet()) { ChapterRef.of(it.book, it.chapter) })
            },
        )
    }.distinctUntilChanged()

    suspend fun version(versionId: String): BibleVersionEntity? = dao.version(versionId)

    suspend fun chapterSource(versionId: String, ref: ChapterRef): ChapterSource? =
        dao.chapterSource(versionId, ref.book, ref.chapter)

    suspend fun chapterSources(versionId: String): Map<ChapterRef, ChapterSource> =
        dao.chapterSources(versionId).mapNotNull { r ->
            ChapterRef.of(r.book, r.chapter)?.let { it to ChapterSource(r.path, r.startMs, r.endMs, r.durationMs) }
        }.toMap()

    /** Deletes a version's rows and files, and forgets resumption state that points at it. */
    suspend fun delete(versionId: String) = withContext(Dispatchers.IO) {
        dao.deleteVersion(versionId)
        storage.versionDir(versionId).deleteRecursively()
        val saved = resumption.get()
        if (saved != null && (MediaId.parse(saved.mediaId) as? MediaId.Chapter)?.versionId == versionId) resumption.clear()
    }
}
