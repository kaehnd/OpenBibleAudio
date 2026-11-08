package com.openbibleaudio.tools.importer

import com.openbibleaudio.core.importer.fixtures.BsbNames
import com.openbibleaudio.core.importer.fixtures.Mp3Fixtures
import com.openbibleaudio.core.model.Book
import com.openbibleaudio.core.model.pack.PackManifestCodec
import org.junit.After
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImporterCliTest {
    private val dir: Path = Files.createTempDirectory("oba-cli").also { d ->
        listOf(Book.GENESIS to 1, Book.GENESIS to 2, Book.JUDE to 1).forEachIndexed { i, (book, ch) ->
            d.resolve(BsbNames.fileName(book, ch)).writeBytes(Mp3Fixtures.cbr(10 * (i + 1)))
        }
    }
    private val manifest get() = dir.resolve("manifest.json")

    @After
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    private data class Run(val code: Int, val out: String, val err: String)

    private fun cli(vararg args: String): Run {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = runCli(args.toList(), PrintStream(out, true, "UTF-8"), PrintStream(err, true, "UTF-8"))
        return Run(code, out.toString("UTF-8"), err.toString("UTF-8"))
    }

    @Test
    fun profilesListsBsbGilbert() {
        val r = cli("profiles")
        assertEquals(0, r.code)
        assertTrue(r.out.startsWith("bsb-gilbert"), r.out)
    }

    @Test
    fun generateWritesAValidManifest() {
        val r = cli("generate", dir.toString(), "--profile", "bsb-gilbert", "--attribution", "Public domain")
        assertEquals(0, r.code, r.out + r.err)
        val m = PackManifestCodec.decode(manifest.readText())
        assertEquals(3, m.chapters.size)
        assertEquals("Public domain", m.bible.attribution)
        assertTrue(m.files.all { it.sha256 != null && it.durationMs != null })
        assertTrue("3 chapters (2 books, 1186 chapters missing), 3 files" in r.out, r.out)
        assertFalse(dir.resolve("manifest.json.tmp").exists())
    }

    @Test
    fun refusesToOverwriteWithoutForceAndForceIsByteIdentical() {
        assertEquals(0, cli("generate", dir.toString(), "--profile", "bsb-gilbert").code)
        val first = manifest.readBytes()
        val again = cli("generate", dir.toString(), "--profile", "bsb-gilbert")
        assertEquals(ExitCode.USAGE_OR_IO, again.code)
        assertTrue("--force" in again.err)
        assertEquals(0, cli("generate", dir.toString(), "--profile", "bsb-gilbert", "--force", "--jobs", "1").code)
        assertContentEquals(first, manifest.readBytes())
    }

    @Test
    fun dryRunWritesNothing() {
        val r = cli("generate", dir.toString(), "--profile", "bsb-gilbert", "--dry-run")
        assertEquals(0, r.code)
        assertFalse(manifest.exists())
    }

    @Test
    fun noHashAndOutputOption() {
        val out = dir.resolve("elsewhere.json")
        assertEquals(0, cli("generate", dir.toString(), "--profile", "bsb-gilbert", "--no-hash", "--output", out.toString()).code)
        assertTrue(PackManifestCodec.decode(out.readText()).files.all { it.sha256 == null })
        assertFalse(manifest.exists())
    }

    @Test
    fun usageErrorsExitWith2() {
        assertEquals(ExitCode.USAGE_OR_IO, cli("generate", dir.toString(), "--profile", "nope").code)
        assertEquals(ExitCode.USAGE_OR_IO, cli("generate", dir.toString()).code)
        assertEquals(ExitCode.USAGE_OR_IO, cli("generate", dir.resolve("missing").toString(), "--profile", "bsb-gilbert").code)
        assertEquals(ExitCode.USAGE_OR_IO, cli("generate", dir.toString(), "--profile", "bsb-gilbert", "--jobs", "0").code)
        assertEquals(ExitCode.USAGE_OR_IO, cli("frobnicate").code)
    }

    @Test
    fun matchingErrorsExitWith1AndWriteNothing() {
        dir.resolve("BSB_02_Gen_003_G.mp3").writeBytes(Mp3Fixtures.cbr(5)) // order doesn't match GEN
        val r = cli("generate", dir.toString(), "--profile", "bsb-gilbert")
        assertEquals(ExitCode.VALIDATION_ERRORS, r.code)
        assertTrue("PROFILE_CHECK_FAILED" in r.out, r.out)
        assertFalse(manifest.exists())
    }

    @Test
    fun validateDetectsCorruptionAndMissingFiles() {
        assertEquals(0, cli("generate", dir.toString(), "--profile", "bsb-gilbert").code)
        assertEquals(0, cli("validate", dir.toString()).code)

        val gen1 = dir.resolve(BsbNames.fileName(Book.GENESIS, 1))
        gen1.writeBytes(gen1.readBytes().also { it[100] = (it[100] + 1).toByte() })
        val corrupted = cli("validate", dir.toString())
        assertEquals(ExitCode.VALIDATION_ERRORS, corrupted.code)
        assertTrue("HASH_MISMATCH" in corrupted.out, corrupted.out)
        assertEquals(0, cli("validate", dir.toString(), "--no-hash").code)

        dir.resolve(BsbNames.fileName(Book.JUDE, 1)).deleteExisting()
        val missing = cli("validate", dir.toString(), "--no-hash")
        assertEquals(ExitCode.VALIDATION_ERRORS, missing.code)
        assertTrue("MISSING_FILE" in missing.out, missing.out)
    }

    @Test
    fun validateReportsUndecodableManifest() {
        manifest.writeText("""{"formatVersion": 9}""")
        val r = cli("validate", dir.toString())
        assertEquals(ExitCode.VALIDATION_ERRORS, r.code)
        assertTrue("newer version" in r.out, r.out)
    }

    @Test
    fun validateWithoutManifestIsAnIoError() {
        assertEquals(ExitCode.USAGE_OR_IO, cli("validate", dir.toString()).code)
    }
}
