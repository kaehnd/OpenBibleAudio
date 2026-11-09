package com.openbibleaudio.openbibleaudio.library

import com.openbibleaudio.core.importer.fixtures.BsbNames
import com.openbibleaudio.core.model.Book
import com.openbibleaudio.core.model.ChapterRef
import org.junit.After
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PackInspectorTest {
    private val dir: File = Files.createTempDirectory("oba-inspect").toFile()

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun pack() = MiniPack.create(dir, listOf(ChapterRef(Book.GENESIS, 1), ChapterRef(Book.JUDE, 1)))

    @Test
    fun validPackReportsMissingChapters() {
        val result = PackInspector.inspect(FilePackSource(pack()))
        assertIs<PackInspection.Valid>(result)
        assertEquals(2, result.files.size)
        assertTrue(result.missingChapters!!.startsWith("missing: GEN 2-50"), result.missingChapters)
    }

    @Test
    fun noManifest() {
        File(dir, "a.mp3").writeBytes(ByteArray(10))
        val result = PackInspector.inspect(FilePackSource(dir))
        assertIs<PackInspection.Invalid>(result)
        assertTrue("manifest.json" in result.problems.single())
    }

    @Test
    fun missingAndResizedFiles() {
        pack()
        File(dir, BsbNames.fileName(Book.GENESIS, 1)).delete()
        File(dir, BsbNames.fileName(Book.JUDE, 1)).appendBytes(ByteArray(3))
        val result = PackInspector.inspect(FilePackSource(dir))
        assertIs<PackInspection.Invalid>(result)
        assertEquals(2, result.problems.size, "${result.problems}")
    }

    @Test
    fun newerFormatIsRejectedWithItsMessage() {
        File(dir, "manifest.json").writeText("""{"formatVersion": 3}""")
        val result = PackInspector.inspect(FilePackSource(dir))
        assertIs<PackInspection.Invalid>(result)
        assertTrue("newer version" in result.problems.single())
    }
}
