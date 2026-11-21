package com.openbibleaudio.core.podcast

import com.openbibleaudio.core.model.plan.CycleRule
import com.openbibleaudio.core.model.plan.EpisodeMatch
import com.openbibleaudio.core.model.plan.PlanCodec
import com.openbibleaudio.core.model.plan.PlanDay
import com.openbibleaudio.core.model.plan.PlanDefinition
import com.openbibleaudio.core.model.plan.PlanPodcast
import com.openbibleaudio.core.model.plan.Reading
import com.openbibleaudio.core.model.plan.ReadingListParser

/**
 * Builds a plan whose readings come from episode titles, e.g. `Day 001 (Genesis 1-3) - Year 7`
 * (spec 0003 §4). Readings are taken from the default cycle (normally the latest complete one),
 * and every other complete cycle must agree with it day for day.
 */
object PlanFromFeed {
    data class Params(
        val id: String,
        val name: String,
        val version: Int = 1,
        val description: String? = null,
        val source: String? = null,
        val feedUrl: String,
        val match: EpisodeMatch,
        /** Capture group of [EpisodeMatch.titleRegex] holding the reading list. */
        val readingsGroup: Int,
        val cycle: CycleRule?,
    )

    sealed interface Result {
        val catalog: List<Cycle>

        data class Ok(val plan: PlanDefinition, val from: Cycle, override val catalog: List<Cycle>) : Result

        /** [problems] are human-readable, one per line. */
        data class Failed(val problems: List<String>, override val catalog: List<Cycle>) : Result
    }

    /** The highest plan day any title in [items] names (titles are matched up to day 9999). */
    fun dayCount(items: List<FeedItem>, podcast: PlanPodcast): Int =
        items.mapNotNull { EpisodeMatcher.dayOf(it, podcast, 9999) }.maxOrNull() ?: 0

    fun build(items: List<FeedItem>, params: Params): Result {
        val podcast = PlanPodcast(feedUrl = params.feedUrl, match = params.match, cycle = params.cycle)
        val dayCount = dayCount(items, podcast)
        if (dayCount == 0) return Result.Failed(listOf("No episode title matches ${params.match.titleRegex}"), emptyList())
        val catalog = EpisodeMatcher.catalog(items, podcast, dayCount)
        val from = EpisodeMatcher.select(catalog, podcast)
            ?: return Result.Failed(listOf("No cycle has any matching episodes"), catalog)
        val problems = mutableListOf<String>()
        if (!from.complete) problems += "Cycle ${from.key} is missing days ${abbreviate(from.missingDays)}"

        val readings = readingsOf(from, params, problems)
        for (other in catalog) {
            if (other === from || !other.complete) continue
            val theirs = readingsOf(other, params, mutableListOf())
            val disagree = (1..dayCount).filter { theirs[it] != readings[it] }
            if (disagree.isNotEmpty()) problems += "Cycle ${other.key} disagrees with cycle ${from.key} on days ${abbreviate(disagree)}"
        }
        if (problems.isNotEmpty()) return Result.Failed(problems, catalog)

        val plan = PlanDefinition(
            formatVersion = PlanCodec.SUPPORTED_FORMAT_VERSION,
            id = params.id,
            version = params.version,
            name = params.name,
            description = params.description,
            source = params.source,
            days = (1..dayCount).map { PlanDay(it, readings.getValue(it)) },
            podcast = podcast,
        )
        return Result.Ok(plan, from, catalog)
    }

    /** Day → readings for [cycle]; unparseable titles are added to [problems]. */
    private fun readingsOf(cycle: Cycle, params: Params, problems: MutableList<String>): Map<Int, List<Reading>> {
        val regex = Regex(params.match.titleRegex)
        return cycle.byDay.mapNotNull { (day, a) ->
            val text = regex.find(a.item.title)?.groupValues?.getOrNull(params.readingsGroup)
            val parsed = text?.let(ReadingListParser::parse)
            if (parsed == null) {
                problems += "Day $day: can't read the readings in \"${a.item.title}\""
                null
            } else {
                day to parsed
            }
        }.toMap()
    }

    /** `1, 2, 5-9, 12` style list, at most 20 entries. */
    fun abbreviate(days: List<Int>): String {
        val ranges = mutableListOf<String>()
        var i = 0
        val sorted = days.sorted()
        while (i < sorted.size) {
            var j = i
            while (j + 1 < sorted.size && sorted[j + 1] == sorted[j] + 1) j++
            ranges += if (j > i) "${sorted[i]}-${sorted[j]}" else "${sorted[i]}"
            i = j + 1
        }
        return if (ranges.size > 20) ranges.take(20).joinToString(", ") + ", …" else ranges.joinToString(", ")
    }
}
