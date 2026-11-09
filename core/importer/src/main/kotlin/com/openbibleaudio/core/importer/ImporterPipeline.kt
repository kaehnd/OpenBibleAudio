package com.openbibleaudio.core.importer

import com.openbibleaudio.core.importer.probe.AudioProbe
import com.openbibleaudio.core.importer.probe.AudioProbes
import com.openbibleaudio.core.importer.probe.ProbeResult
import com.openbibleaudio.core.importer.profile.LayoutProfile
import com.openbibleaudio.core.importer.profile.ProfileMatcher
import com.openbibleaudio.core.model.pack.Issue
import com.openbibleaudio.core.model.pack.IssueCode
import com.openbibleaudio.core.model.pack.ManifestValidator
import com.openbibleaudio.core.model.pack.PackGenerator
import com.openbibleaudio.core.model.pack.PackManifest
import com.openbibleaudio.core.model.pack.ValidationReport

data class ImportOptions(
    val overrides: BibleOverrides = BibleOverrides(),
    val hash: Boolean = true,
    val jobs: Int = FileScanner.defaultJobs(),
    val probes: List<AudioProbe> = AudioProbes.default,
    val generator: PackGenerator? = null,
    val progress: ProgressListener? = null,
)

data class ImportReport(
    val issues: List<Issue>,
    val scannedFiles: Int,
    val matchedFiles: Int,
    val unmatchedFiles: Int,
    val elapsedMs: Long,
) {
    val hasErrors: Boolean get() = ValidationReport(issues).hasErrors
}

/** [manifest] is null if there were errors. */
data class ImportResult(val manifest: PackManifest?, val report: ImportReport)

/**
 * scan → match → probe + hash → build → validate + verify (spec 0001 §6.1).
 * Stops before reading file contents if matching already produced errors.
 */
object ImporterPipeline {
    suspend fun run(tree: FileTree, profile: LayoutProfile, options: ImportOptions = ImportOptions()): ImportResult {
        val started = System.nanoTime()
        val issues = mutableListOf<Issue>()
        val entries = tree.walk().toList()
        val outcome = ProfileMatcher.match(profile, entries)
        issues += outcome.issues
        issues += ManifestBuilder.duplicateChapters(outcome.matches)
        val bible = ManifestBuilder.bible(profile.bibleDefaults, options.overrides, issues)

        fun result(manifest: PackManifest?) = ImportResult(
            manifest?.takeUnless { ValidationReport(issues).hasErrors },
            ImportReport(
                issues = issues.toList(),
                scannedFiles = entries.size,
                matchedFiles = outcome.matchedFiles.size,
                unmatchedFiles = outcome.unmatched.size,
                elapsedMs = (System.nanoTime() - started) / 1_000_000,
            ),
        )

        if (outcome.matches.isEmpty()) {
            issues += Issue(IssueCode.EMPTY_PACK, "no files match profile '${profile.id}'")
        }
        if (bible == null || ValidationReport(issues).hasErrors) return result(null)

        val facts = FileScanner(options.probes, options.hash, options.jobs, options.progress)
            .scan(tree, outcome.matchedFiles)
            .associateBy { it.path }
        val entriesByPath = outcome.matchedFiles.associateBy { it.path }
        for (f in facts.values.sortedBy { it.path }) {
            val entry = entriesByPath.getValue(f.path)
            when {
                f.readError != null -> issues += Issue(IssueCode.READ_FAILED, f.readError, f.path)
                f.bytesRead != entry.sizeBytes ->
                    issues += Issue(IssueCode.SIZE_MISMATCH, "read ${f.bytesRead} bytes, expected ${entry.sizeBytes} (did it change during the scan?)", f.path)
                f.probe is ProbeResult.Failed -> issues += Issue(IssueCode.PROBE_FAILED, f.probe.reason, f.path)
            }
        }
        if (ValidationReport(issues).hasErrors) return result(null)

        val manifest = ManifestBuilder.build(bible, options.generator, outcome.matches, facts)
        issues += ManifestValidator.validate(manifest).issues
        issues += PackVerifier(options.probes).verify(tree, manifest, checkContents = false).issues
        return result(manifest)
    }
}
