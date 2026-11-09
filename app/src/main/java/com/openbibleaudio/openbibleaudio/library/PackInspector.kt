package com.openbibleaudio.openbibleaudio.library

import com.openbibleaudio.core.model.pack.Issue
import com.openbibleaudio.core.model.pack.IssueCode
import com.openbibleaudio.core.model.pack.ManifestFormatException
import com.openbibleaudio.core.model.pack.ManifestValidator
import com.openbibleaudio.core.model.pack.PackFile
import com.openbibleaudio.core.model.pack.PackManifest
import com.openbibleaudio.core.model.pack.PackManifestCodec
import java.io.IOException

/** Result of reading and checking a pack folder before import (spec 0002 §6.1 step 4). */
sealed interface PackInspection {
    data class Valid(
        val manifest: PackManifest,
        val manifestText: String,
        /** Files referenced by chapters; the only ones that get imported. */
        val files: List<PackFile>,
        val issues: List<Issue>,
    ) : PackInspection {
        val totalBytes: Long get() = files.sumOf { it.sizeBytes }
        val missingChapters: String? get() = issues.firstOrNull { it.code == IssueCode.MISSING_CHAPTERS }?.message
    }

    data class Invalid(val problems: List<String>) : PackInspection
}

object PackInspector {
    const val MANIFEST = "manifest.json"
    private const val MAX_PROBLEMS = 20

    fun inspect(source: PackSource): PackInspection {
        val index = try {
            source.index()
        } catch (e: IOException) {
            return PackInspection.Invalid(listOf("Couldn't read the folder: ${e.message}"))
        } catch (e: SecurityException) {
            return PackInspection.Invalid(listOf("No access to the folder: ${e.message}"))
        }
        if (MANIFEST !in index) {
            return PackInspection.Invalid(listOf("This folder has no manifest.json. Create one with the importer CLI first."))
        }
        val text = try {
            source.open(MANIFEST).use { it.readBytes().decodeToString() }
        } catch (e: IOException) {
            return PackInspection.Invalid(listOf("Couldn't read manifest.json: ${e.message}"))
        }
        val manifest = try {
            PackManifestCodec.decode(text)
        } catch (e: ManifestFormatException) {
            return PackInspection.Invalid(listOf(e.message ?: "manifest.json is invalid"))
        }

        val report = ManifestValidator.validate(manifest)
        val problems = report.errors.map { it.toString() }.toMutableList()
        val referenced = manifest.chapters.mapTo(HashSet()) { it.file }
        val files = manifest.files.filter { it.path in referenced }
        for (f in files) {
            val entry = index[f.path]
            when {
                entry == null -> problems += "Missing file: ${f.path}"
                entry.sizeBytes >= 0 && entry.sizeBytes != f.sizeBytes ->
                    problems += "Wrong size: ${f.path} is ${entry.sizeBytes} bytes, manifest says ${f.sizeBytes}"
            }
        }
        if (problems.isNotEmpty()) {
            val shown = problems.take(MAX_PROBLEMS)
            return PackInspection.Invalid(if (problems.size > MAX_PROBLEMS) shown + "… and ${problems.size - MAX_PROBLEMS} more" else shown)
        }
        return PackInspection.Valid(manifest, text, files, report.issues)
    }
}

/** Free-space rule (AD-6, spec 0002 §6.1 step 6). */
object ImportSpace {
    const val MARGIN_BYTES = 64L * 1024 * 1024

    /** [remaining] = sizes of files not yet verified in app storage. */
    fun requiredBytes(remaining: List<Long>, move: Boolean): Long =
        if (remaining.isEmpty()) 0 else (if (move) remaining.max() else remaining.sum()) + MARGIN_BYTES
}
