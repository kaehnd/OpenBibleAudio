package com.openbibleaudio.core.model

/**
 * Stable, parseable IDs for every browsable or playable node (AD-3, spec 0002 §2.2). Android
 * Auto hands back IDs without URIs, so the playback service rebuilds any item from its ID alone.
 *
 * `format(parse(s)) == s` for every valid `s`.
 */
sealed interface MediaId {
    fun format(): String

    data object Root : MediaId {
        override fun format() = ROOT
    }

    data object Bible : MediaId {
        override fun format() = BIBLE
    }

    data class Version(val versionId: String) : MediaId {
        init { requireVersionId(versionId) }
        override fun format() = "$BIBLE/$versionId"
    }

    data class TestamentNode(val versionId: String, val testament: Testament) : MediaId {
        init { requireVersionId(versionId) }
        override fun format() = "$BIBLE/$versionId/${testament.code}"
    }

    data class BookNode(val versionId: String, val book: Book) : MediaId {
        init { requireVersionId(versionId) }
        override fun format() = "$BIBLE/$versionId/${book.usfm}"
    }

    /** A slice of a long book's chapter list, so Auto lists stay short (spec 0002 §7.4). */
    data class ChapterGroup(val versionId: String, val book: Book, val from: Int, val to: Int) : MediaId {
        init {
            requireVersionId(versionId)
            require(book.hasChapter(from) && book.hasChapter(to) && from <= to) { "bad group ${book.usfm} $from-$to" }
        }
        override fun format() = "$BIBLE/$versionId/${book.usfm}/$from-$to"
    }

    /** Playable. */
    data class Chapter(val versionId: String, val ref: ChapterRef) : MediaId {
        init { requireVersionId(versionId) }
        override fun format() = "$BIBLE/$versionId/${ref.book.usfm}/${ref.chapter}"
    }

    companion object {
        const val ROOT = "root"
        const val BIBLE = "bible"

        /** Same rule as the pack manifest's `bible.versionId` (spec 0001 §4.3). */
        val VERSION_ID = Regex("^[a-z0-9][a-z0-9-]{0,31}$")
        private val NUMBER = Regex("^[1-9][0-9]{0,2}$")
        private val RANGE = Regex("^([1-9][0-9]{0,2})-([1-9][0-9]{0,2})$")

        private val Testament.code get() = if (this == Testament.OLD) "OT" else "NT"

        private fun requireVersionId(id: String) = require(VERSION_ID.matches(id)) { "bad versionId '$id'" }

        /** Strict parse; null for anything that isn't exactly a formatted ID. */
        fun parse(id: String): MediaId? {
            if (id == ROOT) return Root
            val parts = id.split('/')
            if (parts[0] != BIBLE) return null
            if (parts.size == 1) return Bible
            val versionId = parts[1]
            if (!VERSION_ID.matches(versionId)) return null
            if (parts.size == 2) return Version(versionId)
            val third = parts[2]
            if (parts.size == 3) {
                when (third) {
                    "OT" -> return TestamentNode(versionId, Testament.OLD)
                    "NT" -> return TestamentNode(versionId, Testament.NEW)
                }
            }
            // Book codes are uppercase in IDs; Book.fromUsfm is case-insensitive, so check exactly.
            val book = Book.fromUsfm(third)?.takeIf { it.usfm == third } ?: return null
            if (parts.size == 3) return BookNode(versionId, book)
            if (parts.size != 4) return null
            val last = parts[3]
            NUMBER.matchEntire(last)?.let {
                return ChapterRef.of(book, last.toInt())?.let { ref -> Chapter(versionId, ref) }
            }
            RANGE.matchEntire(last)?.let {
                val from = it.groupValues[1].toInt()
                val to = it.groupValues[2].toInt()
                return if (book.hasChapter(from) && book.hasChapter(to) && from <= to) ChapterGroup(versionId, book, from, to) else null
            }
            return null
        }
    }
}
