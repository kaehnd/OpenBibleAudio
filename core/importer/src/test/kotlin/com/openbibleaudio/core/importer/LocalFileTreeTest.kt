package com.openbibleaudio.core.importer

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class LocalFileTreeTest {
    @Test
    fun walksRecursivelyWithIgnoreRules() {
        val root = Files.createTempDirectory("oba-tree")
        try {
            root.resolve("b.mp3").writeBytes(ByteArray(3))
            root.resolve("01 Genesis").createDirectories().resolve("Genesis 001.mp3").writeBytes(ByteArray(5))
            root.resolve(".hidden").createDirectories().resolve("x.mp3").writeBytes(ByteArray(1))
            root.resolve("manifest.json").writeBytes(ByteArray(1))
            root.resolve("c.mp3.part").writeBytes(ByteArray(1))
            root.resolve("Thumbs.db").writeBytes(ByteArray(1))

            val tree = LocalFileTree(root)
            assertEquals(
                listOf(FileEntry("01 Genesis/Genesis 001.mp3", 5), FileEntry("b.mp3", 3)),
                tree.walk().toList(),
            )
            assertContentEquals(ByteArray(5), tree.open("01 Genesis/Genesis 001.mp3").readBytes())
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
