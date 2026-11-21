package com.openbibleaudio.core.podcast

import com.openbibleaudio.core.model.plan.PlanCodec
import com.openbibleaudio.core.model.plan.ReadingListParser
import com.openbibleaudio.core.podcast.fixtures.TbrShapedFeed
import org.junit.Assume.assumeTrue
import org.kxml2.io.KXmlParser
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Opt-in test against a saved copy of the real Bible Recap feed (spec 0003 §10.1). Skipped
 * unless a path is given:
 *
 * ```
 * ./gradlew :core:podcast:test --tests "*TbrFeedTest*" -Poba.feed.tbr=<abs path>/feed.xml \
 *     [-Poba.plan.tbr=<abs path>/plans/bible-recap-chronological.json]
 * ```
 */
class TbrFeedTest {
    private val feedFile = (System.getProperty("oba.feed.tbr") ?: System.getenv("OBA_FEED_TBR"))?.let(::File)

    @Test
    fun latestCompleteCycleHasEveryDayAndReadingsAgree() {
        assumeTrue("set -Poba.feed.tbr to run", feedFile != null)
        val feed = feedFile!!.inputStream().use { FeedParser.parse(it, KXmlParser()) }
        val podcast = TbrShapedFeed.podcast.copy(feedUrl = "https://feed.podbean.com/thebiblerecap/feed.xml")
        val catalog = EpisodeMatcher.catalog(feed.items, podcast, 365)
        val selected = assertNotNull(EpisodeMatcher.select(catalog, podcast))
        println("TBR feed: ${feed.items.size} items; cycles " + catalog.joinToString { "${it.key}=${it.matchedDays}" } +
            "; selected ${selected.key} (${selected.year}), ${selected.totalBytes / 1_000_000} MB")
        assertTrue(selected.complete, "missing days ${selected.missingDays}")

        val readingsPattern = Regex("^Day \\d{1,3} \\((.+)\\)")
        fun readings(c: Cycle) = (1..365).associateWith { day ->
            val title = c.byDay.getValue(day).item.title
            ReadingListParser.parse(readingsPattern.find(title)!!.groupValues[1])
        }
        val expected = readings(selected)
        for (c in catalog.filter { it.complete }) assertEquals(expected, readings(c), "cycle ${c.key} disagrees")

        val planFile = (System.getProperty("oba.plan.tbr") ?: System.getenv("OBA_PLAN_TBR"))?.let(::File) ?: return
        val plan = PlanCodec.decode(planFile.readText())
        for (day in 1..365) assertEquals(expected.getValue(day), plan.day(day)!!.readings, "day $day")
    }
}
