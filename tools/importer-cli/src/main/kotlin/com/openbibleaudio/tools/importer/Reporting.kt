package com.openbibleaudio.tools.importer

import com.openbibleaudio.core.model.Book
import com.openbibleaudio.core.model.pack.Issue
import com.openbibleaudio.core.model.pack.PackManifest
import com.openbibleaudio.core.model.pack.Severity
import java.io.PrintStream
import java.util.Locale

/** Human-readable CLI output (spec 0001 §7). */
internal object Reporting {
    /** Issues grouped by severity, errors first. */
    fun printIssues(out: PrintStream, issues: List<Issue>) {
        for (severity in Severity.entries) {
            issues.filter { it.severity == severity }.forEach { out.println(it) }
        }
    }

    fun counts(issues: List<Issue>): String {
        val errors = issues.count { it.severity == Severity.ERROR }
        val warnings = issues.count { it.severity == Severity.WARNING }
        return "$errors error${if (errors == 1) "" else "s"}, $warnings warning${if (warnings == 1) "" else "s"}"
    }

    /** e.g. `1189 chapters (66 books, complete), 1189 files, 5.80 GB, 67h 08m, 0 errors, 0 warnings` */
    fun summary(m: PackManifest, issues: List<Issue>): String {
        val books = m.chapters.map { it.book }.distinct().size
        val chapters = m.chapters.map { it.book to it.chapter }.toSet()
        val canon = Book.entries.sumOf { it.chapterCount }
        val completeness = if (chapters.size == canon) "complete" else "${canon - chapters.size} chapters missing"
        val bytes = m.files.sumOf { it.sizeBytes }
        val durations = m.files.mapNotNull { it.durationMs }
        val duration = if (durations.size == m.files.size) duration(durations.sum()) else "duration unknown"
        return "${m.chapters.size} chapters ($books books, $completeness), ${m.files.size} files, " +
            "${size(bytes)}, $duration, ${counts(issues)}"
    }

    fun size(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> String.format(Locale.ROOT, "%.2f GB", bytes / 1e9)
        bytes >= 1_000_000 -> String.format(Locale.ROOT, "%.1f MB", bytes / 1e6)
        bytes >= 1_000 -> String.format(Locale.ROOT, "%.1f kB", bytes / 1e3)
        else -> "$bytes B"
    }

    fun duration(ms: Long): String {
        val minutes = (ms + 30_000) / 60_000
        return String.format(Locale.ROOT, "%dh %02dm", minutes / 60, minutes % 60)
    }
}
