package com.openbibleaudio.openbibleaudio.podcasts

import com.openbibleaudio.core.podcast.Feed
import com.openbibleaudio.core.podcast.FeedFormatException
import com.openbibleaudio.core.podcast.FeedParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.IOException

sealed interface FeedFetchResult {
    data class Fetched(val feed: Feed, val etag: String?, val lastModified: String?) : FeedFetchResult

    /** `304 Not Modified`: the stored snapshot is current. */
    data object NotModified : FeedFetchResult

    /** [retryable] is true for network errors and server-side failures (5xx, 408, 429). */
    data class Failed(val message: String, val retryable: Boolean) : FeedFetchResult
}

/**
 * Conditional GET of a plan's podcast feed (spec 0003 §7.1). Only this HTTP call is Android-side.
 * Parsing is `:core:podcast`'s [FeedParser]. [newParser] supplies the XmlPullParser
 * (`Xml.newPullParser()` on the device).
 */
class FeedFetcher(
    private val client: OkHttpClient,
    private val userAgent: String,
    private val newParser: () -> XmlPullParser,
) {
    suspend fun fetch(url: String, etag: String?, lastModified: String?): FeedFetchResult = withContext(Dispatchers.IO) {
        val httpUrl = url.toHttpUrlOrNull() ?: return@withContext FeedFetchResult.Failed("Not a web address: $url", retryable = false)
        val request = Request.Builder()
            .url(httpUrl)
            .header("User-Agent", userAgent)
            .apply {
                etag?.let { header("If-None-Match", it) }
                lastModified?.let { header("If-Modified-Since", it) }
            }
            .build()
        try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 304 -> FeedFetchResult.NotModified
                    !response.isSuccessful -> FeedFetchResult.Failed(
                        "The feed server answered HTTP ${response.code}",
                        retryable = response.code >= 500 || response.code == 408 || response.code == 429,
                    )
                    else -> {
                        val body = response.body ?: return@use FeedFetchResult.Failed("The feed was empty", retryable = true)
                        val feed = FeedParser.parse(body.byteStream(), newParser())
                        FeedFetchResult.Fetched(feed, response.header("ETag"), response.header("Last-Modified"))
                    }
                }
            }
        } catch (e: FeedFormatException) {
            // Includes a truncated body (the parser requires </rss>). The stored snapshot is left as it
            // is, and the next scheduled refresh tries again.
            FeedFetchResult.Failed(e.message ?: "The feed couldn't be read", retryable = false)
        } catch (e: IOException) {
            FeedFetchResult.Failed(e.message ?: "Network error", retryable = true)
        }
    }
}
