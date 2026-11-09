package com.openbibleaudio.openbibleaudio.library

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.text.Normalizer

/** A file in a pack folder. */
data class PackEntry(val path: String, val sizeBytes: Long)

/**
 * A folder the user picked, read through SAF in production and through `java.io` in tests
 * (spec 0002 §10.2).
 */
interface PackSource {
    /** Every file, keyed by '/'-separated relative path. Listed once. */
    fun index(): Map<String, PackEntry>

    fun open(path: String): InputStream

    /** Whether [delete] can work (write access was granted). */
    val canDelete: Boolean

    /** Deletes one source file (for "move"). False if it couldn't. */
    fun delete(path: String): Boolean

    /** Gives up any persisted access grant. */
    fun release() = Unit
}

/** Opens a [PackSource] for a stored URI: `content://` trees go through SAF, `file://` is for tests. */
class PackSourceFactory(private val context: Context) {
    fun open(uri: Uri): PackSource = when (uri.scheme) {
        ContentResolver.SCHEME_CONTENT -> SafPackSource(context.contentResolver, uri)
        ContentResolver.SCHEME_FILE -> FilePackSource(File(requireNotNull(uri.path)))
        else -> throw IOException("Unsupported pack location $uri")
    }
}

class FilePackSource(private val root: File) : PackSource {
    override fun index(): Map<String, PackEntry> {
        if (!root.isDirectory) throw FileNotFoundException("$root is not a folder")
        return root.walkTopDown().filter { it.isFile }.associate { f ->
            val path = Normalizer.normalize(f.relativeTo(root).invariantSeparatorsPath, Normalizer.Form.NFC)
            path to PackEntry(path, f.length())
        }
    }

    override fun open(path: String): InputStream = File(root, path).inputStream()

    override val canDelete: Boolean get() = root.canWrite()

    override fun delete(path: String): Boolean = File(root, path).delete()
}

/**
 * SAF tree. The index is built with one recursive children query per folder. Never use
 * `DocumentFile.findFile` per file: that's O(n) per call (spec 0002 §6.1).
 */
class SafPackSource(private val resolver: ContentResolver, private val treeUri: Uri) : PackSource {
    private val documents = HashMap<String, Uri>()

    override fun index(): Map<String, PackEntry> {
        documents.clear()
        val out = LinkedHashMap<String, PackEntry>()
        list(DocumentsContract.getTreeDocumentId(treeUri), "", out)
        return out
    }

    private fun list(parentId: String, prefix: String, out: MutableMap<String, PackEntry>) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val projection = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE)
        val cursor = resolver.query(children, projection, null, null, null) ?: throw IOException("Can't list $treeUri")
        cursor.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val name = Normalizer.normalize(c.getString(1), Normalizer.Form.NFC)
                val path = prefix + name
                if (c.getString(2) == Document.MIME_TYPE_DIR) {
                    list(id, "$path/", out)
                } else {
                    documents[path] = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                    out[path] = PackEntry(path, if (c.isNull(3)) -1 else c.getLong(3))
                }
            }
        }
    }

    private fun uri(path: String): Uri {
        if (documents.isEmpty()) index()
        return documents[path] ?: throw FileNotFoundException(path)
    }

    override fun open(path: String): InputStream =
        resolver.openInputStream(uri(path)) ?: throw FileNotFoundException(path)

    override val canDelete: Boolean
        get() = resolver.persistedUriPermissions.any { it.uri == treeUri && it.isWritePermission }

    override fun delete(path: String): Boolean = try {
        DocumentsContract.deleteDocument(resolver, uri(path))
    } catch (e: Exception) {
        false
    }

    override fun release() {
        runCatching {
            resolver.releasePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }
}
