package com.openbibleaudio.core.model.pack

import com.openbibleaudio.core.model.Book
import java.text.Normalizer

/**
 * Structural validation of a manifest (spec 0001 §4.9). It doesn't touch the file system;
 * checking a pack against real files is `PackVerifier` in `:core:importer`.
 */
object ManifestValidator {
    private val VERSION_ID = Regex("^[a-z0-9][a-z0-9-]{0,31}$")
    private val LANGUAGE = Regex("^[a-zA-Z]{2,3}(-[a-zA-Z0-9]{1,8})*$")
    private val SHA256 = Regex("^[0-9a-f]{64}$")
    const val VERSIFICATION_ENG = "eng"

    fun validate(m: PackManifest): ValidationReport {
        val issues = mutableListOf<Issue>()
        if (m.formatVersion != PackManifestCodec.SUPPORTED_FORMAT_VERSION) {
            issues += Issue(IssueCode.UNSUPPORTED_FORMAT, "formatVersion ${m.formatVersion} is not supported", "formatVersion")
        }
        validateBible(m.bible, issues)
        val durations = validateFiles(m.files, issues)
        validateChapters(m, durations, issues)
        return ValidationReport(issues)
    }

    private fun validateBible(b: PackBible, issues: MutableList<Issue>) {
        if (!VERSION_ID.matches(b.versionId)) {
            issues += Issue(IssueCode.BAD_VERSION_ID, "'${b.versionId}' must match ${VERSION_ID.pattern}", "bible.versionId")
        }
        if (!LANGUAGE.matches(b.language)) {
            issues += Issue(IssueCode.BAD_LANGUAGE, "'${b.language}' is not a BCP-47 language tag", "bible.language")
        }
        fun length(field: String, value: String?, range: IntRange) {
            if (value != null && value.length !in range) {
                issues += Issue(IssueCode.BAD_FIELD_LENGTH, "length ${value.length} not in ${range.first}..${range.last}", "bible.$field")
            }
        }
        length("abbreviation", b.abbreviation, 1..16)
        length("name", b.name, 1..100)
        length("attribution", b.attribution, 0..2000)
        if (b.versification != VERSIFICATION_ENG) {
            issues += Issue(IssueCode.UNSUPPORTED_VERSIFICATION, "'${b.versification}' (only '$VERSIFICATION_ENG' is defined)", "bible.versification")
        }
    }

    /** Returns path → durationMs (null if unknown) for files whose path is usable. */
    private fun validateFiles(files: List<PackFile>, issues: MutableList<Issue>): Map<String, Long?> {
        if (files.isEmpty()) issues += Issue(IssueCode.EMPTY_PACK, "files[] is empty", "files")
        val seen = HashMap<String, Int>()
        val durations = LinkedHashMap<String, Long?>()
        files.forEachIndexed { i, f ->
            val ptr = "files[$i]"
            pathProblem(f.path)?.let { issues += Issue(IssueCode.BAD_PATH, "'${f.path}': $it", ptr) }
            val folded = f.path.lowercase()
            val previous = seen.putIfAbsent(folded, i)
            if (previous != null) {
                issues += Issue(IssueCode.DUPLICATE_PATH, "'${f.path}' duplicates files[$previous] (paths compare case-insensitively)", ptr)
            }
            if (f.sizeBytes <= 0) issues += Issue(IssueCode.BAD_SIZE, "sizeBytes must be > 0", ptr)
            if (f.sha256 != null && !SHA256.matches(f.sha256)) {
                issues += Issue(IssueCode.BAD_HASH, "sha256 must be 64 lowercase hex characters", ptr)
            }
            if (f.durationMs != null && f.durationMs <= 0) issues += Issue(IssueCode.BAD_DURATION, "durationMs must be > 0", ptr)
            durations.putIfAbsent(f.path, f.durationMs?.takeIf { it > 0 })
        }
        return durations
    }

    private fun validateChapters(m: PackManifest, durations: Map<String, Long?>, issues: MutableList<Issue>) {
        if (m.chapters.isEmpty()) issues += Issue(IssueCode.EMPTY_PACK, "chapters[] is empty", "chapters")
        val seen = HashMap<Pair<Book, Int>, Int>()
        val spansByFile = HashMap<String, MutableList<Pair<Int, PackChapter>>>()

        m.chapters.forEachIndexed { i, c ->
            val ptr = "chapters[$i]"
            val label = "${c.book.usfm} ${c.chapter}"
            if (!c.book.hasChapter(c.chapter)) {
                issues += Issue(IssueCode.NO_SUCH_CHAPTER, "$label (${c.book.usfm} has ${c.book.chapterCount} chapters)", ptr)
            }
            seen.putIfAbsent(c.book to c.chapter, i)?.let {
                issues += Issue(IssueCode.DUPLICATE_CHAPTER, "$label duplicates chapters[$it]", ptr)
            }
            if (c.file !in durations) {
                issues += Issue(IssueCode.UNKNOWN_FILE, "$label refers to '${c.file}', which is not in files[]", ptr)
                return@forEachIndexed
            }
            val end = c.endMs
            if (c.startMs < 0 || (end != null && end <= c.startMs)) {
                issues += Issue(IssueCode.BAD_SPAN, "$label has span ${c.startMs}..${end ?: "end"}", ptr)
                return@forEachIndexed
            }
            val duration = durations[c.file]
            if (duration != null && (c.startMs >= duration || (end != null && end > duration))) {
                issues += Issue(IssueCode.SPAN_EXCEEDS_FILE, "$label span ${c.startMs}..${end ?: "end"} exceeds file duration $duration ms", ptr)
            }
            spansByFile.getOrPut(c.file) { mutableListOf() } += i to c
        }

        for ((file, spans) in spansByFile) {
            val sorted = spans.sortedBy { it.second.startMs }
            for ((a, b) in sorted.zipWithNext()) {
                val aEnd = a.second.endMs ?: Long.MAX_VALUE
                if (b.second.startMs < aEnd) {
                    issues += Issue(IssueCode.OVERLAPPING_SPANS, "chapters[${a.first}] and chapters[${b.first}] overlap in '$file'", "chapters[${b.first}]")
                }
            }
        }

        val referenced = m.chapters.mapTo(HashSet()) { it.file }
        m.files.forEachIndexed { i, f ->
            if (f.path !in referenced) {
                issues += Issue(IssueCode.UNREFERENCED_FILE, "'${f.path}' is not used by any chapter and won't be imported", "files[$i]")
            } else if (f.durationMs == null) {
                issues += Issue(IssueCode.NO_DURATION, "'${f.path}' has no durationMs", "files[$i]")
            }
        }

        val missing = MissingChapters.summarize(seen.keys)
        if (missing != null) issues += Issue(IssueCode.MISSING_CHAPTERS, "missing: $missing", "chapters")
    }

    /** Null if [path] satisfies spec 0001 §4.4, otherwise the reason it doesn't. */
    fun pathProblem(path: String): String? = when {
        path.isEmpty() -> "path is empty"
        path.startsWith("/") -> "must be relative (no leading '/')"
        '\\' in path -> "must use '/' separators, not '\\'"
        path.any { it < ' ' || it == '\u007F' } -> "contains control characters"
        path.split('/').any { it.isEmpty() } -> "contains an empty segment"
        path.split('/').any { it == "." || it == ".." } -> "contains a '.' or '..' segment"
        !Normalizer.isNormalized(path, Normalizer.Form.NFC) -> "is not NFC-normalised"
        else -> null
    }
}

/** Compact summary of chapters absent from a pack, e.g. `PSA 23; JHN 1-3; all of JUD`. */
object MissingChapters {
    /** Null if [present] covers the whole canon. */
    fun summarize(present: Set<Pair<Book, Int>>): String? {
        val parts = mutableListOf<String>()
        for (book in Book.entries) {
            val missing = (1..book.chapterCount).filter { (book to it) !in present }
            when {
                missing.isEmpty() -> Unit
                missing.size == book.chapterCount -> parts += "all of ${book.usfm}"
                else -> parts += "${book.usfm} " + ranges(missing).joinToString(", ") {
                    if (it.first == it.last) "${it.first}" else "${it.first}-${it.last}"
                }
            }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    private fun ranges(sorted: List<Int>): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var start = sorted.first()
        var prev = start
        for (n in sorted.drop(1)) {
            if (n != prev + 1) {
                out += start..prev
                start = n
            }
            prev = n
        }
        out += start..prev
        return out
    }
}
