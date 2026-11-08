package com.openbibleaudio.core.importer

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.text.Normalizer
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.streams.asSequence

/** A regular file in a [FileTree]. [path] is relative to the root, '/'-separated, NFC. */
data class FileEntry(val path: String, val sizeBytes: Long)

/**
 * Read-only view of a folder (AD-5, spec 0001 §6.2). Implemented over `java.nio` for the CLI,
 * in memory for tests, and over SAF on device later (Phase 5).
 */
interface FileTree {
    /** All regular files under the root, recursively, minus [isIgnored] ones, sorted by path. */
    fun walk(): Sequence<FileEntry>

    fun open(path: String): InputStream

    /** The file on the local file system, if there is one (used by probes that need a path). */
    fun localPath(path: String): Path? = null

    companion object {
        private val IGNORED_NAMES = setOf("thumbs.db", "desktop.ini")

        /** Spec 0001 §6.2: dot-files, the root manifest, partial downloads and OS clutter. */
        fun isIgnored(path: String): Boolean {
            val segments = path.split('/')
            val name = segments.last()
            return segments.any { it.startsWith(".") } ||
                path == MANIFEST_FILE_NAME || path == "$MANIFEST_FILE_NAME.tmp" ||
                name.endsWith(".part", ignoreCase = true) ||
                name.lowercase() in IGNORED_NAMES
        }

        const val MANIFEST_FILE_NAME = "manifest.json"
    }
}

/** [FileTree] over a local directory. Doesn't follow symlinks. */
class LocalFileTree(private val root: Path) : FileTree {
    private val index: Map<String, Path> by lazy {
        Files.walk(root).use { stream ->
            stream.asSequence()
                .filter { it != root && it.isRegularFile() }
                .associateBy { relative(it) }
                .filterKeys { !FileTree.isIgnored(it) }
                .toSortedMap()
        }
    }

    override fun walk(): Sequence<FileEntry> =
        index.asSequence().map { (path, file) -> FileEntry(path, Files.size(file)) }

    override fun open(path: String): InputStream = Files.newInputStream(localPath(path))

    override fun localPath(path: String): Path = index[path] ?: root.resolve(path)

    private fun relative(file: Path): String =
        Normalizer.normalize(root.relativize(file).joinToString("/") { it.name }, Normalizer.Form.NFC)
}
