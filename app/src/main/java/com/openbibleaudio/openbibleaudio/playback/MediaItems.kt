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
import com.openbibleaudio.core.model.plan.Reading
import com.openbibleaudio.openbibleaudio.plans.DayStatus
import com.openbibleaudio.openbibleaudio.plans.PlanPosition
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

    override val today: String get() = context.getString(R.string.browse_today)
    override val plans: String get() = context.getString(R.string.browse_plans)
    override val noPlans: String get() = context.getString(R.string.browse_no_plans)

    override fun enrollmentToday(planName: String, day: Int, upNext: Boolean) =
        context.getString(if (upNext) R.string.plan_today_up_next else R.string.plan_today, planName, day)

    override fun enrollmentSubtitle(position: PlanPosition): String {
        val day = position.currentDay ?: return context.getString(R.string.plan_finished)
        val res = context.resources
        return when {
            position.behind > 0 -> res.getQuantityString(R.plurals.plan_behind, position.behind, position.behind, day)
            position.ahead > 0 -> res.getQuantityString(R.plurals.plan_ahead, position.ahead, position.ahead, day)
            else -> context.getString(R.string.plan_day_of, day, position.dayCount)
        }
    }

    override fun dayGroup(from: Int, to: Int) = context.getString(R.string.plan_day_group, from, to)

    override fun day(day: Int, status: DayStatus) = context.getString(
        when (status) {
            DayStatus.PENDING -> R.string.plan_day
            DayStatus.IN_PROGRESS -> R.string.plan_day_started
            DayStatus.COMPLETE -> R.string.plan_day_complete
            DayStatus.SKIPPED -> R.string.plan_day_skipped
        },
        day,
    )

    override fun reading(reading: Reading): String {
        val name = books.book(reading.book)
        val last = reading.last
        return if (last == reading.from) {
            context.getString(R.string.plan_reading_chapter, name, reading.from)
        } else {
            context.getString(R.string.plan_reading_range, name, reading.from, last)
        }
    }

    override fun continueItem(title: String) = context.getString(R.string.plan_continue, title)
}

/** Maps browse nodes and resolved chapters to Media3 [MediaItem]s (spec 0002 §7.2, §7.4). */
@OptIn(UnstableApi::class)
class MediaItemFactory(private val books: BookTitles) {

    fun browseItem(node: BrowseNode): MediaItem {
        val extras = Bundle()
        if (node.gridChildren) {
            extras.putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
        } else if (node.listChildren) {
            extras.putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
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
    fun playable(id: MediaId.Chapter, version: VersionInfo, resolved: ResolvedChapter, chaptersInBook: Int): MediaItem =
        chapterItem(id.format(), id.ref, version, resolved, albumTitle = books.book(id.ref.book), chaptersInBook)

    /**
     * A chapter in a plan day's queue (spec 0003 §6.2): the spec 0002 chapter metadata, but its
     * ID is `plan/{e}/day/{n}/{i}` and the album is "The Bible Recap · Day 1".
     */
    fun planChapter(id: MediaId.PlanItem, ref: ChapterRef, version: VersionInfo, resolved: ResolvedChapter, albumTitle: String): MediaItem =
        chapterItem(id.format(), ref, version, resolved, albumTitle, chaptersInBook = 0)

    private fun chapterItem(
        mediaId: String,
        ref: ChapterRef,
        version: VersionInfo,
        resolved: ResolvedChapter,
        albumTitle: String,
        chaptersInBook: Int,
    ): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(books.chapter(ref))
            .setArtist(version.abbreviation)
            .setAlbumTitle(albumTitle)
            .setTrackNumber(ref.chapter)
            .apply { if (chaptersInBook > 0) setTotalTrackCount(chaptersInBook) }
            .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .apply { resolved.durationMs?.let { setDurationMs(it) } }
            .build()
        val builder = MediaItem.Builder().setMediaId(mediaId).setUri(resolved.uri).setMediaMetadata(metadata)
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
