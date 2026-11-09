package com.openbibleaudio.core.model.pack

enum class Severity { ERROR, WARNING, INFO }

/**
 * Every issue the manifest validator (spec 0001 §4.9) and the importer (§6) can report. Each
 * code has a fixed severity. Importer-only codes live here too, so a single report type covers
 * both the CLI and the app.
 */
enum class IssueCode(val severity: Severity) {
    // Manifest structure (§4.9)
    UNSUPPORTED_FORMAT(Severity.ERROR),
    BAD_VERSION_ID(Severity.ERROR),
    BAD_LANGUAGE(Severity.ERROR),
    BAD_FIELD_LENGTH(Severity.ERROR),
    UNSUPPORTED_VERSIFICATION(Severity.ERROR),
    EMPTY_PACK(Severity.ERROR),
    BAD_PATH(Severity.ERROR),
    DUPLICATE_PATH(Severity.ERROR),
    BAD_SIZE(Severity.ERROR),
    BAD_HASH(Severity.ERROR),
    BAD_DURATION(Severity.ERROR),
    NO_SUCH_CHAPTER(Severity.ERROR),
    DUPLICATE_CHAPTER(Severity.ERROR),
    UNKNOWN_FILE(Severity.ERROR),
    BAD_SPAN(Severity.ERROR),
    OVERLAPPING_SPANS(Severity.ERROR),
    SPAN_EXCEEDS_FILE(Severity.ERROR),
    UNREFERENCED_FILE(Severity.WARNING),
    NO_DURATION(Severity.WARNING),
    MISSING_CHAPTERS(Severity.INFO),

    // Importer: layout profile matching (§6.3)
    UNMATCHED_FILE(Severity.WARNING),
    UNKNOWN_BOOK_ALIAS(Severity.ERROR),
    PROFILE_CHECK_FAILED(Severity.ERROR),
    MISSING_BIBLE_FIELD(Severity.ERROR),

    // Importer: reading files and verifying a pack against them (§6.4, §6.7)
    READ_FAILED(Severity.ERROR),
    PROBE_FAILED(Severity.WARNING),
    MISSING_FILE(Severity.ERROR),
    SIZE_MISMATCH(Severity.ERROR),
    HASH_MISMATCH(Severity.ERROR),
    DURATION_MISMATCH(Severity.WARNING),
}

/** [pointer] locates the issue, e.g. `chapters[12]` or a file path. */
data class Issue(val code: IssueCode, val message: String, val pointer: String? = null) {
    val severity: Severity get() = code.severity

    override fun toString(): String =
        "${severity.name.lowercase()} ${code.name}: ${pointer?.let { "$it: " } ?: ""}$message"
}

data class ValidationReport(val issues: List<Issue>) {
    val errors: List<Issue> get() = issues.filter { it.severity == Severity.ERROR }
    val warnings: List<Issue> get() = issues.filter { it.severity == Severity.WARNING }
    val hasErrors: Boolean get() = issues.any { it.severity == Severity.ERROR }

    operator fun plus(other: ValidationReport) = ValidationReport(issues + other.issues)

    companion object {
        val EMPTY = ValidationReport(emptyList())
    }
}
