package com.openbibleaudio.openbibleaudio.library

import com.openbibleaudio.core.importer.BibleOverrides
import com.openbibleaudio.core.importer.ImportOptions
import com.openbibleaudio.core.importer.ImporterPipeline
import com.openbibleaudio.core.importer.LocalFileTree
import com.openbibleaudio.core.importer.fixtures.BsbNames
import com.openbibleaudio.core.importer.fixtures.Mp3Fixtures
import com.openbibleaudio.core.importer.probe.Mp3FrameProbe
import com.openbibleaudio.core.importer.profile.BsbGilbertProfile
import com.openbibleaudio.core.model.ChapterRef
import com.openbibleaudio.core.model.pack.PackManifestCodec
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * A tiny BSB-shaped pack: synthetic silent CBR MP3s (no Xing header) plus a manifest built by
 * the same pipeline the importer CLI uses (spec 0002 §10.3), so the CLI and app must agree.
 */
object MiniPack {
    /** ~40 frames ≈ 1 s of silence per chapter. [versionId] overrides the profile's `bsb-gilbert`. */
    fun create(dir: File, chapters: List<ChapterRef>, framesPerFile: Int = 40, versionId: String? = null): File {
        dir.mkdirs()
        chapters.forEachIndexed { i, ref ->
            File(dir, BsbNames.fileName(ref.book, ref.chapter)).writeBytes(Mp3Fixtures.cbr(framesPerFile + i))
        }
        val result = runBlocking {
            ImporterPipeline.run(LocalFileTree(dir.toPath()), BsbGilbertProfile.profile, ImportOptions(overrides = BibleOverrides(versionId = versionId), probes = listOf(Mp3FrameProbe)))
        }
        val manifest = requireNotNull(result.manifest) { "mini pack invalid: ${result.report.issues}" }
        File(dir, "manifest.json").writeText(PackManifestCodec.encode(manifest))
        return dir
    }
}
