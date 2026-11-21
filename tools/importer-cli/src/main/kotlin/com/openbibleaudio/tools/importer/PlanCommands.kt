package com.openbibleaudio.tools.importer

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import com.openbibleaudio.core.model.plan.CycleKey
import com.openbibleaudio.core.model.plan.CycleRule
import com.openbibleaudio.core.model.plan.CycleSelect
import com.openbibleaudio.core.model.plan.EpisodeMatch
import com.openbibleaudio.core.model.plan.PlanCodec
import com.openbibleaudio.core.model.plan.PlanDefinition
import com.openbibleaudio.core.model.plan.PlanFormatException
import com.openbibleaudio.core.model.plan.PlanValidator
import com.openbibleaudio.core.model.plan.ReadingListParser
import com.openbibleaudio.core.podcast.Cycle
import com.openbibleaudio.core.podcast.EpisodeMatcher
import com.openbibleaudio.core.podcast.Feed
import com.openbibleaudio.core.podcast.FeedFormatException
import com.openbibleaudio.core.podcast.FeedParser
import com.openbibleaudio.core.podcast.PlanFromFeed
import org.kxml2.io.KXmlParser
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.readText

/** `plan from-feed` and `plan validate` (spec 0003 §4). */
internal class PlanCommand(io: CliIo) : CliktCommand(name = "plan") {
    init {
        subcommands(PlanFromFeedCommand(io), PlanValidateCommand(io))
    }

    override fun help(context: Context) = "Build and check reading plan files."
    override fun run() = Unit
}

private val CYCLE_KEYS = mapOf(
    "itunesSeason" to CycleKey.ITUNES_SEASON,
    "titleRegex" to CycleKey.TITLE_REGEX,
    "pubDateYear" to CycleKey.PUB_DATE_YEAR,
)

private class PlanFromFeedCommand(private val io: CliIo) : CliktCommand(name = "from-feed") {
    override fun help(context: Context) =
        "Build a plan whose daily readings come from podcast episode titles, e.g. 'Day 001 (Genesis 1-3) - Year 7'."

    val source by argument(help = "Feed URL (https://…) or a saved feed.xml")
    val id by option("--id", help = "Plan id, e.g. bible-recap-chronological").required()
    val name by option("--name").required()
    val planVersion by option("--plan-version", help = "Plan version (default 1)").int().default(1)
    val description by option("--description")
    val sourceUrl by option("--source-url", help = "Publisher URL shown in About")
    val feedUrl by option("--feed-url", help = "Feed URL to store in the plan (required when SOURCE is a file)")
    val matchTitle by option("--match-title", help = "Title regex with a day group and a readings group").required()
    val dayGroup by option("--day-group").int().default(1)
    val readingsGroup by option("--readings-group").int().default(2)
    val episodeType by option("--episode-type", help = "Required itunes:episodeType, e.g. full")
    val cycle by option("--cycle", help = "How episodes group into cycles (default: one cycle)")
        .choice(*CYCLE_KEYS.keys.toTypedArray(), "none").default("none")
    val cycleRegex by option("--cycle-regex", help = "For --cycle titleRegex: group 1 is the cycle key")
    val fallbackCycleRegex by option("--fallback-cycle-regex", help = "Cycle key from the title when the item has none")
    val select by option("--select").choice("latestComplete", "latest").default("latestComplete")
    val out by option("--out", help = "Where to write the plan (default: print it)").path()
    val force by option("--force", help = "Overwrite an existing --out file").flag()

    override fun run() {
        val isUrl = source.startsWith("https://") || source.startsWith("http://")
        val storedFeedUrl = feedUrl ?: if (isUrl) source else throw UsageError("--feed-url is required when SOURCE is a file")
        val target = out
        if (target != null && target.exists() && !force) {
            io.err.println("$target already exists; pass --force to overwrite it")
            throw ProgramResult(ExitCode.USAGE_OR_IO)
        }
        val feed = loadFeed(io, source)
        val params = PlanFromFeed.Params(
            id = id,
            name = name,
            version = planVersion,
            description = description,
            source = sourceUrl,
            feedUrl = storedFeedUrl,
            match = EpisodeMatch(episodeType = episodeType, titleRegex = matchTitle, dayGroup = dayGroup),
            readingsGroup = readingsGroup,
            cycle = CYCLE_KEYS[cycle]?.let {
                CycleRule(
                    key = it,
                    titleRegex = cycleRegex,
                    fallbackTitleRegex = fallbackCycleRegex,
                    select = if (select == "latest") CycleSelect.LATEST else CycleSelect.LATEST_COMPLETE,
                )
            },
        )
        val result = PlanFromFeed.build(feed.items, params)
        printCycles(io, result.catalog)
        when (result) {
            is PlanFromFeed.Result.Failed -> {
                result.problems.forEach { io.out.println("error: $it") }
                throw ProgramResult(ExitCode.VALIDATION_ERRORS)
            }
            is PlanFromFeed.Result.Ok -> {
                val report = PlanValidator.validate(result.plan)
                Reporting.printIssues(io.out, report.issues)
                if (report.hasErrors) throw ProgramResult(ExitCode.VALIDATION_ERRORS)
                io.out.println("Readings from cycle ${label(result.from)}: ${planSummary(result.plan)}")
                val text = PlanCodec.encode(result.plan)
                if (target == null) {
                    io.out.print(text)
                } else {
                    try {
                        writeAtomically(target, text)
                    } catch (e: IOException) {
                        io.err.println("Couldn't write $target: ${e.message}")
                        throw ProgramResult(ExitCode.USAGE_OR_IO)
                    }
                    io.out.println("Wrote $target")
                }
            }
        }
    }
}

private class PlanValidateCommand(private val io: CliIo) : CliktCommand(name = "validate") {
    override fun help(context: Context) = "Validate a plan file, and optionally check its podcast rules against a feed."

    val planFile by argument().path(mustExist = true, canBeDir = false, mustBeReadable = true)
    val feed by option("--feed", help = "Feed URL or saved feed.xml to match episodes against")
    val readingsGroup by option(
        "--readings-group",
        help = "Also compare the plan's readings with this capture group of the title regex",
    ).int()

    override fun run() {
        val plan = readPlan(planFile)
        val report = PlanValidator.validate(plan)
        Reporting.printIssues(io.out, report.issues)
        io.out.println("${plan.id} v${plan.version}: ${planSummary(plan)}, ${Reporting.counts(report.issues)}")
        if (report.hasErrors) throw ProgramResult(ExitCode.VALIDATION_ERRORS)

        val feedSource = feed ?: return
        val podcast = plan.podcast ?: throw UsageError("the plan has no podcast block to check --feed against")
        val items = loadFeed(io, feedSource).items
        val catalog = EpisodeMatcher.catalog(items, podcast, plan.dayCount)
        printCycles(io, catalog)
        val selected = EpisodeMatcher.select(catalog, podcast)
        io.out.println("New enrollments would use cycle ${selected?.let(::label) ?: "(none)"}")
        for (c in catalog) {
            if (c.missingDays.isNotEmpty()) io.out.println("  cycle ${c.key}: unmatched days ${PlanFromFeed.abbreviate(c.missingDays)}")
            if (c.duplicates.isNotEmpty()) io.out.println("  cycle ${c.key}: duplicate days ${PlanFromFeed.abbreviate(c.duplicates.keys.toList())}")
        }

        val group = readingsGroup ?: return
        val regex = Regex(podcast.match.titleRegex)
        var disagreements = 0
        for (c in catalog) {
            val days = c.byDay.filter { (day, a) ->
                val text = regex.find(a.item.title)?.groupValues?.getOrNull(group)
                text?.let(ReadingListParser::parse) != plan.day(day)?.readings
            }.keys
            if (days.isNotEmpty()) {
                disagreements += days.size
                io.out.println("error: cycle ${c.key} titles disagree with the plan on days ${PlanFromFeed.abbreviate(days.toList())}")
            }
        }
        io.out.println("$disagreements reading disagreements")
        if (disagreements > 0) throw ProgramResult(ExitCode.VALIDATION_ERRORS)
    }

    private fun readPlan(file: Path): PlanDefinition = try {
        PlanCodec.decode(file.readText())
    } catch (e: IOException) {
        io.err.println("Couldn't read $file: ${e.message}")
        throw ProgramResult(ExitCode.USAGE_OR_IO)
    } catch (e: PlanFormatException) {
        io.out.println("error: ${e.message}")
        throw ProgramResult(ExitCode.VALIDATION_ERRORS)
    }
}

private fun label(c: Cycle): String = buildString {
    append(c.key ?: "(single)")
    c.year?.let { append(" [$it]") }
    append(" (${c.matchedDays}/${c.dayCount})")
}

private fun printCycles(io: CliIo, catalog: List<Cycle>) {
    for (c in catalog) {
        io.out.println("Cycle ${label(c)}${if (c.complete) ", complete" else ""}, ${Reporting.size(c.totalBytes)}")
    }
}

private fun planSummary(plan: PlanDefinition): String {
    val chapters = plan.days.flatMap { it.chapters() }
    return "${plan.dayCount} days, ${chapters.size} chapters (${chapters.toSet().size} distinct)"
}

/** Reads a feed from a URL or a file. Exits with USAGE_OR_IO on failure. */
private fun loadFeed(io: CliIo, source: String): Feed {
    try {
        val parse = { input: InputStream -> input.use { FeedParser.parse(it, KXmlParser()) } }
        if (!(source.startsWith("https://") || source.startsWith("http://"))) return parse(Path.of(source).inputStream())
        val client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build()
        val request = HttpRequest.newBuilder(URI.create(source))
            .header("User-Agent", "$TOOL_NAME/$TOOL_VERSION")
            .timeout(Duration.ofSeconds(120))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() != 200) {
            response.body().close()
            io.err.println("Couldn't fetch $source: HTTP ${response.statusCode()}")
            throw ProgramResult(ExitCode.USAGE_OR_IO)
        }
        return parse(response.body())
    } catch (e: FeedFormatException) {
        io.err.println("Couldn't read the feed: ${e.message}")
        throw ProgramResult(ExitCode.USAGE_OR_IO)
    } catch (e: IOException) {
        io.err.println("Couldn't read $source: ${e.message}")
        throw ProgramResult(ExitCode.USAGE_OR_IO)
    } catch (e: InterruptedException) {
        throw ProgramResult(ExitCode.USAGE_OR_IO)
    }
}
