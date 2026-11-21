package com.openbibleaudio.core.podcast

import com.openbibleaudio.core.model.plan.CycleKey
import com.openbibleaudio.core.model.plan.CycleSelect
import com.openbibleaudio.core.model.plan.PlanPodcast
import java.time.Instant
import java.time.ZoneOffset

/** Why an episode is assigned to a day. Precedence: MANUAL > PLAN_OVERRIDE > TITLE (spec 0003 §7.3). */
enum class MatchSource { TITLE, PLAN_OVERRIDE, MANUAL }

data class Assignment(val item: FeedItem, val source: MatchSource)

/**
 * One cycle of a recurring feed (spec 0003 §7.2): which episode plays on each plan day.
 * [key] is null for a plan without a cycle block (the whole feed is one cycle).
 */
data class Cycle(
    val key: Int?,
    val dayCount: Int,
    val byDay: Map<Int, Assignment>,
    /** Days with more than one title match; the losers (older pubDate) are listed here. */
    val duplicates: Map<Int, List<FeedItem>>,
) {
    val matchedDays: Int get() = byDay.size
    val complete: Boolean get() = matchedDays == dayCount
    val missingDays: List<Int> get() = (1..dayCount).filterNot { it in byDay }
    val totalBytes: Long get() = byDay.values.sumOf { it.item.enclosureLength ?: 0L }

    /** The most common `pubDate` year among the cycle's episodes, e.g. 2025 for TBR Year 7. */
    val year: Int? get() = byDay.values.mapNotNull { a -> a.item.pubDateMs?.let { yearOf(it) } }
        .groupingBy { it }.eachCount().maxWithOrNull(compareBy<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })?.key

    /** This cycle with an enrollment's manual pins (day → GUID) applied on top. */
    fun withPins(pins: Map<Int, String>, items: List<FeedItem>): Cycle {
        if (pins.isEmpty()) return this
        val byGuid = items.associateBy { it.guid }
        val out = byDay.toMutableMap()
        for ((day, guid) in pins) {
            if (day !in 1..dayCount) continue
            val item = byGuid[guid] ?: continue
            out.entries.removeAll { it.value.item.guid == guid && it.key != day }
            out[day] = Assignment(item, MatchSource.MANUAL)
        }
        return copy(byDay = out)
    }
}

object EpisodeMatcher {

    /** The plan day [item] belongs to by title, or null. */
    fun dayOf(item: FeedItem, podcast: PlanPodcast, dayCount: Int): Int? {
        val match = podcast.match
        if (match.episodeType != null && !match.episodeType.equals(item.episodeType, ignoreCase = true)) return null
        val m = Regex(match.titleRegex).find(item.title) ?: return null
        return m.groupValues.getOrNull(match.dayGroup)?.toIntOrNull()?.takeIf { it in 1..dayCount }
    }

    /**
     * The cycle key of [item], or null when it has none (it's then ignored). For a plan without a
     * cycle block every item is in the single cycle.
     */
    fun cycleOf(item: FeedItem, podcast: PlanPodcast): Int? {
        val rule = podcast.cycle ?: return NO_CYCLE
        val primary = when (rule.key) {
            CycleKey.ITUNES_SEASON -> item.season
            CycleKey.PUB_DATE_YEAR -> item.pubDateMs?.let(::yearOf)
            CycleKey.TITLE_REGEX -> rule.titleRegex?.let { group1(it, item.title) }
        }
        return primary ?: rule.fallbackTitleRegex?.let { group1(it, item.title) }
    }

    /**
     * Every cycle in [items], newest key first, with title matches and the plan's overrides
     * applied. Duplicates within a cycle: the latest `pubDate` wins.
     */
    fun catalog(items: List<FeedItem>, podcast: PlanPodcast, dayCount: Int): List<Cycle> {
        val titleMatches = HashMap<Int, HashMap<Int, MutableList<FeedItem>>>() // cycle → day → items
        for (item in items) {
            val day = dayOf(item, podcast, dayCount) ?: continue
            val cycle = cycleOf(item, podcast) ?: continue
            titleMatches.getOrPut(cycle) { HashMap() }.getOrPut(day) { mutableListOf() } += item
        }
        val byGuid = items.associateBy { it.guid }
        val overrides = podcast.overrides.mapNotNull { (key, o) ->
            val parts = key.split('/')
            val (cycle, day) = when {
                podcast.cycle == null && parts.size == 1 -> NO_CYCLE to parts[0].toIntOrNull()
                podcast.cycle != null && parts.size == 2 -> parts[0].toIntOrNull() to parts[1].toIntOrNull()
                else -> return@mapNotNull null
            }
            val item = byGuid[o.guid] ?: return@mapNotNull null
            if (cycle == null || day == null || day !in 1..dayCount) null else Triple(cycle, day, item)
        }
        val keys = (titleMatches.keys + overrides.map { it.first }).toSortedSet().reversed()
        return keys.map { key ->
            val byDay = HashMap<Int, Assignment>()
            val duplicates = HashMap<Int, List<FeedItem>>()
            titleMatches[key]?.forEach { (day, matches) ->
                val sorted = matches.sortedWith(compareByDescending<FeedItem> { it.pubDateMs ?: Long.MIN_VALUE }.thenBy { it.guid })
                byDay[day] = Assignment(sorted.first(), MatchSource.TITLE)
                if (sorted.size > 1) duplicates[day] = sorted.drop(1)
            }
            for ((cycle, day, item) in overrides) {
                if (cycle != key) continue
                byDay.entries.removeAll { it.value.item.guid == item.guid && it.key != day }
                byDay[day] = Assignment(item, MatchSource.PLAN_OVERRIDE)
            }
            Cycle(key.takeUnless { podcast.cycle == null }, dayCount, byDay, duplicates)
        }
    }

    /**
     * The default cycle for a new enrollment (spec 0003 §7.2). For [CycleSelect.LATEST_COMPLETE]
     * with no complete cycle, the one with the most matched days (newest on a tie).
     */
    fun select(catalog: List<Cycle>, select: CycleSelect): Cycle? = when (select) {
        CycleSelect.LATEST -> catalog.firstOrNull()
        CycleSelect.LATEST_COMPLETE -> catalog.firstOrNull { it.complete }
            ?: catalog.maxWithOrNull(compareBy<Cycle> { it.matchedDays }.thenBy { it.key ?: 0 })
    }

    /** Default cycle selection for [podcast]. */
    fun select(catalog: List<Cycle>, podcast: PlanPodcast): Cycle? =
        select(catalog, podcast.cycle?.select ?: CycleSelect.LATEST)

    /** Internal key for the single cycle of a plan without a cycle block. */
    private const val NO_CYCLE = 0

    private fun group1(regex: String, title: String): Int? =
        Regex(regex).find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
}

internal fun yearOf(epochMs: Long): Int = Instant.ofEpochMilli(epochMs).atOffset(ZoneOffset.UTC).year
