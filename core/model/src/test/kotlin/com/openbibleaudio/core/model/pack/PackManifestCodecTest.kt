package com.openbibleaudio.core.model.pack

import com.openbibleaudio.core.model.Book
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PackManifestCodecTest {

    private val manifest = PackManifest(
        formatVersion = 1,
        bible = PackBible("bsb-gilbert", "BSB", "Berean Standard Bible", "en", "eng"),
        generator = PackGenerator("tool", "0.1.0", "bsb-gilbert"),
        files = listOf(
            PackFile("BSB_01_Gen_001_G.mp3", 100, "a".repeat(64), 245_000),
            PackFile("book/Psalms.mp3", 200),
        ),
        chapters = listOf(
            PackChapter(Book.GENESIS, 1, "BSB_01_Gen_001_G.mp3"),
            PackChapter(Book.PSALMS, 1, "book/Psalms.mp3", startMs = 0, endMs = 60_000),
            PackChapter(Book.PSALMS, 2, "book/Psalms.mp3", startMs = 60_000),
        ),
    )

    @Test
    fun roundTrip() {
        assertEquals(manifest, PackManifestCodec.decode(PackManifestCodec.encode(manifest)))
    }

    @Test
    fun defaultsAreOmittedAndBooksAreUsfm() {
        val text = PackManifestCodec.encode(manifest)
        assertFalse("\"startMs\": 0" in text, text)
        assertFalse("\"endMs\": null" in text, text)
        assertFalse("verses" in text)
        assertFalse("narrator" in text)
        assertTrue("\"book\": \"GEN\"" in text)
        assertTrue("\"startMs\": 60000" in text)
        assertTrue(text.endsWith("}\n"))
    }

    @Test
    fun unknownFieldsAreIgnoredAndVersesAccepted() {
        val text = """
            {"formatVersion": 1, "future": {"x": 1},
             "bible": {"versionId": "x", "abbreviation": "X", "name": "X", "language": "en", "versification": "eng", "logo": "a.png"},
             "files": [{"path": "a.mp3", "sizeBytes": 1, "codec": "mp3"}],
             "chapters": [{"book": "gen", "chapter": 1, "file": "a.mp3", "verses": [{"verse": 1, "startMs": 0}]}]}
        """.trimIndent()
        val m = PackManifestCodec.decode(text)
        assertEquals(Book.GENESIS, m.chapters.single().book)
        assertNull(m.generator)
    }

    @Test
    fun newerFormatVersionGetsAClearMessage() {
        val e = assertFailsWith<ManifestFormatException> {
            PackManifestCodec.decode("""{"formatVersion": 2, "somethingNew": true}""")
        }
        assertEquals("This pack needs a newer version of OpenBibleAudio (format 2 > 1)", e.message)
    }

    @Test
    fun missingOrZeroFormatVersionIsRejected() {
        assertFailsWith<ManifestFormatException> { PackManifestCodec.decode("""{"bible": {}}""") }
        assertFailsWith<ManifestFormatException> { PackManifestCodec.decode("""{"formatVersion": 0}""") }
        assertFailsWith<ManifestFormatException> { PackManifestCodec.decode("[1]") }
        assertFailsWith<ManifestFormatException> { PackManifestCodec.decode("not json") }
    }

    @Test
    fun unknownBookNamesTheEntry() {
        val text = PackManifestCodec.encode(manifest).replace("\"PSA\"", "\"XYZ\"")
        val e = assertFailsWith<ManifestFormatException> { PackManifestCodec.decode(text) }
        assertEquals("chapters[1].book: unknown book code 'XYZ'", e.message)
    }

    @Test
    fun missingRequiredFieldIsAFormatError() {
        val text = PackManifestCodec.encode(manifest).replace("\"sizeBytes\": 200", "\"size\": 200")
        assertFailsWith<ManifestFormatException> { PackManifestCodec.decode(text) }
    }

    @Test
    fun byteOrderMarkIsTolerated() {
        assertEquals(manifest, PackManifestCodec.decode("﻿" + PackManifestCodec.encode(manifest)))
    }
}
