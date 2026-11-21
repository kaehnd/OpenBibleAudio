package com.openbibleaudio.core.podcast.fixtures

import com.openbibleaudio.core.model.Book
import com.openbibleaudio.core.model.plan.CycleKey
import com.openbibleaudio.core.model.plan.CycleRule
import com.openbibleaudio.core.model.plan.EpisodeMatch
import com.openbibleaudio.core.model.plan.PlanDay
import com.openbibleaudio.core.model.plan.PlanDefinition
import com.openbibleaudio.core.model.plan.PlanPodcast
import com.openbibleaudio.core.model.plan.Reading

/**
 * A synthetic feed shaped like *The Bible Recap* (spec 0003 §2, §10.1), for a 5-day plan.
 * GUIDs and URLs are made up. It reproduces the real feed's quirks:
 *
 * - Year 6 (2024) and Year 7 (2025) complete, Year 8 (2026) partial (days 1–3).
 * - Monthly bonus episodes, a "Prep" episode and a non-audio item.
 * - Year 8 day 2 titled "… - Year 7" but tagged season 8.
 * - A season-less `full` "Day 4 The Bible KNEEcap Preview" with `itunes:episode` 366.
 * - A double space before "Year", zero-padded `itunes:episode` ("003"), `h:mm:ss` durations.
 * - Year 6 day 5 uploaded twice (the later upload wins).
 * - HTML descriptions in CDATA, and an `&amp;` in a title.
 */
object TbrShapedFeed {
    const val FEED_URL = "https://feed.example.com/recap/feed.xml"
    const val DAY_COUNT = 5

    val readings = listOf("Genesis 1-3", "Genesis 4-7", "Genesis 8-11", "Job 1-5", "Job 6-9")

    fun guid(year: Int, day: Int, variant: String = "") = "recap.example.com/y$year-d$day$variant"
    fun url(year: Int, day: Int, variant: String = "") = "https://cdn.example.com/mf/y$year/TBR_${"%03d".format(day)}$variant.mp3"

    val podcast = PlanPodcast(
        feedUrl = FEED_URL,
        match = EpisodeMatch(episodeType = "full", titleRegex = "^Day (\\d{1,3}) \\(", dayGroup = 1),
        cycle = CycleRule(CycleKey.ITUNES_SEASON, fallbackTitleRegex = "-\\s*Year\\s+(\\d+)\\s*$"),
    )

    val plan = PlanDefinition(
        formatVersion = 1,
        id = "recap-mini",
        version = 1,
        name = "Recap (mini)",
        days = listOf(
            PlanDay(1, listOf(Reading(Book.GENESIS, 1, 3))),
            PlanDay(2, listOf(Reading(Book.GENESIS, 4, 7))),
            PlanDay(3, listOf(Reading(Book.GENESIS, 8, 11))),
            PlanDay(4, listOf(Reading(Book.JOB, 1, 5))),
            PlanDay(5, listOf(Reading(Book.JOB, 6, 9))),
        ),
        podcast = podcast,
    )

    /** The feed, newest item first like the real one. */
    val xml: String by lazy { build() }

    private data class Item(
        val title: String,
        val pubDate: String,
        val guid: String,
        val url: String?,
        val type: String = "audio/mpeg",
        val length: Long = 7_000_000,
        val duration: String = "468",
        val episodeType: String? = "full",
        val season: Int? = null,
        val episode: String? = null,
    )

    private fun daily(year: Int, day: Int, titleYear: Int = year, spacing: String = " ", variant: String = "", pubHour: Int = 1): Item {
        val calYear = 2018 + year
        val date = java.time.LocalDate.of(calYear, 1, 1).plusDays(day - 1L)
        val dow = date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)
        val mon = date.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)
        return Item(
            title = "Day ${"%03d".format(day)} (${readings[day - 1]}) -$spacing" + "Year $titleYear",
            pubDate = "$dow, ${"%02d".format(date.dayOfMonth)} $mon $calYear ${"%02d".format(pubHour)}:00:00 -0600",
            guid = guid(year, day, variant),
            url = url(year, day, variant),
            season = year,
            episode = if (day == 3) "003" else day.toString(),
            duration = if (day == 4) "0:07:48" else "468",
        )
    }

    private fun build(): String {
        val items = mutableListOf<Item>()
        // Year 8 (partial): day 2 is titled "Year 7" but tagged season 8; day 3 has a double space.
        items += daily(8, 3, spacing = "  ")
        items += daily(8, 2, titleYear = 7)
        items += Item("January Reflections & Corrections - Year 8", "Sat, 31 Jan 2026 01:10:00 -0600", "recap.example.com/bonus-jan-8", "https://cdn.example.com/bonus8.mp3", episodeType = "bonus", season = 8)
        items += daily(8, 1)
        items += Item("Prep for Next Year - 2026", "Thu, 18 Dec 2025 01:30:00 -0600", "recap.example.com/prep-2026", "https://cdn.example.com/prep.mp3", episodeType = "bonus", season = 7)
        for (d in 5 downTo 1) items += daily(7, d)
        items += Item("Day 004 The Bible KNEEcap Preview", "Tue, 01 Oct 2024 09:42:13 -0600", "recap.example.com/kneecap", "https://cdn.example.com/kneecap.mp3", episodeType = "full", episode = "366")
        items += Item("Show notes (not audio)", "Mon, 30 Sep 2024 01:00:00 -0600", "recap.example.com/pdf", "https://cdn.example.com/notes.pdf", type = "application/pdf", season = 6)
        items += Item("No enclosure", "Mon, 30 Sep 2024 01:00:00 -0600", "recap.example.com/none", null, season = 6)
        items += daily(6, 5, variant = "-reupload", pubHour = 9)
        for (d in 5 downTo 1) items += daily(6, d)
        return buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
            append("""<rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd" xmlns:atom="http://www.w3.org/2005/Atom">""").append('\n')
            append("<channel>\n<title>The Recap (fixture)</title>\n")
            append("""<atom:link href="$FEED_URL" rel="self" type="application/rss+xml"/>""").append('\n')
            append("<description><![CDATA[<p>A <em>daily</em> recap &nbsp; fixture.</p>]]></description>\n")
            append("<image><url>https://cdn.example.com/logo.png</url><title>Not the channel title</title></image>\n")
            for (it in items) {
                append("<item>\n")
                append("  <title>${it.title.replace("&", "&amp;")}</title>\n")
                append("  <itunes:title>${it.title.replace("&", "&amp;")}</itunes:title>\n")
                append("  <pubDate>${it.pubDate}</pubDate>\n")
                append("  <guid isPermaLink=\"false\">${it.guid}</guid>\n")
                append("  <description><![CDATA[<p>FROM the <b>recap</b> &amp; more <a href=\"x\">link</a></p>]]></description>\n")
                if (it.url != null) append("  <enclosure url=\"${it.url}\" length=\"${it.length}\" type=\"${it.type}\"/>\n")
                append("  <itunes:duration>${it.duration}</itunes:duration>\n")
                it.episodeType?.let { t -> append("  <itunes:episodeType>$t</itunes:episodeType>\n") }
                it.season?.let { s -> append("  <itunes:season>$s</itunes:season>\n") }
                it.episode?.let { e -> append("  <itunes:episode>$e</itunes:episode>\n") }
                append("</item>\n")
            }
            append("</channel>\n</rss>\n")
        }
    }
}
