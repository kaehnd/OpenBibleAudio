package com.openbibleaudio.core.importer

import com.openbibleaudio.core.importer.probe.AudioProbe
import com.openbibleaudio.core.importer.probe.ProbeResult
import com.openbibleaudio.core.model.pack.Issue
import com.openbibleaudio.core.model.pack.IssueCode
import com.openbibleaudio.core.model.pack.PackManifest
import com.openbibleaudio.core.model.pack.ValidationReport
import kotlin.math.abs

/** Checks a manifest against the files actually in a [FileTree] (spec 0001 §6.7). */
class PackVerifier(
    private val probes: List<AudioProbe>,
    private val jobs: Int = FileScanner.defaultJobs(),
    private val progress: ProgressListener? = null,
) {
    /**
     * Always checks existence and size. With [checkContents], also re-hashes (files with a
     * `sha256`) and re-probes durations (files with a `durationMs`), reading every file once.
     */
    suspend fun verify(tree: FileTree, manifest: PackManifest, checkContents: Boolean): ValidationReport {
        val issues = mutableListOf<Issue>()
        val present = tree.walk().associateBy { it.path }
        val toRead = mutableListOf<FileEntry>()

        for (f in manifest.files) {
            val entry = present[f.path]
            when {
                entry == null -> issues += Issue(IssueCode.MISSING_FILE, "not found in the pack folder", f.path)
                entry.sizeBytes != f.sizeBytes ->
                    issues += Issue(IssueCode.SIZE_MISMATCH, "is ${entry.sizeBytes} bytes, manifest says ${f.sizeBytes}", f.path)
                checkContents && (f.sha256 != null || f.durationMs != null) -> toRead += entry
            }
        }
        if (toRead.isEmpty()) return ValidationReport(issues)

        val expected = manifest.files.associateBy { it.path }
        val facts = FileScanner(probes, hash = true, jobs = jobs, progress = progress).scan(tree, toRead)
        for (fact in facts) {
            val f = expected.getValue(fact.path)
            if (fact.readError != null) {
                issues += Issue(IssueCode.READ_FAILED, fact.readError, fact.path)
                continue
            }
            if (f.sha256 != null && fact.sha256 != f.sha256) {
                issues += Issue(IssueCode.HASH_MISMATCH, "SHA-256 differs from the manifest", fact.path)
            }
            val probed = (fact.probe as? ProbeResult.Ok)?.durationMs
            val declared = f.durationMs
            if (declared != null && probed != null && abs(probed - declared) > DURATION_TOLERANCE_MS) {
                issues += Issue(IssueCode.DURATION_MISMATCH, "probed $probed ms, manifest says $declared ms", fact.path)
            }
        }
        return ValidationReport(issues)
    }

    companion object {
        const val DURATION_TOLERANCE_MS = 1000L
    }
}
