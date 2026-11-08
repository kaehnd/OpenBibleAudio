package com.openbibleaudio.core.importer.profile

import com.openbibleaudio.core.model.Book
import com.openbibleaudio.core.model.pack.PackBible

/**
 * Berean Standard Bible, Gilbert narration, as downloaded from openbible.com/audio/gilbert
 * (spec 0001 §3): flat folder, `BSB_{order:2}_{abbr}_{chapter:3}_G.mp3`. The abbreviations are
 * USFM codes in mixed case except `Tts` (Titus).
 */
object BsbGilbertProfile {
    val profile = LayoutProfile(
        id = "bsb-gilbert",
        description = "Berean Standard Bible, Gilbert narration (openbible.com/audio/gilbert)",
        pathPattern = Regex("""^BSB_(?<order>\d{2})_(?<book>[1-3]?[A-Za-z]{2,3})_(?<chapter>\d{3})_G\.mp3$"""),
        books = BookAliases.usfm() + mapOf("TTS" to Book.TITUS),
        bibleDefaults = PackBible(
            versionId = "bsb-gilbert",
            abbreviation = "BSB",
            name = "Berean Standard Bible",
            language = "en",
            versification = "eng",
            narrator = null, // spec 0001 open question 2
            attribution = null, // spec 0001 open question 1
        ),
        checks = listOf(ProfileCheck.orderMatchesCanon("order")),
    )
}
