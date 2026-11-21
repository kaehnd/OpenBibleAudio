package com.openbibleaudio.openbibleaudio.plans

import com.openbibleaudio.core.model.ChapterRef
import java.time.LocalDate
import java.time.temporal.ChronoUnit

enum class DayStatus { PENDING, IN_PROGRESS, COMPLETE, SKIPPED }

/** Stable keys for the items of a day, used by `enrollment_item_completion` (spec 0003 §5). */
object ItemKey {
    fun chapter(ref: ChapterRef) = "ch/${ref.book.usfm}/${ref.chapter}"
    fun episode(guidHash: String) = "ep/$guidHash"
}

/**
 * Where an enrollment stands (spec 0003 §6.3).
 *
 * [currentDay] is the lowest PENDING or IN_PROGRESS day, or null once every day is done.
 * It is driven only by completion, never by the calendar: after finishing a day the next one
 * is current straight away, even when the listener is caught up or ahead.
 */
data class PlanPosition(
    val dayCount: Int,
    val currentDay: Int?,
    /** `today − startDate + 1`, clamped to 1..[dayCount]. */
    val calendarDay: Int,
    /** Days the listener is behind the calendar (0 when not behind). */
    val behind: Int,
    /** Days the listener has gone past today's calendar day (0 when not ahead). */
    val ahead: Int,
) {
    val finished: Boolean get() = currentDay == null

    /**
     * Today's calendar day is already done, so Today offers the next day "(up next)". Playback is
     * never gated on this; it only changes how Today is labelled.
     */
    val upNext: Boolean get() = currentDay != null && currentDay > calendarDay
}

object PlanProgress {

    fun position(dayCount: Int, statuses: Map<Int, DayStatus>, startDate: LocalDate, today: LocalDate): PlanPosition {
        require(dayCount >= 1)
        val current = (1..dayCount).firstOrNull { (statuses[it] ?: DayStatus.PENDING).isOpen() }
        val calendar = calendarDay(dayCount, startDate, today)
        return PlanPosition(
            dayCount = dayCount,
            currentDay = current,
            calendarDay = calendar,
            behind = if (current == null) 0 else (calendar - current).coerceAtLeast(0),
            ahead = if (current == null) 0 else (current - calendar - 1).coerceAtLeast(0),
        )
    }

    fun calendarDay(dayCount: Int, startDate: LocalDate, today: LocalDate): Int =
        (ChronoUnit.DAYS.between(startDate, today) + 1).coerceIn(1, dayCount.toLong()).toInt()

    /**
     * Status of a day from its item completions. [episodeKey] is null when the day has no
     * episode in its queue; [episodeWasUnavailable] is true when the day's queue was built
     * without its episode, which then doesn't block completion (spec 0003 §6.3, open question 3).
     */
    fun statusFromItems(
        readingKeys: List<String>,
        episodeKey: String?,
        completed: Set<String>,
        episodeWasUnavailable: Boolean,
    ): DayStatus {
        val readingsDone = readingKeys.all { it in completed }
        val episodeDone = episodeKey == null || episodeKey in completed || episodeWasUnavailable
        return when {
            readingsDone && episodeDone -> DayStatus.COMPLETE
            readingKeys.any { it in completed } || (episodeKey != null && episodeKey in completed) -> DayStatus.IN_PROGRESS
            else -> DayStatus.PENDING
        }
    }

    /**
     * Applies a recomputed status. Manual decisions (COMPLETE via "Mark complete", SKIPPED) stand:
     * only open days move.
     */
    fun merge(existing: DayStatus?, computed: DayStatus): DayStatus {
        val e = existing ?: DayStatus.PENDING
        return if (e.isOpen()) computed else e
    }

    /** "Skip to today": every open day before the calendar day becomes SKIPPED. */
    fun skipToToday(position: PlanPosition, statuses: Map<Int, DayStatus>): Map<Int, DayStatus> =
        (1 until position.calendarDay)
            .filter { (statuses[it] ?: DayStatus.PENDING).isOpen() }
            .associateWith { DayStatus.SKIPPED }

    /** The start date that makes [day] today's calendar day: on schedule, neither behind nor ahead. */
    fun startDateFor(day: Int, today: LocalDate): LocalDate = today.minusDays((day - 1).toLong())

    /**
     * "Start from day N": the open days before [day], which become COMPLETE. Every day from [day] on
     * is reset to not done separately.
     */
    fun daysCompletedByStartingAt(day: Int, statuses: Map<Int, DayStatus>): List<Int> =
        (1 until day).filter { (statuses[it] ?: DayStatus.PENDING).isOpen() }

    /** The day to play after [day] when "continue to next day" is on: the next open day after it. */
    fun nextDayAfter(day: Int, dayCount: Int, statuses: Map<Int, DayStatus>): Int? =
        (day + 1..dayCount).firstOrNull { (statuses[it] ?: DayStatus.PENDING).isOpen() }

    private fun DayStatus.isOpen() = this == DayStatus.PENDING || this == DayStatus.IN_PROGRESS
}
