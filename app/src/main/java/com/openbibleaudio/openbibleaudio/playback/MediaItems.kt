package com.openbibleaudio.openbibleaudio.playback

import android.content.Context
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import com.openbibleaudio.core.model.Book
import com.openbibleaudio.core.model.ChapterRef
import com.openbibleaudio.core.model.MediaId
import com.openbibleaudio.core.model.Testament
import com.openbibleaudio.openbibleaudio.R
import com.openbibleaudio.openbibleaudio.library.ResolvedChapter

/** Book names from resources, in canonical order (`R.array.book_names`). */
class BookTitles(context: Context) {
    private val names: Array<String> = context.resources.getStringArray(R.array.book_names).also {
        check(it.size == Book.entries.size) { "book_names must list ${Book.entries.size} books" }
    }

    fun book(b: Book): String = names[b.ordinal]

    fun chapter(ref: ChapterRef): String = "${book(ref.book)} ${ref.chapter}"
}

class ResourceBrowseTitles(private val context: Context, private val books: BookTitles) : BrowseTitles {
    override val bible: String get() = context.getString(R.string.browse_bible)
    override val noLibrary: String get() = context.getString(R.string.browse_no_library)
    override fun testament(t: Testament) = context.getString(if (t == Testament.OLD) R.string.old_testament else R.string.new_testament)
    override fun book(b: Book) = books.book(b)
    override fun chapterGroup(b: Book, from: Int, to: Int) = context.getString(R.string.browse_chapter_group, books.book(b), from, to)
    override fun version(v: VersionInfo) = context.getString(R.string.browse_version, v.abbreviation, v.name)
}

/** Maps browse nodes and resolved chapters to Media3 [MediaItem]s (spec 0002 §7.2, §7.4). */
@OptIn(UnstableApi::class)
class MediaItemFactory(private val books: BookTitles) {

    fun browseItem(node: BrowseNode): MediaItem {
        val extras = Bundle()
        if (node.gridChildren) {
            extras.putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(node.title)
            .setSubtitle(node.subtitle)
            .setIsPlayable(node.playable)
            .setIsBrowsable(node.browsable)
            .setMediaType(if (node.playable) MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER else MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS)
            .setExtras(extras)
            .build()
        return MediaItem.Builder().setMediaId(node.id).setMediaMetadata(metadata).build()
    }

    /** A playable item with its local URI and, for sub-file chapters, clipping. */
    fun playable(id: MediaId.Chapter, version: VersionInfo, resolved: ResolvedChapter, chaptersInBook: Int): MediaItem {
        val ref = id.ref
        val metadata = MediaMetadata.Builder()
            .setTitle(books.chapter(ref))
            .setArtist(version.abbreviation)
            .setAlbumTitle(books.book(ref.book))
            .setTrackNumber(ref.chapter)
            .setTotalTrackCount(chaptersInBook)
            .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .apply { resolved.durationMs?.let { setDurationMs(it) } }
            .build()
        val builder = MediaItem.Builder().setMediaId(id.format()).setUri(resolved.uri).setMediaMetadata(metadata)
        // Clip only sub-file chapters, so whole-file chapters keep normal seeking.
        if (resolved.clipStartMs > 0 || resolved.clipEndMs != null) {
            builder.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(resolved.clipStartMs)
                    .apply { resolved.clipEndMs?.let { setEndPositionMs(it) } }
                    .build(),
            )
        }
        return builder.build()
    }
}
