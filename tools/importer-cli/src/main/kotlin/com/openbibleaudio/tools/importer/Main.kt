package com.openbibleaudio.tools.importer

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.validate
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import com.openbibleaudio.core.importer.BibleOverrides
import com.openbibleaudio.core.importer.FileScanner
import com.openbibleaudio.core.importer.FileTree
import com.openbibleaudio.core.importer.ImportOptions
import com.openbibleaudio.core.importer.ImporterPipeline
import com.openbibleaudio.core.importer.LocalFileTree
import com.openbibleaudio.core.importer.PackVerifier
import com.openbibleaudio.core.importer.ProgressListener
import com.openbibleaudio.core.importer.probe.AudioProbes
import com.openbibleaudio.core.importer.profile.LayoutProfiles
import com.openbibleaudio.core.model.pack.ManifestFormatException
import com.openbibleaudio.core.model.pack.ManifestValidator
import com.openbibleaudio.core.model.pack.PackGenerator
import com.openbibleaudio.core.model.pack.PackManifestCodec
import com.openbibleaudio.core.model.pack.Severity
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.io.PrintStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.system.exitProcess

const val TOOL_NAME = "openbibleaudio-importer-cli"
const val TOOL_VERSION = "0.1.0"

/** Exit codes (spec 0001 §7). */
object ExitCode {
    const val OK = 0
    const val VALIDATION_ERRORS = 1
    const val USAGE_OR_IO = 2
}

fun main(args: Array<String>) {
    exitProcess(runCli(args.toList(), System.out, System.err, showProgress = System.console() != null))
}

/** Runs the CLI and returns the exit code instead of exiting. Used by tests. */
fun runCli(args: List<String>, out: PrintStream, err: PrintStream, showProgress: Boolean = false): Int {
    val io = CliIo(out, err, showProgress)
    val cli = ImporterCli().subcommands(ProfilesCommand(io), GenerateCommand(io), ValidateCommand(io))
    return try {
        cli.parse(args)
        ExitCode.OK
    } catch (e: ProgramResult) {
        e.statusCode
    } catch (e: CliktError) {
        val help = cli.getFormattedHelp(e)
        if (help != null) (if (e.printError) err else out).println(help)
        if (e is UsageError) ExitCode.USAGE_OR_IO else e.statusCode
    }
}

class CliIo(val out: PrintStream, val err: PrintStream, val showProgress: Boolean)

private class ImporterCli : CliktCommand(name = "importer-cli") {
    override fun help(context: Context) = "Builds and checks Audio Bible Pack manifests (manifest.json)."
    override fun run() = Unit
}

private class ProfilesCommand(private val io: CliIo) : CliktCommand(name = "profiles") {
    override fun help(context: Context) = "List the built-in layout profiles."
    override fun run() {
        LayoutProfiles.all.forEach { io.out.println("${it.id.padEnd(16)} ${it.description}") }
    }
}

private class GenerateCommand(private val io: CliIo) : CliktCommand(name = "generate") {
    override fun help(context: Context) = "Scan a folder with a layout profile and write its manifest.json."

    val packDir by argument(help = "Folder containing the audio files")
        .path(mustExist = true, canBeFile = false, mustBeReadable = true)
    val profileId by option("--profile", help = "Layout profile id (see `profiles`)").required()
    val versionId by option("--version-id")
    val abbreviation by option("--abbreviation")
    val name by option("--name")
    val language by option("--language")
    val narrator by option("--narrator")
    val attribution by option("--attribution")
    val attributionFile by option("--attribution-file").path(mustExist = true, canBeDir = false)
    val noHash by option("--no-hash", help = "Don't compute SHA-256 hashes").flag()
    val jobs by option("--jobs", help = "Files read in parallel").int().default(FileScanner.defaultJobs())
        .validate { require(it >= 1) { "must be at least 1" } }
    val output by option("--output", help = "Where to write the manifest (default <packDir>/manifest.json)").path()
    val force by option("--force", help = "Overwrite an existing manifest").flag()
    val dryRun by option("--dry-run", help = "Report only; write nothing").flag()

    override fun run() {
        val profile = LayoutProfiles.byId(profileId)
            ?: throw UsageError("unknown profile '$profileId' (available: ${LayoutProfiles.all.joinToString { it.id }})")
        if (attribution != null && attributionFile != null) {
            throw UsageError("use either --attribution or --attribution-file, not both")
        }
        val target = output ?: packDir.resolve(FileTree.MANIFEST_FILE_NAME)
        if (!dryRun && target.exists() && !force) {
            io.err.println("$target already exists; pass --force to overwrite it")
            throw ProgramResult(ExitCode.USAGE_OR_IO)
        }

        val options = ImportOptions(
            overrides = BibleOverrides(
                versionId = versionId,
                abbreviation = abbreviation,
                name = name,
                language = language,
                narrator = narrator,
                attribution = attribution ?: attributionFile?.readText()?.trim(),
            ),
            hash = !noHash,
            jobs = jobs,
            probes = AudioProbes.default,
            generator = PackGenerator(TOOL_NAME, TOOL_VERSION, profile.id),
            progress = progressLine(io, "Reading"),
        )
        val result = runBlocking { ImporterPipeline.run(LocalFileTree(packDir), profile, options) }
        clearProgress(io)

        Reporting.printIssues(io.out, result.report.issues)
        val manifest = result.manifest
        if (manifest == null) {
            io.out.println("Not written: ${Reporting.counts(result.report.issues)}")
            throw ProgramResult(ExitCode.VALIDATION_ERRORS)
        }
        io.out.println(Reporting.summary(manifest, result.report.issues))
        if (dryRun) return

        try {
            writeAtomically(target, PackManifestCodec.encode(manifest))
        } catch (e: IOException) {
            io.err.println("Couldn't write $target: ${e.message}")
            throw ProgramResult(ExitCode.USAGE_OR_IO)
        }
        io.out.println("Wrote $target")
    }
}

private class ValidateCommand(private val io: CliIo) : CliktCommand(name = "validate") {
    override fun help(context: Context) = "Validate <packDir>/manifest.json and verify it against the files."

    val packDir by argument().path(mustExist = true, canBeFile = false, mustBeReadable = true)
    val noHash by option("--no-hash", help = "Only check that files exist with the right size").flag()

    override fun run() {
        val file = packDir.resolve(FileTree.MANIFEST_FILE_NAME)
        val manifest = try {
            PackManifestCodec.decode(file.readText())
        } catch (e: IOException) {
            io.err.println("Couldn't read $file: ${e.message}")
            throw ProgramResult(ExitCode.USAGE_OR_IO)
        } catch (e: ManifestFormatException) {
            io.out.println("error: ${e.message}")
            throw ProgramResult(ExitCode.VALIDATION_ERRORS)
        }
        val structural = ManifestValidator.validate(manifest)
        val verified = runBlocking {
            PackVerifier(AudioProbes.default, progress = progressLine(io, "Verifying"))
                .verify(LocalFileTree(packDir), manifest, checkContents = !noHash)
        }
        clearProgress(io)
        val issues = (structural + verified).issues
        Reporting.printIssues(io.out, issues)
        io.out.println(Reporting.summary(manifest, issues))
        if (issues.any { it.severity == Severity.ERROR }) {
            throw ProgramResult(ExitCode.VALIDATION_ERRORS)
        }
    }
}

/** Writes to `<target>.tmp`, then moves it into place (spec 0001 §7). */
internal fun writeAtomically(target: Path, text: String) {
    val tmp = target.resolveSibling("${target.name}.tmp")
    tmp.writeText(text, Charsets.UTF_8)
    try {
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
    }
}

private fun progressLine(io: CliIo, verb: String): ProgressListener? {
    if (!io.showProgress) return null
    var last = 0L
    return ProgressListener { done, total, doneBytes, totalBytes ->
        val now = System.nanoTime()
        synchronized(io) {
            if (done == total || now - last > 200_000_000) {
                last = now
                io.err.print("\r$verb $done/$total files (${Reporting.size(doneBytes)} of ${Reporting.size(totalBytes)})   ")
            }
        }
    }
}

private fun clearProgress(io: CliIo) {
    if (io.showProgress) io.err.print("\r" + " ".repeat(70) + "\r")
}
