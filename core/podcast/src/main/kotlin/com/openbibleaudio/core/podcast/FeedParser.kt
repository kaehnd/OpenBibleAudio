package com.openbibleaudio.core.podcast

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** One `<item>` with an audio enclosure (spec 0003 §7.1). */
data class FeedItem(
    /** `<guid>`, or the enclosure URL when the item has none. */
    val guid: String,
    val title: String,
    /** Epoch ms, or null when `pubDate` is missing or unparseable. */
    val pubDateMs: Long?,
    val enclosureUrl: String,
    val enclosureLength: Long?,
    val enclosureType: String?,
    val durationMs: Long?,
    /** `itunes:episodeType`: `full`, `bonus`, `trailer`, … */
    val episodeType: String?,
    /** `itunes:season`. */
    val season: Int?,
    /** `itunes:episode`. */
    val episode: Int?,
)

data class Feed(val title: String?, val items: List<FeedItem>)

class FeedFormatException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Stream-parses an RSS 2.0 podcast feed with the iTunes namespace. It reads only the fields
 * plan matching needs and skips everything else (HTML descriptions, chapters, …).
 *
 * Takes the [XmlPullParser] to use, so it runs over Android's built-in parser on the device and
 * kxml2 on the JVM.
 */
object FeedParser {
    const val ITUNES_NS = "http://www.itunes.com/dtds/podcast-1.0.dtd"
    const val DEFAULT_MAX_BYTES = 32L * 1024 * 1024

    fun parse(input: InputStream, parser: XmlPullParser, maxBytes: Long = DEFAULT_MAX_BYTES): Feed {
        try {
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            // Strict XML on purpose: kxml's relaxed mode closes open tags at end of input, which
            // would hide a truncated download (see readFeed).
            parser.setInput(LimitedInputStream(input, maxBytes), null)
            return readFeed(parser)
        } catch (e: XmlPullParserException) {
            throw FeedFormatException("The feed is not valid XML: ${e.message}", e)
        }
    }

    private fun readFeed(p: XmlPullParser): Feed {
        var channelTitle: String? = null
        val items = mutableListOf<FeedItem>()
        var sawRss = false
        var closed = false
        while (true) {
            val event = p.next()
            if (event == XmlPullParser.END_DOCUMENT) break
            if (event == XmlPullParser.END_TAG && p.depth == 1 && p.name == "rss") closed = true
            if (event != XmlPullParser.START_TAG || p.namespace.isNotEmpty()) continue
            // <rss> (depth 1) / <channel> (2) / <title> or <item> (3)
            when {
                p.depth == 1 -> if (p.name == "rss") sawRss = true else throw FeedFormatException("Not an RSS feed (<${p.name}>)")
                p.depth == 3 && p.name == "item" -> readItem(p)?.let(items::add)
                p.depth == 3 && p.name == "title" && channelTitle == null -> channelTitle = readText(p).trim().ifEmpty { null }
            }
        }
        if (!sawRss) throw FeedFormatException("The feed is empty")
        // A truncated download must not look like a feed whose old items were removed (§7.3).
        if (!closed) throw FeedFormatException("The feed ended early (truncated download?)")
        return Feed(channelTitle, items)
    }

    /** Positioned on `<item>`; returns positioned on `</item>`. Null when there's no usable enclosure. */
    private fun readItem(p: XmlPullParser): FeedItem? {
        var title = ""
        var guid: String? = null
        var pubDate: String? = null
        var url: String? = null
        var length: Long? = null
        var type: String? = null
        var duration: String? = null
        var episodeType: String? = null
        var season: String? = null
        var episode: String? = null
        val itemDepth = p.depth
        while (true) {
            val event = p.next()
            if (event == XmlPullParser.END_DOCUMENT) break
            if (event == XmlPullParser.END_TAG && p.depth == itemDepth) break
            if (event != XmlPullParser.START_TAG || p.depth != itemDepth + 1) continue
            when (p.namespace) {
                "" -> when (p.name) {
                    "title" -> title = readText(p)
                    "guid" -> guid = readText(p)
                    "pubDate" -> pubDate = readText(p)
                    "enclosure" -> {
                        url = p.getAttributeValue(null, "url")?.trim()
                        length = p.getAttributeValue(null, "length")?.trim()?.toLongOrNull()?.takeIf { it > 0 }
                        type = p.getAttributeValue(null, "type")?.trim()
                        skip(p)
                    }
                    else -> skip(p)
                }
                ITUNES_NS -> when (p.name) {
                    "duration" -> duration = readText(p)
                    "episodeType" -> episodeType = readText(p)
                    "season" -> season = readText(p)
                    "episode" -> episode = readText(p)
                    else -> skip(p)
                }
                else -> skip(p)
            }
        }
        val enclosure = url?.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: return null
        if (type != null && !type.startsWith("audio/")) return null
        return FeedItem(
            guid = guid?.trim()?.ifEmpty { null } ?: enclosure,
            title = title.trim(),
            pubDateMs = pubDate?.let(::parseDate),
            enclosureUrl = enclosure,
            enclosureLength = length,
            enclosureType = type,
            durationMs = duration?.let(::parseDuration),
            episodeType = episodeType?.trim()?.lowercase()?.ifEmpty { null },
            season = season?.trim()?.toIntOrNull(),
            episode = episode?.trim()?.toIntOrNull(),
        )
    }

    /** Text content of the current element, positioned after its end tag. Nested tags are flattened. */
    private fun readText(p: XmlPullParser): String {
        val sb = StringBuilder()
        val depth = p.depth
        while (true) {
            when (p.next()) {
                XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> sb.append(p.text ?: "")
                XmlPullParser.END_TAG -> if (p.depth == depth) return sb.toString()
                XmlPullParser.END_DOCUMENT -> return sb.toString()
            }
        }
    }

    private fun skip(p: XmlPullParser) {
        val depth = p.depth
        while (true) {
            when (p.next()) {
                XmlPullParser.END_TAG -> if (p.depth == depth) return
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    /** RFC 822 / 1123 dates, with or without the day of the week. */
    internal fun parseDate(text: String): Long? {
        val t = text.trim().replace(Regex("\\s+"), " ")
        for (candidate in listOf(t, t.substringAfter(", ", t))) {
            for (f in DATE_FORMATS) {
                try {
                    return ZonedDateTime.parse(candidate, f).toInstant().toEpochMilli()
                } catch (_: DateTimeParseException) {
                }
            }
        }
        return null
    }

    private val DATE_FORMATS = listOf(
        DateTimeFormatter.RFC_1123_DATE_TIME,
        DateTimeFormatter.ofPattern("d MMM yyyy HH:mm:ss Z", java.util.Locale.ENGLISH),
        DateTimeFormatter.ofPattern("d MMM yyyy HH:mm:ss zzz", java.util.Locale.ENGLISH),
        DateTimeFormatter.ofPattern("d MMM yyyy HH:mm Z", java.util.Locale.ENGLISH),
    )

    /** `itunes:duration`: seconds, `mm:ss` or `h:mm:ss`. */
    internal fun parseDuration(text: String): Long? {
        val parts = text.trim().split(':')
        if (parts.isEmpty() || parts.size > 3) return null
        val nums = parts.map { it.toDoubleOrNull() ?: return null }
        if (nums.any { it < 0 }) return null
        val seconds = nums.fold(0.0) { acc, n -> acc * 60 + n }
        return (seconds * 1000).toLong().takeIf { it > 0 }
    }

    private class LimitedInputStream(input: InputStream, private val max: Long) : FilterInputStream(input) {
        private var count = 0L
        private fun add(n: Int): Int {
            if (n > 0) {
                count += n
                if (count > max) throw FeedFormatException("The feed is larger than ${max / (1024 * 1024)} MB")
            }
            return n
        }
        override fun read(): Int = super.read().also { if (it >= 0) add(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = add(super.read(b, off, len))
    }
}
