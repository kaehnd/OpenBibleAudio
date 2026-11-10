package com.openbibleaudio.openbibleaudio.plans

import com.openbibleaudio.openbibleaudio.data.db.EnrollmentState

/**
 * What an enrollment card on the phone says (spec 0003 §9.3, §6.3), as data so it's testable;
 * the screen turns it into strings.
 */
data class PlanCard(
    val enrollmentId: Long,
    val planName: String,
    /** Null once the plan is finished. */
    val currentDay: Int?,
    val dayCount: Int,
    val daysDone: Int,
    /** Informational only; never gates playback. */
    val badge: Badge?,
    /** "Today's reading done ✓ — keep going with Day N": set when the calendar day is already done. */
    val keepGoingDay: Int?,
    val paused: Boolean,
) {
    sealed interface Badge {
        data class Behind(val days: Int) : Badge
        data class Ahead(val days: Int) : Badge
    }

    val finished: Boolean get() = currentDay == null

    companion object {
        fun from(s: EnrollmentSummary): PlanCard {
            val p = s.position
            val badge = when {
                p.finished -> null
                p.behind > 0 -> Badge.Behind(p.behind)
                p.ahead > 0 -> Badge.Ahead(p.ahead)
                else -> null
            }
            return PlanCard(
                enrollmentId = s.enrollment.id,
                planName = s.plan.name,
                currentDay = p.currentDay,
                dayCount = p.dayCount,
                daysDone = (1..p.dayCount).count { s.statuses[it] == DayStatus.COMPLETE || s.statuses[it] == DayStatus.SKIPPED },
                badge = badge,
                keepGoingDay = p.currentDay.takeIf { p.upNext },
                paused = s.enrollment.state == EnrollmentState.PAUSED,
            )
        }
    }
}
