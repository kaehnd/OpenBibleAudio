package com.openbibleaudio.openbibleaudio.plans

import com.openbibleaudio.openbibleaudio.data.db.EnrollmentState
import java.util.Locale

/** Voice requests for a plan (spec 0003 §9.2). Checked before Bible reference parsing. Pure. */
object PlanVoice {
    private val GENERIC = setOf(
        "today", "todays reading", "today reading", "todays plan", "my plan", "my reading", "my reading plan",
        "my bible plan", "my bible reading", "the plan", "reading plan",
    )

    /**
     * The enrollment [query] asks for, or null when it isn't a plan request. Generic phrases
     * ("today", "my plan") pick [lastPlayed] when it can still play, else the first one that can.
     * A plan's name ("play the bible recap") picks that plan.
     */
    fun match(query: String, enrollments: List<EnrollmentSummary>, lastPlayed: Long?): Long? {
        val q = normalize(query)
        if (q.isEmpty()) return null
        val playable = enrollments.filter { it.enrollment.state == EnrollmentState.ACTIVE && it.position.currentDay != null }
        if (playable.isEmpty()) return null
        if (q in GENERIC) {
            return (playable.firstOrNull { it.enrollment.id == lastPlayed } ?: playable.first()).enrollment.id
        }
        val named = playable.filter { e ->
            val name = normalize(e.plan.name.replace(PARENTHETICAL, " "))
            // The whole name in the query, or a query of 2+ words that's part of the name
            // ("bible recap"). A single word ("genesis") is left to Bible reference parsing.
            name.isNotEmpty() && (" $q ".contains(" $name ") || (q.contains(' ') && " $name ".contains(" $q ")))
        }
        return (named.firstOrNull { it.enrollment.id == lastPlayed } ?: named.firstOrNull())?.enrollment?.id
    }

    private val PARENTHETICAL = Regex("""\([^)]*\)""")
    private val LEADING = Regex("""^(?:play|start|resume|continue)\s+""")

    internal fun normalize(text: String): String = text.lowercase(Locale.ROOT)
        .replace("'", "")
        .replace('’', ' ')
        .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
        .trim()
        .replace(LEADING, "")
        .removePrefix("the ")
        .trim()
}

/** Where to resume a saved `plan/{e}/day/{n}/{i}` (spec 0003 §9.2). Pure. */
object PlanResumption {
    data class Target(val day: Int, val index: Int, val positionMs: Long)

    /**
     * The saved day and item while that day is still open. Once it's been completed (or skipped),
     * the current day from its start; null when the plan is finished.
     */
    fun target(savedDay: Int, savedIndex: Int, positionMs: Long, statuses: Map<Int, DayStatus>, currentDay: Int?): Target? {
        val status = statuses[savedDay] ?: DayStatus.PENDING
        if (status == DayStatus.PENDING || status == DayStatus.IN_PROGRESS) return Target(savedDay, savedIndex, positionMs)
        return currentDay?.let { Target(it, 0, 0) }
    }
}
