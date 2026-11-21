package com.openbibleaudio.openbibleaudio.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt
import java.io.File
import java.io.FileNotFoundException

/**
 * Artwork for chapter tiles in Android Auto's grid and the now-playing screen: the chapter number,
 * large, in the launcher icon's colours. Auto shows a warning triangle for grid items without
 * artwork. The images are generated (no user data), so the provider is exported read-only for Auto
 * to fetch them; files are cached under `cacheDir/chapter-art`.
 *
 * `content://{applicationId}.art/chapter/{n}?v={VERSION}` with n in 1..[MAX].
 */
class ChapterArtProvider : ContentProvider() {
    override fun onCreate() = true

    override fun getType(uri: Uri) = "image/png"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read-only")
        val n = number(uri) ?: throw FileNotFoundException(uri.toString())
        val ctx = context ?: throw FileNotFoundException(uri.toString())
        val file = File(ctx.cacheDir, "chapter-art/$VERSION-$n.png")
        if (!file.isFile) render(n, file)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun render(n: Int, file: File) {
        file.parentFile?.mkdirs()
        val bitmap = createBitmap(SIZE, SIZE)
        val canvas = Canvas(bitmap)
        canvas.drawColor(BACKGROUND)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = FOREGROUND
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            // Three digits (Psalms) still fit with margin.
            textSize = SIZE * if (n >= 100) 0.40f else 0.50f
        }
        val y = SIZE / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(n.toString(), SIZE / 2f, y, paint)
        // Write to a temp file and rename, so a concurrent reader never sees a partial PNG.
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        if (!tmp.renameTo(file)) tmp.delete()
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()

    companion object {
        const val MAX = 150
        private const val SIZE = 256
        private const val VERSION = 2 // bump when the design changes: new cache files, and a new URI so Auto refetches
        private val BACKGROUND = "#23395B".toColorInt()
        private val FOREGROUND = "#F6EAD2".toColorInt()

        fun authority(context: Context) = "${context.packageName}.art"

        fun uri(context: Context, chapter: Int): Uri = Uri.Builder()
            .scheme("content").authority(authority(context)).appendPath("chapter").appendPath(chapter.toString())
            .appendQueryParameter("v", VERSION.toString()).build()

        /** The chapter number in a `chapter/{n}` URI, or null if it isn't one. */
        internal fun number(uri: Uri): Int? {
            val segments = uri.pathSegments
            if (segments.size != 2 || segments[0] != "chapter") return null
            return segments[1].toIntOrNull()?.takeIf { it in 1..MAX }
        }
    }
}
