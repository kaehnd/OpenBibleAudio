package com.openbibleaudio.core.model

enum class Testament { OLD, NEW }

/**
 * The books of the 66-book Protestant canon, in canonical order (enum ordinal = canonical order).
 *
 * [usfm] is the USFM 3-letter book code, the canonical book key throughout the app (AD-11).
 * [chapterCount] follows English versification (e.g. Joel 3, Malachi 4).
 */
enum class Book(val usfm: String, val testament: Testament, val chapterCount: Int) {
    GENESIS("GEN", Testament.OLD, 50),
    EXODUS("EXO", Testament.OLD, 40),
    LEVITICUS("LEV", Testament.OLD, 27),
    NUMBERS("NUM", Testament.OLD, 36),
    DEUTERONOMY("DEU", Testament.OLD, 34),
    JOSHUA("JOS", Testament.OLD, 24),
    JUDGES("JDG", Testament.OLD, 21),
    RUTH("RUT", Testament.OLD, 4),
    FIRST_SAMUEL("1SA", Testament.OLD, 31),
    SECOND_SAMUEL("2SA", Testament.OLD, 24),
    FIRST_KINGS("1KI", Testament.OLD, 22),
    SECOND_KINGS("2KI", Testament.OLD, 25),
    FIRST_CHRONICLES("1CH", Testament.OLD, 29),
    SECOND_CHRONICLES("2CH", Testament.OLD, 36),
    EZRA("EZR", Testament.OLD, 10),
    NEHEMIAH("NEH", Testament.OLD, 13),
    ESTHER("EST", Testament.OLD, 10),
    JOB("JOB", Testament.OLD, 42),
    PSALMS("PSA", Testament.OLD, 150),
    PROVERBS("PRO", Testament.OLD, 31),
    ECCLESIASTES("ECC", Testament.OLD, 12),
    SONG_OF_SONGS("SNG", Testament.OLD, 8),
    ISAIAH("ISA", Testament.OLD, 66),
    JEREMIAH("JER", Testament.OLD, 52),
    LAMENTATIONS("LAM", Testament.OLD, 5),
    EZEKIEL("EZK", Testament.OLD, 48),
    DANIEL("DAN", Testament.OLD, 12),
    HOSEA("HOS", Testament.OLD, 14),
    JOEL("JOL", Testament.OLD, 3),
    AMOS("AMO", Testament.OLD, 9),
    OBADIAH("OBA", Testament.OLD, 1),
    JONAH("JON", Testament.OLD, 4),
    MICAH("MIC", Testament.OLD, 7),
    NAHUM("NAM", Testament.OLD, 3),
    HABAKKUK("HAB", Testament.OLD, 3),
    ZEPHANIAH("ZEP", Testament.OLD, 3),
    HAGGAI("HAG", Testament.OLD, 2),
    ZECHARIAH("ZEC", Testament.OLD, 14),
    MALACHI("MAL", Testament.OLD, 4),

    MATTHEW("MAT", Testament.NEW, 28),
    MARK("MRK", Testament.NEW, 16),
    LUKE("LUK", Testament.NEW, 24),
    JOHN("JHN", Testament.NEW, 21),
    ACTS("ACT", Testament.NEW, 28),
    ROMANS("ROM", Testament.NEW, 16),
    FIRST_CORINTHIANS("1CO", Testament.NEW, 16),
    SECOND_CORINTHIANS("2CO", Testament.NEW, 13),
    GALATIANS("GAL", Testament.NEW, 6),
    EPHESIANS("EPH", Testament.NEW, 6),
    PHILIPPIANS("PHP", Testament.NEW, 4),
    COLOSSIANS("COL", Testament.NEW, 4),
    FIRST_THESSALONIANS("1TH", Testament.NEW, 5),
    SECOND_THESSALONIANS("2TH", Testament.NEW, 3),
    FIRST_TIMOTHY("1TI", Testament.NEW, 6),
    SECOND_TIMOTHY("2TI", Testament.NEW, 4),
    TITUS("TIT", Testament.NEW, 3),
    PHILEMON("PHM", Testament.NEW, 1),
    HEBREWS("HEB", Testament.NEW, 13),
    JAMES("JAS", Testament.NEW, 5),
    FIRST_PETER("1PE", Testament.NEW, 5),
    SECOND_PETER("2PE", Testament.NEW, 3),
    FIRST_JOHN("1JN", Testament.NEW, 5),
    SECOND_JOHN("2JN", Testament.NEW, 1),
    THIRD_JOHN("3JN", Testament.NEW, 1),
    JUDE("JUD", Testament.NEW, 1),
    REVELATION("REV", Testament.NEW, 22);

    /** True if [chapter] is a valid 1-based chapter number for this book. */
    fun hasChapter(chapter: Int): Boolean = chapter in 1..chapterCount

    companion object {
        private val byUsfm: Map<String, Book> = entries.associateBy { it.usfm }

        /** Looks up a book by USFM code (case-insensitive); null if unknown. */
        fun fromUsfm(code: String): Book? = byUsfm[code.trim().uppercase()]
    }
}
