package com.openbibleaudio.core.importer.profile

import com.openbibleaudio.core.model.Book
import com.openbibleaudio.core.model.pack.PackBible

/**
 * Berean Standard Bible, Gilbert narration, as downloaded from openbible.com/audio/gilbert
 * (spec 0001 §3): flat folder, `BSB_{order:2}_{abbr}_{chapter:3}_G.mp3`. The abbreviations are
 * USFM codes in mixed case except `Tts` (Titus).
 */
object BsbGilbertProfile {
    val profile = OpenBibleBsb.profile(
        id = "bsb-gilbert",
        description = "Berean Standard Bible, Gilbert narration (openbible.com/audio/gilbert)",
        fileSuffix = "_G",
    )
}

/**
 * Berean Standard Bible, Souer narration, as downloaded from openbible.com/audio/souer: the
 * Gilbert layout without the narrator suffix, `BSB_{order:2}_{abbr}_{chapter:3}.mp3`.
 */
object BsbSouerProfile {
    val profile = OpenBibleBsb.profile(
        id = "bsb-souer",
        description = "Berean Standard Bible, Souer narration (openbible.com/audio/souer)",
        fileSuffix = "",
    )
}

/** The openbible.com BSB recordings share one naming scheme, differing only by a file suffix. */
internal object OpenBibleBsb {
    fun profile(id: String, description: String, fileSuffix: String) = LayoutProfile(
        id = id,
        description = description,
        pathPattern = Regex("""^BSB_(?<order>\d{2})_(?<book>[1-3]?[A-Za-z]{2,3})_(?<chapter>\d{3})${Regex.escape(fileSuffix)}\.mp3$"""),
        books = BookAliases.usfm() + mapOf("TTS" to Book.TITUS),
        bibleDefaults = PackBible(
            versionId = id, // translation + recording (spec 0001 §5)
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
