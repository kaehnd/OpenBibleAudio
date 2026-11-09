package com.openbibleaudio.core.model.plan

import com.openbibleaudio.core.model.Book
import com.openbibleaudio.core.model.ChapterRef
import com.openbibleaudio.core.model.pack.Issue
import com.openbibleaudio.core.model.pack.IssueCode
import com.openbibleaudio.core.model.pack.ValidationReport
import java.util.regex.PatternSyntaxException

/** Checks a decoded plan (spec 0003 §3.2). Pure; never throws. */
object PlanValidator {
    val PLAN_ID = Regex("^[a-z0-9][a-z0-9-]{0,63}$")
    private const val MAX_NAME = 200
    private val TOTAL_CHAPTERS = Book.entries.sumOf { it.chapterCount }

    fun validate(plan: PlanDefinition): ValidationReport {
        val issues = mutableListOf<Issue>()
        fun add(code: IssueCode, message: String, pointer: String? = null) = issues.add(Issue(code, message, pointer))

        if (plan.formatVersion != PlanCodec.SUPPORTED_FORMAT_VERSION) {
            add(IssueCode.UNSUPPORTED_FORMAT, "formatVersion ${plan.formatVersion} is not supported", "formatVersion")
        }
        if (!PLAN_ID.matches(plan.id)) add(IssueCode.BAD_PLAN_ID, "'${plan.id}' must match ${PLAN_ID.pattern}", "id")
        if (plan.version < 1) add(IssueCode.BAD_PLAN_VERSION, "version must be ≥ 1", "version")
        if (plan.name.isBlank() || plan.name.length > MAX_NAME) {
            add(IssueCode.BAD_FIELD_LENGTH, "name must be 1–$MAX_NAME characters", "name")
        }

        if (plan.days.isEmpty()) add(IssueCode.EMPTY_PLAN, "the plan has no days", "days")
        plan.days.forEachIndexed { i, day ->
            val at = "days[$i]"
            if (day.day != i + 1) add(IssueCode.BAD_DAY_NUMBER, "expected day ${i + 1}, found ${day.day}", at)
            if (day.readings.isEmpty()) add(IssueCode.EMPTY_DAY, "day ${day.day} has no readings", at)
            day.readings.forEachIndexed { r, reading ->
                val rp = "$at.readings[$r]"
                val bad = listOf(reading.from, reading.last).filterNot { reading.book.hasChapter(it) }
                when {
                    bad.isNotEmpty() -> add(
                        IssueCode.NO_SUCH_CHAPTER,
                        "${reading.book.usfm} has ${reading.book.chapterCount} chapters (got ${bad.joinToString()})", rp,
                    )
                    reading.from > reading.last -> add(IssueCode.BAD_RANGE, "from ${reading.from} > to ${reading.last}", rp)
                }
            }
        }

        plan.podcast?.let { validatePodcast(it, plan.days.size, ::add) }
        coverage(plan)?.let { add(IssueCode.PARTIAL_COVERAGE, it, "days") }
        return ValidationReport(issues)
    }

    private fun validatePodcast(p: PlanPodcast, dayCount: Int, add: (IssueCode, String, String?) -> Unit) {
        if (!p.feedUrl.startsWith("https://") || p.feedUrl.length <= "https://".length) {
            add(IssueCode.BAD_FEED_URL, "feedUrl must be an https URL", "podcast.feedUrl")
        }
        checkRegex(p.match.titleRegex, p.match.dayGroup, "podcast.match.titleRegex", add)
        p.cycle?.let { c ->
            if (c.key == CycleKey.TITLE_REGEX && c.titleRegex == null) {
                add(IssueCode.BAD_REGEX, "key 'titleRegex' needs cycle.titleRegex", "podcast.cycle")
            }
            c.titleRegex?.let { checkRegex(it, 1, "podcast.cycle.titleRegex", add) }
            c.fallbackTitleRegex?.let { checkRegex(it, 1, "podcast.cycle.fallbackTitleRegex", add) }
        }
        val keyShape = if (p.cycle != null) Regex("^([1-9][0-9]*)/([1-9][0-9]*)$") else Regex("^()([1-9][0-9]*)$")
        for ((key, override) in p.overrides) {
            val at = "podcast.overrides[\"$key\"]"
            val m = keyShape.matchEntire(key)
            val day = m?.groupValues?.get(2)?.toIntOrNull()
            when {
                m == null -> add(
                    IssueCode.BAD_OVERRIDE,
                    if (p.cycle != null) "key must be \"{cycle}/{day}\"" else "key must be \"{day}\"", at,
                )
                day == null || day !in 1..dayCount -> add(IssueCode.BAD_OVERRIDE, "day must be 1–$dayCount", at)
            }
            if (override.guid.isBlank()) add(IssueCode.BAD_OVERRIDE, "guid is empty", at)
        }
    }

    private fun checkRegex(pattern: String, group: Int, at: String, add: (IssueCode, String, String?) -> Unit) {
        val groups = try {
            java.util.regex.Pattern.compile(pattern).matcher("").groupCount()
        } catch (e: PatternSyntaxException) {
            add(IssueCode.BAD_REGEX, "doesn't compile: ${e.description}", at)
            return
        }
        if (group < 1 || group > groups) add(IssueCode.BAD_REGEX, "needs capture group $group (has $groups)", at)
    }

    /** Null when the plan reads every chapter of the canon exactly once (spec 0003 §3.2). */
    private fun coverage(plan: PlanDefinition): String? {
        val all = plan.days.flatMap { it.chapters() }
        val distinct = all.toSet()
        if (all.size == TOTAL_CHAPTERS && distinct.size == TOTAL_CHAPTERS) return null
        val uncovered = Book.entries.filter { b -> (1..b.chapterCount).none { ChapterRef(b, it) in distinct } }
        return buildString {
            append("covers ${distinct.size} of $TOTAL_CHAPTERS chapters")
            val repeats = all.size - distinct.size
            if (repeats > 0) append("; $repeats repeated")
            if (uncovered.isNotEmpty()) append("; books not read: ${uncovered.joinToString(" ") { it.usfm }}")
        }
    }
}
