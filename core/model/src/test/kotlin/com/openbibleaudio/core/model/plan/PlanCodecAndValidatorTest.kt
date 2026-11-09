package com.openbibleaudio.core.model.plan

import com.openbibleaudio.core.model.Book
import com.openbibleaudio.core.model.pack.IssueCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlanCodecAndValidatorTest {
    private val tbrLike = """
        {
          "formatVersion": 1,
          "id": "bible-recap-chronological",
          "version": 1,
          "name": "The Bible Recap (Chronological)",
          "days": [
            { "day": 1, "readings": [ { "book": "GEN", "from": 1, "to": 3 } ] },
            { "day": 2, "readings": [ { "book": "NUM", "from": 14, "to": 15 }, { "book": "PSA", "from": 90 } ] }
          ],
          "podcast": {
            "feedUrl": "https://feed.example.com/feed.xml",
            "match": { "episodeType": "full", "titleRegex": "^Day (\\d{1,3}) \\(", "dayGroup": 1 },
            "cycle": { "key": "itunesSeason", "fallbackTitleRegex": "-\\s*Year\\s+(\\d+)\\s*${'$'}" },
            "overrides": { "7/2": { "guid": "abc" } },
            "someFutureField": true
          }
        }
    """.trimIndent()

    private fun plan(days: List<PlanDay>, podcast: PlanPodcast? = null) =
        PlanDefinition(1, "p", 1, "P", days = days, podcast = podcast)

    private fun codes(p: PlanDefinition) = PlanValidator.validate(p).issues.map { it.code }.toSet()

    @Test
    fun decodesWithDefaultsAndRoundTrips() {
        val p = PlanCodec.decode(tbrLike)
        assertEquals(2, p.dayCount)
        assertEquals(listOf(Reading(Book.NUMBERS, 14, 15), Reading(Book.PSALMS, 90)), p.day(2)!!.readings)
        val podcast = p.podcast!!
        assertEquals(PodcastPosition.AFTER_READINGS, podcast.position)
        assertEquals(CycleKey.ITUNES_SEASON, podcast.cycle!!.key)
        assertEquals(CycleSelect.LATEST_COMPLETE, podcast.cycle!!.select)
        assertEquals("abc", podcast.overrides["7/2"]!!.guid)

        val encoded = PlanCodec.encode(p)
        assertEquals(p, PlanCodec.decode(encoded))
        assertEquals(encoded, PlanCodec.encode(PlanCodec.decode(encoded)))
        assertFalse("afterReadings" in encoded) // defaults omitted
    }

    @Test
    fun readableDecodeErrors() {
        assertTrue("newer version" in assertFailsWith<PlanFormatException> {
            PlanCodec.decode("""{"formatVersion": 2}""")
        }.message!!)
        assertTrue("days[0].readings[0].book" in assertFailsWith<PlanFormatException> {
            PlanCodec.decode("""{"formatVersion":1,"id":"p","version":1,"name":"P","days":[{"day":1,"readings":[{"book":"XYZ","from":1}]}]}""")
        }.message!!)
        assertTrue("verse ranges" in assertFailsWith<PlanFormatException> {
            PlanCodec.decode("""{"formatVersion":1,"id":"p","version":1,"name":"P","days":[{"day":1,"readings":[{"book":"GEN","from":"1:1"}]}]}""")
        }.message!!)
        assertFailsWith<PlanFormatException> { PlanCodec.decode("[]") }
        assertFailsWith<PlanFormatException> { PlanCodec.decode("{") }
    }

    @Test
    fun validPlanHasNoErrors() {
        val report = PlanValidator.validate(PlanCodec.decode(tbrLike))
        assertEquals(emptyList(), report.errors)
        assertEquals(setOf(IssueCode.PARTIAL_COVERAGE), report.issues.map { it.code }.toSet())
    }

    @Test
    fun wholeBibleOnceHasNoCoverageNote() {
        val days = Book.entries.mapIndexed { i, b -> PlanDay(i + 1, listOf(Reading(b, 1, b.chapterCount))) }
        assertEquals(emptyList(), PlanValidator.validate(plan(days)).issues)
    }

    @Test
    fun structuralErrors() {
        val gen = Reading(Book.GENESIS, 1)
        assertTrue(IssueCode.EMPTY_PLAN in codes(plan(emptyList())))
        assertTrue(IssueCode.BAD_DAY_NUMBER in codes(plan(listOf(PlanDay(2, listOf(gen))))))
        assertTrue(IssueCode.BAD_DAY_NUMBER in codes(plan(listOf(PlanDay(1, listOf(gen)), PlanDay(1, listOf(gen))))))
        assertTrue(IssueCode.EMPTY_DAY in codes(plan(listOf(PlanDay(1, emptyList())))))
        assertTrue(IssueCode.NO_SUCH_CHAPTER in codes(plan(listOf(PlanDay(1, listOf(Reading(Book.GENESIS, 1, 51)))))))
        assertTrue(IssueCode.BAD_RANGE in codes(plan(listOf(PlanDay(1, listOf(Reading(Book.GENESIS, 5, 3)))))))
        val d = listOf(PlanDay(1, listOf(gen)))
        assertTrue(IssueCode.BAD_PLAN_ID in codes(PlanDefinition(1, "Bad ID", 1, "P", days = d)))
        assertTrue(IssueCode.BAD_PLAN_VERSION in codes(PlanDefinition(1, "p", 0, "P", days = d)))
        assertTrue(IssueCode.BAD_FIELD_LENGTH in codes(PlanDefinition(1, "p", 1, " ", days = d)))
        assertTrue(IssueCode.UNSUPPORTED_FORMAT in codes(PlanDefinition(2, "p", 1, "P", days = d)))
    }

    @Test
    fun podcastErrors() {
        val d = listOf(PlanDay(1, listOf(Reading(Book.GENESIS, 1))))
        fun pc(
            url: String = "https://x.example/feed",
            regex: String = "^Day (\\d+)",
            group: Int = 1,
            cycle: CycleRule? = null,
            overrides: Map<String, EpisodeOverride> = emptyMap(),
        ) = codes(plan(d, PlanPodcast(url, match = EpisodeMatch(titleRegex = regex, dayGroup = group), cycle = cycle, overrides = overrides)))

        assertFalse(pc().any { it.severity == com.openbibleaudio.core.model.pack.Severity.ERROR })
        assertTrue(IssueCode.BAD_FEED_URL in pc(url = "http://x.example/feed"))
        assertTrue(IssueCode.BAD_REGEX in pc(regex = "^Day ("))
        assertTrue(IssueCode.BAD_REGEX in pc(group = 2))
        assertTrue(IssueCode.BAD_REGEX in pc(cycle = CycleRule(CycleKey.TITLE_REGEX)))
        assertTrue(IssueCode.BAD_REGEX in pc(cycle = CycleRule(CycleKey.ITUNES_SEASON, fallbackTitleRegex = "Year")))
        assertTrue(IssueCode.BAD_OVERRIDE in pc(overrides = mapOf("7/1" to EpisodeOverride("g")))) // no cycle → "{day}"
        assertTrue(IssueCode.BAD_OVERRIDE in pc(overrides = mapOf("2" to EpisodeOverride("g")))) // day out of range
        assertTrue(IssueCode.BAD_OVERRIDE in pc(overrides = mapOf("1" to EpisodeOverride(" "))))
        assertFalse(IssueCode.BAD_OVERRIDE in pc(overrides = mapOf("1" to EpisodeOverride("g"))))
        val cyc = CycleRule(CycleKey.ITUNES_SEASON)
        assertFalse(IssueCode.BAD_OVERRIDE in pc(cycle = cyc, overrides = mapOf("7/1" to EpisodeOverride("g"))))
        assertTrue(IssueCode.BAD_OVERRIDE in pc(cycle = cyc, overrides = mapOf("1" to EpisodeOverride("g"))))
    }
}
