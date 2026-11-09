package com.openbibleaudio.core.importer

import com.openbibleaudio.core.importer.probe.ProbeResult
import com.openbibleaudio.core.importer.profile.ChapterMatch
import com.openbibleaudio.core.model.pack.Issue
import com.openbibleaudio.core.model.pack.IssueCode
import com.openbibleaudio.core.model.pack.PackBible
import com.openbibleaudio.core.model.pack.PackChapter
import com.openbibleaudio.core.model.pack.PackFile
import com.openbibleaudio.core.model.pack.PackGenerator
import com.openbibleaudio.core.model.pack.PackManifest
import com.openbibleaudio.core.model.pack.PackManifestCodec

/** Values given on the command line that override a profile's [PackBible] defaults. */
data class BibleOverrides(
    val versionId: String? = null,
    val abbreviation: String? = null,
    val name: String? = null,
    val language: String? = null,
    val narrator: String? = null,
    val attribution: String? = null,
)

/** Assembles a [PackManifest] (spec 0001 §6.6). Output is deterministic. */
object ManifestBuilder {

    /** Merges [defaults] with [overrides]. Returns null and adds issues if a required field is missing. */
    fun bible(defaults: PackBible?, overrides: BibleOverrides, issues: MutableList<Issue>): PackBible? {
        fun required(field: String, value: String?): String {
            if (value.isNullOrBlank()) {
                issues += Issue(IssueCode.MISSING_BIBLE_FIELD, "no value for bible.$field (the profile has no default; pass it explicitly)", "bible.$field")
            }
            return value.orEmpty()
        }
        val versionId = required("versionId", overrides.versionId ?: defaults?.versionId)
        val abbreviation = required("abbreviation", overrides.abbreviation ?: defaults?.abbreviation)
        val name = required("name", overrides.name ?: defaults?.name)
        val language = required("language", overrides.language ?: defaults?.language)
        if (issues.any { it.code == IssueCode.MISSING_BIBLE_FIELD }) return null
        return PackBible(
            versionId = versionId,
            abbreviation = abbreviation,
            name = name,
            language = language,
            versification = defaults?.versification ?: "eng",
            narrator = overrides.narrator ?: defaults?.narrator,
            attribution = overrides.attribution ?: defaults?.attribution,
        )
    }

    /** Errors for (book, chapter) pairs matched by more than one file. */
    fun duplicateChapters(matches: List<ChapterMatch>): List<Issue> =
        matches.groupBy { it.book to it.chapter }
            .filterValues { it.size > 1 }
            .entries
            .sortedWith(compareBy({ it.key.first.ordinal }, { it.key.second }))
            .map { (key, dupes) ->
                Issue(
                    IssueCode.DUPLICATE_CHAPTER,
                    "${key.first.usfm} ${key.second} is matched by ${dupes.joinToString { "'${it.path}'" }}",
                    dupes.first().path,
                )
            }

    /**
     * Builds the manifest. Chapters are sorted canonically, files by first use in that order
     * (§4.5). [facts] supplies size, hash and duration per path.
     */
    fun build(bible: PackBible, generator: PackGenerator?, matches: List<ChapterMatch>, facts: Map<String, FileFacts>): PackManifest {
        val sorted = matches.sortedWith(compareBy({ it.book.ordinal }, { it.chapter }, { it.startMs }))
        val files = sorted.map { it.path }.distinct().map { path ->
            val f = facts.getValue(path)
            PackFile(
                path = path,
                sizeBytes = f.bytesRead,
                sha256 = f.sha256,
                durationMs = (f.probe as? ProbeResult.Ok)?.durationMs,
            )
        }
        val chapters = sorted.map { PackChapter(it.book, it.chapter, it.path, it.startMs, it.endMs) }
        return PackManifest(PackManifestCodec.SUPPORTED_FORMAT_VERSION, bible, generator, files, chapters)
    }
}
