package com.openbibleaudio.core.model.plan

import com.openbibleaudio.core.model.Book
import com.openbibleaudio.core.model.ChapterRef
import com.openbibleaudio.core.model.pack.BookUsfmSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A reading plan, format v1 (AD-8, spec 0003 §3.1). Read and write it through [PlanCodec] and
 * check it with [PlanValidator].
 */
@Serializable
data class PlanDefinition(
    val formatVersion: Int,
    val id: String,
    val version: Int,
    val name: String,
    val description: String? = null,
    val source: String? = null,
    val days: List<PlanDay>,
    val podcast: PlanPodcast? = null,
) {
    val dayCount: Int get() = days.size

    /** Day [n] (1-based), or null if out of range. Assumes a validated plan (days are 1..N in order). */
    fun day(n: Int): PlanDay? = days.getOrNull(n - 1)?.takeIf { it.day == n }
}

@Serializable
data class PlanDay(
    val day: Int,
    val readings: List<Reading>,
) {
    /** Every chapter of the day, in listed (not canonical) order. */
    fun chapters(): List<ChapterRef> = readings.flatMap { it.chapters() }
}

/** Whole chapters [from]..[to] of [book]. `to` defaults to `from`. Verse ranges arrive in Phase 4. */
@Serializable
data class Reading(
    @Serializable(with = BookUsfmSerializer::class) val book: Book,
    val from: Int,
    val to: Int? = null,
) {
    val last: Int get() = to ?: from

    /** Empty when the range is invalid; [PlanValidator] reports that. */
    fun chapters(): List<ChapterRef> =
        if (book.hasChapter(from) && book.hasChapter(last) && from <= last) (from..last).map { ChapterRef(book, it) }
        else emptyList()
}

@Serializable
data class PlanPodcast(
    val feedUrl: String,
    val position: PodcastPosition = PodcastPosition.AFTER_READINGS,
    val match: EpisodeMatch,
    val cycle: CycleRule? = null,
    /** `"{cycle}/{day}"`, or `"{day}"` when there's no [cycle] block → the episode to use. */
    val overrides: Map<String, EpisodeOverride> = emptyMap(),
)

@Serializable
enum class PodcastPosition {
    @SerialName("afterReadings") AFTER_READINGS,
    @SerialName("beforeReadings") BEFORE_READINGS,
}

@Serializable
data class EpisodeMatch(
    /** `itunes:episodeType` an episode must have, e.g. `full`. Null = any. */
    val episodeType: String? = null,
    val titleRegex: String,
    val dayGroup: Int = 1,
)

/** How a recurring feed's episodes are grouped into cycles (spec 0003 §3.1, §7.2). */
@Serializable
data class CycleRule(
    val key: CycleKey,
    /** For [CycleKey.TITLE_REGEX]: group 1 is the cycle key. */
    val titleRegex: String? = null,
    /** Tried when an item has no key of the [key] kind; group 1 is the cycle key. */
    val fallbackTitleRegex: String? = null,
    val select: CycleSelect = CycleSelect.LATEST_COMPLETE,
)

@Serializable
enum class CycleKey {
    @SerialName("itunesSeason") ITUNES_SEASON,
    @SerialName("titleRegex") TITLE_REGEX,
    @SerialName("pubDateYear") PUB_DATE_YEAR,
}

@Serializable
enum class CycleSelect {
    @SerialName("latestComplete") LATEST_COMPLETE,
    @SerialName("latest") LATEST,
}

@Serializable
data class EpisodeOverride(val guid: String)
