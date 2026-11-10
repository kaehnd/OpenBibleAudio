package com.openbibleaudio.openbibleaudio.playback

import com.openbibleaudio.core.model.Book
import com.openbibleaudio.core.model.ChapterRef
import com.openbibleaudio.core.model.MediaId
import com.openbibleaudio.core.model.Testament
import com.openbibleaudio.core.model.plan.Reading
import com.openbibleaudio.openbibleaudio.plans.DayStatus
import com.openbibleaudio.openbibleaudio.plans.EnrollmentSummary
import com.openbibleaudio.openbibleaudio.plans.PlanPosition

/** Display strings for the browse tree. The service implements this with string resources. */
interface BrowseTitles {
    val bible: String
    val noLibrary: String
    fun testament(t: Testament): String
    fun book(b: Book): String
    fun chapterGroup(b: Book, from: Int, to: Int): String
    fun version(v: VersionInfo): String

    // Plans (spec 0003 §9.1)
    val today: String
    val plans: String
    val noPlans: String
    /** "The Bible Recap · Day 47", plus "(up next)" when the calendar day is already done. */
    fun enrollmentToday(planName: String, day: Int, upNext: Boolean): String
    /** "Day 47 · 3 days behind", "Day 47 of 365", "Finished". */
    fun enrollmentSubtitle(position: PlanPosition): String
    fun dayGroup(from: Int, to: Int): String
    fun day(day: Int, status: DayStatus): String
    fun reading(reading: Reading): String
    fun continueItem(title: String): String
}

/** The saved resumption item, offered on the Today tab. */
data class ResumeEntry(val mediaId: String, val title: String)

/** Plan input to the browse tree: enrollments with progress, and the resumption item. */
data class PlanBrowseState(val enrollments: List<EnrollmentSummary>, val resume: ResumeEntry?) {
    fun enrollment(id: Long): EnrollmentSummary? = enrollments.firstOrNull { it.enrollment.id == id }

    companion object {
        val EMPTY = PlanBrowseState(emptyList(), null)
    }
}

/** One node of the browse tree, independent of Media3 types. */
data class BrowseNode(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val playable: Boolean,
    val browsable: Boolean,
    /** Children should be shown as a grid (chapter numbers). */
    val gridChildren: Boolean = false,
    /** Playable children should be shown as a list (plan days), overriding the root's grid default. */
    val listChildren: Boolean = false,
    /** Set for playable chapter nodes. */
    val chapter: MediaId.Chapter? = null,
)

/**
 * The Android Auto browse tree (AD-3, spec 0002 §7.4, spec 0003 §9.1) as a pure function of a
 * [LibrarySnapshot] and a [PlanBrowseState].
 */
class BrowseTree(private val titles: BrowseTitles) {

    fun root() = BrowseNode(MediaId.ROOT, "Root", playable = false, browsable = true)

    /** Children of [id], or null if [id] doesn't name a browsable node in [snap]. */
    fun children(id: MediaId, snap: LibrarySnapshot, plans: PlanBrowseState = PlanBrowseState.EMPTY): List<BrowseNode>? = when (id) {
        MediaId.Root -> listOf(node(MediaId.Today, snap, plans)!!, node(MediaId.Bible, snap)!!, node(MediaId.Plans, snap, plans)!!)
        MediaId.Bible -> when (snap.versions.size) {
            0 -> listOf(BrowseNode(NO_LIBRARY_ID, titles.noLibrary, playable = false, browsable = false))
            1 -> testaments(snap.versions.single().versionId, snap)
            else -> snap.versions.map { node(MediaId.Version(it.versionId), snap)!! }
        }
        is MediaId.Version -> snap.version(id.versionId)?.let { testaments(it.versionId, snap) }
        is MediaId.TestamentNode -> snap.version(id.versionId)?.let {
            snap.books(id.versionId).filter { it.testament == id.testament }.map { b -> node(MediaId.BookNode(id.versionId, b), snap)!! }
        }
        is MediaId.BookNode -> snap.version(id.versionId)?.let {
            val chapters = snap.chapters(id.versionId, id.book)
            if (chapters.isEmpty()) {
                null
            } else if (chapters.size > CHAPTER_GROUP_SIZE) {
                groups(id.book, chapters).map { (from, to) -> node(MediaId.ChapterGroup(id.versionId, id.book, from, to), snap)!! }
            } else {
                chapters.map { chapterNode(id.versionId, it, short = true) }
            }
        }
        is MediaId.ChapterGroup -> snap.version(id.versionId)?.let {
            snap.chapters(id.versionId, id.book).filter { it.chapter in id.from..id.to }.map { chapterNode(id.versionId, it, short = true) }
                .ifEmpty { null }
        }
        is MediaId.Chapter -> null
        MediaId.Today -> buildList {
            plans.enrollments.mapNotNullTo(this) { enrollmentToday(it) }
            plans.resume?.let { add(BrowseNode(it.mediaId, titles.continueItem(it.title), playable = true, browsable = false)) }
            if (isEmpty()) add(noPlansItem())
        }
        MediaId.Plans -> plans.enrollments.map { enrollmentNode(it) }.ifEmpty { listOf(noPlansItem()) }
        is MediaId.Enrollment -> plans.enrollment(id.enrollmentId)?.let { e ->
            listOfNotNull(enrollmentToday(e)) + dayGroups(e).map { (from, to) -> dayGroupNode(e, from, to) }
        }
        is MediaId.DayGroup -> plans.enrollment(id.enrollmentId)
            ?.takeIf { e -> dayGroups(e).contains(id.from to id.to) }
            ?.let { e -> (id.from..id.to).mapNotNull { dayNode(e, it) } }
        is MediaId.EnrollmentToday, is MediaId.PlanDay, is MediaId.PlanItem, is MediaId.Episode -> null
    }

    /** The node for [id] itself (Auto's onGetItem after process death), or null if it doesn't exist. */
    fun node(id: MediaId, snap: LibrarySnapshot, plans: PlanBrowseState = PlanBrowseState.EMPTY): BrowseNode? = when (id) {
        MediaId.Root -> root()
        MediaId.Bible -> BrowseNode(id.format(), titles.bible, playable = false, browsable = true)
        is MediaId.Version -> snap.version(id.versionId)?.let {
            BrowseNode(id.format(), titles.version(it), playable = false, browsable = true)
        }
        is MediaId.TestamentNode -> snap.version(id.versionId)
            ?.takeIf { snap.books(id.versionId).any { it.testament == id.testament } }
            ?.let { BrowseNode(id.format(), titles.testament(id.testament), playable = false, browsable = true) }
        is MediaId.BookNode -> snap.version(id.versionId)
            ?.takeIf { snap.chapters(id.versionId, id.book).isNotEmpty() }
            ?.let {
                val grid = snap.chapters(id.versionId, id.book).size <= CHAPTER_GROUP_SIZE
                BrowseNode(id.format(), titles.book(id.book), playable = false, browsable = true, gridChildren = grid)
            }
        is MediaId.ChapterGroup -> snap.version(id.versionId)
            ?.takeIf { snap.chapters(id.versionId, id.book).any { it.chapter in id.from..id.to } }
            ?.let { BrowseNode(id.format(), titles.chapterGroup(id.book, id.from, id.to), playable = false, browsable = true, gridChildren = true) }
        is MediaId.Chapter -> snap.version(id.versionId)
            ?.takeIf { snap.has(id.versionId, id.ref) }
            ?.let { chapterNode(id.versionId, id.ref, short = false) }
        MediaId.Today -> BrowseNode(id.format(), titles.today, playable = false, browsable = true, listChildren = true)
        MediaId.Plans -> BrowseNode(id.format(), titles.plans, playable = false, browsable = true)
        is MediaId.Enrollment -> plans.enrollment(id.enrollmentId)?.let { enrollmentNode(it) }
        is MediaId.EnrollmentToday -> plans.enrollment(id.enrollmentId)?.let { enrollmentToday(it) }
        is MediaId.DayGroup -> plans.enrollment(id.enrollmentId)
            ?.takeIf { e -> dayGroups(e).contains(id.from to id.to) }
            ?.let { e -> dayGroupNode(e, id.from, id.to) }
        is MediaId.PlanDay -> plans.enrollment(id.enrollmentId)?.let { dayNode(it, id.day) }
        // Queue items and episodes are rebuilt by the service, not browsed.
        is MediaId.PlanItem, is MediaId.Episode -> null
    }

    // --- Plans -------------------------------------------------------------------------------

    private fun noPlansItem() = BrowseNode(NO_PLANS_ID, titles.noPlans, playable = false, browsable = false)

    private fun enrollmentNode(e: EnrollmentSummary) = BrowseNode(
        MediaId.Enrollment(e.enrollment.id).format(), e.plan.name, titles.enrollmentSubtitle(e.position),
        playable = false, browsable = true, listChildren = true,
    )

    /** `plan/{e}/today`: always the current day; null once the plan is finished. */
    private fun enrollmentToday(e: EnrollmentSummary): BrowseNode? {
        val day = e.position.currentDay ?: return null
        return BrowseNode(
            MediaId.EnrollmentToday(e.enrollment.id).format(),
            titles.enrollmentToday(e.plan.name, day, e.position.upNext),
            readings(e, day),
            playable = true, browsable = false,
        )
    }

    private fun dayGroupNode(e: EnrollmentSummary, from: Int, to: Int) = BrowseNode(
        MediaId.DayGroup(e.enrollment.id, from, to).format(), titles.dayGroup(from, to),
        playable = false, browsable = true, listChildren = true,
    )

    private fun dayNode(e: EnrollmentSummary, day: Int): BrowseNode? {
        e.plan.day(day) ?: return null
        return BrowseNode(
            MediaId.PlanDay(e.enrollment.id, day).format(),
            titles.day(day, e.statuses[day] ?: DayStatus.PENDING),
            readings(e, day),
            playable = true, browsable = false,
        )
    }

    private fun readings(e: EnrollmentSummary, day: Int): String? =
        e.plan.day(day)?.readings?.joinToString(", ") { titles.reading(it) }

    /** Groups of [DAY_GROUP_SIZE]; the group holding the current day comes first (spec 0003 §9.1). */
    fun dayGroups(e: EnrollmentSummary): List<Pair<Int, Int>> {
        val count = e.plan.dayCount
        val groups = (1..count step DAY_GROUP_SIZE).map { it to minOf(it + DAY_GROUP_SIZE - 1, count) }
        val current = e.position.currentDay ?: return groups
        val (holding, rest) = groups.partition { current in it.first..it.second }
        return holding + rest
    }

    private fun testaments(versionId: String, snap: LibrarySnapshot): List<BrowseNode> =
        Testament.entries.mapNotNull { node(MediaId.TestamentNode(versionId, it), snap) }

    private fun chapterNode(versionId: String, ref: ChapterRef, short: Boolean): BrowseNode {
        val id = MediaId.Chapter(versionId, ref)
        val title = if (short) ref.chapter.toString() else "${titles.book(ref.book)} ${ref.chapter}"
        return BrowseNode(id.format(), title, playable = true, browsable = false, chapter = id)
    }

    companion object {
        /** Books with more chapters than this are split into groups (spec 0002 §7.4, open question 1). */
        const val CHAPTER_GROUP_SIZE = 50

        /** A non-playable, non-browsable message item shown when nothing is imported. */
        const val NO_LIBRARY_ID = "info/no-library"

        /** The message item shown under Today and Plans with no enrollments. */
        const val NO_PLANS_ID = "info/no-plans"

        /** Days per group under an enrollment, to stay under Auto's list limits (open question 7). */
        const val DAY_GROUP_SIZE = 30

        /** Groups of [CHAPTER_GROUP_SIZE] chapter numbers that contain at least one available chapter. */
        fun groups(book: Book, chapters: List<ChapterRef>): List<Pair<Int, Int>> =
            (1..book.chapterCount step CHAPTER_GROUP_SIZE)
                .map { from -> from to minOf(from + CHAPTER_GROUP_SIZE - 1, book.chapterCount) }
                .filter { (from, to) -> chapters.any { it.chapter in from..to } }
    }
}
