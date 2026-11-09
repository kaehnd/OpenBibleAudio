package com.openbibleaudio.core.model

/** A parsed scripture reference. [chapter] null = the whole book (voice "play Romans"). */
data class ParsedReference(val book: Book, val chapter: Int?, val verse: Int?)

/**
 * Parses English scripture references typed or spoken by the user (AD-11, spec 0002 §2.3):
 * `Gen 3`, `genesis chapter three`, `1 John 2`, `First John 2`, `Song of Songs 4:2`,
 * `psalm one hundred nineteen`, `Romans`.
 *
 * Returns null if the book is unknown, there are stray words, or the chapter doesn't exist.
 */
object ReferenceParser {
    private const val MAX_BOOK_TOKENS = 5

    fun parse(text: String): ParsedReference? {
        var tokens = tokenize(text)
        tokens = stripLeadingFillers(tokens)
        if (tokens.isEmpty()) return null

        val (book, consumed) = matchBook(tokens) ?: return null
        val rest = tokens.drop(consumed).filterNot { it in FILLERS }
        val numbers = NumberParser.parseAll(rest) ?: return null
        return when (numbers.size) {
            0 -> ParsedReference(book, null, null)
            1, 2 -> {
                val chapter = numbers[0]
                if (!book.hasChapter(chapter)) return null
                val verse = numbers.getOrNull(1)?.takeIf { it >= 1 } ?: if (numbers.size == 2) return null else null
                ParsedReference(book, chapter, verse)
            }
            else -> null
        }
    }

    private fun tokenize(text: String): List<String> =
        text.lowercase()
            .replace(Regex("[.,:;'’\"!?()-]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }

    private val FILLERS = setOf("chapter", "chapters", "ch", "verse", "verses", "v", "vs", "and")
    private val LEADING_FILLERS = listOf(listOf("play"), listOf("listen", "to"), listOf("read"), listOf("the", "book", "of"), listOf("book", "of"), listOf("the"))

    private fun stripLeadingFillers(tokens: List<String>): List<String> {
        var t = tokens
        var changed = true
        while (changed) {
            changed = false
            for (f in LEADING_FILLERS) {
                if (t.size > f.size && t.subList(0, f.size) == f) {
                    t = t.drop(f.size)
                    changed = true
                }
            }
        }
        return t
    }

    /** Longest book phrase at the start of [tokens], with a leading ordinal normalised to a digit. */
    private fun matchBook(tokens: List<String>): Pair<Book, Int>? {
        for (k in minOf(MAX_BOOK_TOKENS, tokens.size) downTo 1) {
            val phrase = tokens.subList(0, k)
            val candidates = buildList {
                add(phrase.joinToString(" "))
                ORDINALS[phrase[0]]?.let { n ->
                    val tail = phrase.drop(1).joinToString(" ")
                    if (tail.isNotEmpty()) {
                        add("$n $tail")
                        add("$n$tail")
                    }
                }
            }
            for (c in candidates) BookNames.lookup(c)?.let { return it to k }
        }
        return null
    }

    private val ORDINALS = mapOf(
        "1" to 1, "i" to 1, "1st" to 1, "first" to 1, "one" to 1,
        "2" to 2, "ii" to 2, "2nd" to 2, "second" to 2, "two" to 2,
        "3" to 3, "iii" to 3, "3rd" to 3, "third" to 3, "three" to 3,
    )
}

/** English book names and abbreviations for parsing (not display; display names are Android resources). */
object BookNames {
    private val single: Map<Book, List<String>> = mapOf(
        Book.GENESIS to listOf("genesis", "gen", "ge", "gn"),
        Book.EXODUS to listOf("exodus", "exod", "exo", "ex"),
        Book.LEVITICUS to listOf("leviticus", "lev", "le", "lv"),
        Book.NUMBERS to listOf("numbers", "num", "nu", "nm"),
        Book.DEUTERONOMY to listOf("deuteronomy", "deut", "deu", "dt"),
        Book.JOSHUA to listOf("joshua", "josh", "jos"),
        Book.JUDGES to listOf("judges", "judg", "jdg", "jg"),
        Book.RUTH to listOf("ruth", "ru", "rth"),
        Book.EZRA to listOf("ezra", "ezr"),
        Book.NEHEMIAH to listOf("nehemiah", "neh"),
        Book.ESTHER to listOf("esther", "esth", "est"),
        Book.JOB to listOf("job"),
        Book.PSALMS to listOf("psalms", "psalm", "ps", "psa", "pss"),
        Book.PROVERBS to listOf("proverbs", "proverb", "prov", "pro", "pr"),
        Book.ECCLESIASTES to listOf("ecclesiastes", "eccles", "eccl", "ecc", "qoheleth"),
        Book.SONG_OF_SONGS to listOf("song of songs", "song of solomon", "song", "songs", "canticles", "canticle of canticles", "sos", "sng"),
        Book.ISAIAH to listOf("isaiah", "isa"),
        Book.JEREMIAH to listOf("jeremiah", "jer"),
        Book.LAMENTATIONS to listOf("lamentations", "lam"),
        Book.EZEKIEL to listOf("ezekiel", "ezek", "eze", "ezk"),
        Book.DANIEL to listOf("daniel", "dan", "dn"),
        Book.HOSEA to listOf("hosea", "hos"),
        Book.JOEL to listOf("joel", "jl", "jol"),
        Book.AMOS to listOf("amos", "am", "amo"),
        Book.OBADIAH to listOf("obadiah", "obad", "ob", "oba"),
        Book.JONAH to listOf("jonah", "jon", "jnh"),
        Book.MICAH to listOf("micah", "mic"),
        Book.NAHUM to listOf("nahum", "nah", "nam"),
        Book.HABAKKUK to listOf("habakkuk", "hab"),
        Book.ZEPHANIAH to listOf("zephaniah", "zeph", "zep"),
        Book.HAGGAI to listOf("haggai", "hag"),
        Book.ZECHARIAH to listOf("zechariah", "zech", "zec"),
        Book.MALACHI to listOf("malachi", "mal"),
        Book.MATTHEW to listOf("matthew", "matt", "mat", "mt"),
        Book.MARK to listOf("mark", "mrk", "mk", "mar"),
        Book.LUKE to listOf("luke", "luk", "lk"),
        Book.JOHN to listOf("john", "jhn", "jn"),
        Book.ACTS to listOf("acts", "act", "acts of the apostles"),
        Book.ROMANS to listOf("romans", "rom", "ro"),
        Book.GALATIANS to listOf("galatians", "gal"),
        Book.EPHESIANS to listOf("ephesians", "eph"),
        Book.PHILIPPIANS to listOf("philippians", "phil", "php"),
        Book.COLOSSIANS to listOf("colossians", "col"),
        Book.TITUS to listOf("titus", "tit"),
        Book.PHILEMON to listOf("philemon", "philem", "phm", "phlm"),
        Book.HEBREWS to listOf("hebrews", "heb"),
        Book.JAMES to listOf("james", "jas", "jm"),
        Book.JUDE to listOf("jude", "jud", "jd"),
        Book.REVELATION to listOf("revelation", "revelations", "rev", "re", "apocalypse"),
    )

    /** Base names of numbered books; `1 samuel`, `1samuel`, `1 sam`, … are generated. */
    private val numbered: Map<List<Book>, List<String>> = mapOf(
        listOf(Book.FIRST_SAMUEL, Book.SECOND_SAMUEL) to listOf("samuel", "sam", "sa", "sm"),
        listOf(Book.FIRST_KINGS, Book.SECOND_KINGS) to listOf("kings", "king", "kgs", "ki"),
        listOf(Book.FIRST_CHRONICLES, Book.SECOND_CHRONICLES) to listOf("chronicles", "chron", "chr"),
        listOf(Book.FIRST_CORINTHIANS, Book.SECOND_CORINTHIANS) to listOf("corinthians", "cor", "co"),
        listOf(Book.FIRST_THESSALONIANS, Book.SECOND_THESSALONIANS) to listOf("thessalonians", "thess", "thes", "th"),
        listOf(Book.FIRST_TIMOTHY, Book.SECOND_TIMOTHY) to listOf("timothy", "tim", "ti"),
        listOf(Book.FIRST_PETER, Book.SECOND_PETER) to listOf("peter", "pet", "pe", "pt"),
        listOf(Book.FIRST_JOHN, Book.SECOND_JOHN, Book.THIRD_JOHN) to listOf("john", "jhn", "jn", "jo"),
    )

    private val table: Map<String, Book> = buildMap {
        for (b in Book.entries) put(b.usfm.lowercase(), b)
        for ((book, names) in single) names.forEach { put(it, book) }
        for ((books, names) in numbered) {
            books.forEachIndexed { i, book ->
                for (name in names) {
                    put("${i + 1} $name", book)
                    put("${i + 1}$name", book)
                }
            }
        }
    }

    /** [phrase] is lowercase, single-space separated. */
    fun lookup(phrase: String): Book? = table[phrase]
}

/** Digits and English number words up to the hundreds ("one hundred and nineteen"). */
internal object NumberParser {
    private val UNITS = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine")
        .withIndex().associate { it.value to it.index }
    private val TEENS = listOf("ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
        .withIndex().associate { it.value to it.index + 10 }
    private val TENS = mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90)

    /** Every token must be part of a number; null otherwise. */
    fun parseAll(tokens: List<String>): List<Int>? {
        val out = mutableListOf<Int>()
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if (t.all { it.isDigit() } && t.length <= 4) {
                out += t.toInt()
                i++
                continue
            }
            val (value, used) = parseWords(tokens, i) ?: return null
            out += value
            i += used
        }
        return out
    }

    /** Parses one spelled-out number starting at [start]. */
    private fun parseWords(tokens: List<String>, start: Int): Pair<Int, Int>? {
        var i = start
        var hundreds = 0
        fun word() = tokens.getOrNull(i)

        // Optional "<unit|a> hundred [and]"
        val lead = word()
        if ((lead == "a" || (lead in UNITS && lead != "zero")) && tokens.getOrNull(i + 1) == "hundred") {
            hundreds = if (lead == "a") 100 else UNITS.getValue(lead!!) * 100
            i += 2
            if (word() == "and") i++
        } else if (lead == "hundred") {
            hundreds = 100
            i++
            if (word() == "and") i++
        }

        var rest = 0
        val w = word()
        when {
            w in TEENS -> { rest = TEENS.getValue(w!!); i++ }
            w in TENS -> {
                rest = TENS.getValue(w!!); i++
                word()?.let { u -> UNITS[u]?.takeIf { it > 0 }?.let { rest += it; i++ } }
            }
            w in UNITS -> { rest = UNITS.getValue(w!!); i++ }
        }
        if (i == start) return null
        // A trailing "and" consumed after "hundred" with nothing following isn't a number word.
        if (hundreds > 0 && rest == 0 && tokens.getOrNull(i - 1) == "and") i--
        return (hundreds + rest) to (i - start)
    }
}
