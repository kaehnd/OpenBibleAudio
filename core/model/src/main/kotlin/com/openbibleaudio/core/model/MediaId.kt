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

    // --- Plans & podcasts (spec 0003 §3.4) ---

    /** The "Today" root tab. */
    data object Today : MediaId {
        override fun format() = TODAY
    }

    /** The "Plans" root tab. */
    data object Plans : MediaId {
        override fun format() = PLAN
    }

    data class Enrollment(val enrollmentId: Long) : MediaId {
        init { requireEnrollment(enrollmentId) }
        override fun format() = "$PLAN/$enrollmentId"
    }

    /** Playable: resolves to the enrollment's current day when played. */
    data class EnrollmentToday(val enrollmentId: Long) : MediaId {
        init { requireEnrollment(enrollmentId) }
        override fun format() = "$PLAN/$enrollmentId/today"
    }

    /** A slice of an enrollment's day list, so Auto lists stay short. */
    data class DayGroup(val enrollmentId: Long, val from: Int, val to: Int) : MediaId {
        init {
            requireEnrollment(enrollmentId)
            require(from in 1..MAX_DAY && to in 1..MAX_DAY && from <= to) { "bad day group $from-$to" }
        }
        override fun format() = "$PLAN/$enrollmentId/days/$from-$to"
    }

    /** Playable: the whole queue for day [day]. */
    data class PlanDay(val enrollmentId: Long, val day: Int) : MediaId {
        init {
            requireEnrollment(enrollmentId)
            require(day in 1..MAX_DAY) { "bad day $day" }
        }
        override fun format() = "$PLAN/$enrollmentId/day/$day"
    }

    /** Playable leaf: item [index] (0-based) of day [day]'s queue. Every plan queue item uses this. */
    data class PlanItem(val enrollmentId: Long, val day: Int, val index: Int) : MediaId {
        init {
            requireEnrollment(enrollmentId)
            require(day in 1..MAX_DAY) { "bad day $day" }
            require(index in 0..MAX_ITEM) { "bad item index $index" }
        }
        val dayId: PlanDay get() = PlanDay(enrollmentId, day)
        override fun format() = "$PLAN/$enrollmentId/day/$day/$index"
    }

    /** Playable: an episode outside a plan queue. See [PodcastIds]. */
    data class Episode(val feedId: String, val guidHash: String) : MediaId {
        init {
            require(PodcastIds.FEED_ID.matches(feedId)) { "bad feedId '$feedId'" }
            require(PodcastIds.GUID_HASH.matches(guidHash)) { "bad guidHash '$guidHash'" }
        }
        override fun format() = "$PODCAST/$feedId/$guidHash"
    }

    companion object {
        const val ROOT = "root"
        const val BIBLE = "bible"
        const val TODAY = "today"
        const val PLAN = "plan"
        const val PODCAST = "podcast"
        const val MAX_DAY = 9999
        const val MAX_ITEM = 999

        private val ENROLLMENT = Regex("^[1-9][0-9]{0,17}$")
        private val DAY = Regex("^[1-9][0-9]{0,3}$")
        private val ITEM = Regex("^(0|[1-9][0-9]{0,2})$")
        private val DAY_RANGE = Regex("^([1-9][0-9]{0,3})-([1-9][0-9]{0,3})$")

        /** Same rule as the pack manifest's `bible.versionId` (spec 0001 §4.3). */
        val VERSION_ID = Regex("^[a-z0-9][a-z0-9-]{0,31}$")
        private val NUMBER = Regex("^[1-9][0-9]{0,2}$")
        private val RANGE = Regex("^([1-9][0-9]{0,2})-([1-9][0-9]{0,2})$")

        private val Testament.code get() = if (this == Testament.OLD) "OT" else "NT"

        private fun requireVersionId(id: String) = require(VERSION_ID.matches(id)) { "bad versionId '$id'" }
        private fun requireEnrollment(id: Long) = require(id > 0) { "bad enrollmentId $id" }

        /** Strict parse; null for anything that isn't exactly a formatted ID. */
        fun parse(id: String): MediaId? {
            if (id == ROOT) return Root
            if (id == TODAY) return Today
            val parts = id.split('/')
            when (parts[0]) {
                PLAN -> return parsePlan(parts)
                PODCAST -> return parsePodcast(parts)
                BIBLE -> {}
                else -> return null
            }
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

        private fun parsePlan(parts: List<String>): MediaId? {
            if (parts.size == 1) return Plans
            val e = parts[1].takeIf { ENROLLMENT.matches(it) }?.toLongOrNull() ?: return null
            return when {
                parts.size == 2 -> Enrollment(e)
                parts.size == 3 && parts[2] == "today" -> EnrollmentToday(e)
                parts.size == 4 && parts[2] == "days" -> DAY_RANGE.matchEntire(parts[3])?.let {
                    val from = it.groupValues[1].toInt()
                    val to = it.groupValues[2].toInt()
                    if (from <= to) DayGroup(e, from, to) else null
                }
                parts.size in 4..5 && parts[2] == "day" && DAY.matches(parts[3]) -> {
                    val day = parts[3].toInt()
                    if (parts.size == 4) PlanDay(e, day)
                    else parts[4].takeIf { ITEM.matches(it) }?.let { PlanItem(e, day, it.toInt()) }
                }
                else -> null
            }
        }

        private fun parsePodcast(parts: List<String>): MediaId? {
            if (parts.size != 3) return null
            if (!PodcastIds.FEED_ID.matches(parts[1]) || !PodcastIds.GUID_HASH.matches(parts[2])) return null
            return Episode(parts[1], parts[2])
        }
    }
}
