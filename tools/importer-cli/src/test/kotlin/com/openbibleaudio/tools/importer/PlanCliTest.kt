package com.openbibleaudio.tools.importer

import com.openbibleaudio.core.model.Book
import com.openbibleaudio.core.model.plan.PlanCodec
import com.openbibleaudio.core.model.plan.Reading
import com.openbibleaudio.core.podcast.fixtures.TbrShapedFeed
import org.junit.After
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlanCliTest {
    private val dir: Path = Files.createTempDirectory("oba-plan-cli")
    private val feed: Path = dir.resolve("feed.xml").also { it.writeText(TbrShapedFeed.xml) }
    private val planFile: Path = dir.resolve("plan.json")

    @After
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    private data class Run(val code: Int, val out: String, val err: String)

    private fun cli(vararg args: String): Run {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = runCli(args.toList(), PrintStream(out, true, "UTF-8"), PrintStream(err, true, "UTF-8"))
        return Run(code, out.toString("UTF-8"), err.toString("UTF-8"))
    }

    private fun fromFeed(feedPath: Path = feed, vararg extra: String) = cli(
        "plan", "from-feed", feedPath.toString(), "--feed-url", TbrShapedFeed.FEED_URL,
        "--id", "recap-mini", "--name", "Recap (mini)",
        "--match-title", "^Day (\\d{1,3}) \\((.+)\\)", "--readings-group", "2", "--episode-type", "full",
        "--cycle", "itunesSeason", "--fallback-cycle-regex", "-\\s*Year\\s+(\\d+)\\s*$",
        *extra,
    )

    @Test
    fun fromFeedBuildsThePlanFromTheLatestCompleteCycle() {
        val r = fromFeed(extra = arrayOf("--out", planFile.toString()))
        assertEquals(0, r.code, r.out + r.err)
        assertTrue("Readings from cycle 7 [2025] (5/5)" in r.out, r.out)
        assertTrue("Cycle 8 [2026] (3/5)" in r.out, r.out)
        val plan = PlanCodec.decode(planFile.readText())
        assertEquals(5, plan.dayCount)
        assertEquals(listOf(Reading(Book.GENESIS, 4, 7)), plan.day(2)!!.readings)
        assertEquals(listOf(Reading(Book.JOB, 6, 9)), plan.day(5)!!.readings)
        assertEquals(TbrShapedFeed.FEED_URL, plan.podcast!!.feedUrl)
        assertEquals("full", plan.podcast!!.match.episodeType)

        // Deterministic, and refuses to overwrite without --force.
        val first = planFile.readBytes()
        assertEquals(ExitCode.USAGE_OR_IO, fromFeed(extra = arrayOf("--out", planFile.toString())).code)
        assertEquals(0, fromFeed(extra = arrayOf("--out", planFile.toString(), "--force")).code)
        assertContentEquals(first, planFile.readBytes())
    }

    @Test
    fun fromFeedFailsWhenCompleteCyclesDisagree() {
        val changed = dir.resolve("changed.xml")
        changed.writeText(TbrShapedFeed.xml.replace("Day 002 (Genesis 4-7) - Year 6", "Day 002 (Genesis 4-8) - Year 6"))
        val r = fromFeed(changed)
        assertEquals(ExitCode.VALIDATION_ERRORS, r.code, r.out)
        assertTrue("Cycle 6 disagrees with cycle 7 on days 2" in r.out, r.out)
    }

    @Test
    fun fromFeedNeedsAFeedUrlForFiles() {
        val r = cli("plan", "from-feed", feed.toString(), "--id", "x", "--name", "X", "--match-title", "^Day (\\d+) \\((.+)\\)")
        assertEquals(ExitCode.USAGE_OR_IO, r.code)
    }

    @Test
    fun validateReportsCoverageAndDisagreements() {
        assertEquals(0, fromFeed(extra = arrayOf("--out", planFile.toString())).code)
        val ok = cli("plan", "validate", planFile.toString(), "--feed", feed.toString(), "--readings-group", "2")
        assertEquals(0, ok.code, ok.out + ok.err)
        assertTrue("New enrollments would use cycle 7 [2025] (5/5)" in ok.out, ok.out)
        assertTrue("cycle 8: unmatched days 4-5" in ok.out, ok.out)
        assertTrue("cycle 6: duplicate days 5" in ok.out, ok.out)
        assertTrue("0 reading disagreements" in ok.out, ok.out)

        planFile.writeText(planFile.readText().replaceFirst("\"to\": 3", "\"to\": 4"))
        val bad = cli("plan", "validate", planFile.toString(), "--feed", feed.toString(), "--readings-group", "2")
        assertEquals(ExitCode.VALIDATION_ERRORS, bad.code, bad.out)
        assertTrue("disagree with the plan on days 1" in bad.out, bad.out)
    }

    @Test
    fun validateExitCodes() {
        planFile.writeText("""{"formatVersion":1,"id":"Bad","version":1,"name":"P","days":[]}""")
        assertEquals(ExitCode.VALIDATION_ERRORS, cli("plan", "validate", planFile.toString()).code)
        planFile.writeText("not json")
        assertEquals(ExitCode.VALIDATION_ERRORS, cli("plan", "validate", planFile.toString()).code)
        assertEquals(ExitCode.USAGE_OR_IO, cli("plan", "validate", dir.resolve("missing.json").toString()).code)
    }

    @Test
    fun committedBibleRecapPlanIsValid() {
        val file = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .map { it.resolve("plans/bible-recap-chronological.json") }
            .first { Files.exists(it) }
        val r = cli("plan", "validate", file.toString())
        assertEquals(0, r.code, r.out)
        assertTrue("bible-recap-chronological v1: 365 days, 1189 chapters (1189 distinct), 0 errors, 0 warnings" in r.out, r.out)
    }
}
