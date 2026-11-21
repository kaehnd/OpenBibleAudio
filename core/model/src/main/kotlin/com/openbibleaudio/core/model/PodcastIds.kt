package com.openbibleaudio.core.model

import java.security.MessageDigest

/**
 * Short, stable IDs for feeds and episodes (spec 0003 §3.4). Derived by hashing, so they're
 * safe to use in media IDs and file names whatever the feed puts in a URL or GUID.
 */
object PodcastIds {
    val FEED_ID = Regex("^[0-9a-f]{12}$")
    val GUID_HASH = Regex("^[0-9a-f]{16}$")

    /** First 12 hex characters of SHA-256(feedUrl). */
    fun feedId(feedUrl: String): String = sha256Hex(feedUrl).take(12)

    /** First 16 hex characters of SHA-256(guid). */
    fun guidHash(guid: String): String = sha256Hex(guid).take(16)

    private fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
