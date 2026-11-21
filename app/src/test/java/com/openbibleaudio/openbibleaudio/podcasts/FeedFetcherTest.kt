package com.openbibleaudio.openbibleaudio.podcasts

import android.util.Xml
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openbibleaudio.core.podcast.fixtures.TbrShapedFeed
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Conditional GET and error mapping (spec 0003 §7.1) against MockWebServer. */
@RunWith(AndroidJUnit4::class)
class FeedFetcherTest {
    private val server = MockWebServer()
    private val fetcher = FeedFetcher(OkHttpClient(), "OpenBibleAudio/test") { Xml.newPullParser() }
    private val url get() = server.url("/feed.xml").toString()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun fetchesAndParsesWithValidators() = runBlocking {
        server.enqueue(MockResponse().setBody(TbrShapedFeed.xml).setHeader("ETag", "\"v1\"").setHeader("Last-Modified", "Sat, 21 Nov 2026 01:00:00 GMT"))
        val r = assertIs<FeedFetchResult.Fetched>(fetcher.fetch(url, null, null))
        assertEquals("The Recap (fixture)", r.feed.title)
        assertTrue(r.feed.items.size > 15)
        assertEquals("\"v1\"", r.etag)
        assertEquals("Sat, 21 Nov 2026 01:00:00 GMT", r.lastModified)

        val request = server.takeRequest()
        assertEquals("OpenBibleAudio/test", request.getHeader("User-Agent"))
        assertNull(request.getHeader("If-None-Match"))
    }

    @Test
    fun sendsValidatorsAndUnderstandsNotModified() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(304))
        assertIs<FeedFetchResult.NotModified>(fetcher.fetch(url, "\"v1\"", "Sat, 21 Nov 2026 01:00:00 GMT"))
        val request = server.takeRequest()
        assertEquals("\"v1\"", request.getHeader("If-None-Match"))
        assertEquals("Sat, 21 Nov 2026 01:00:00 GMT", request.getHeader("If-Modified-Since"))
    }

    @Test
    fun serverErrorsAreRetryableAndClientErrorsAreNot() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        assertTrue(assertIs<FeedFetchResult.Failed>(fetcher.fetch(url, null, null)).retryable)
        server.enqueue(MockResponse().setResponseCode(404))
        assertFalse(assertIs<FeedFetchResult.Failed>(fetcher.fetch(url, null, null)).retryable)
    }

    @Test
    fun aTruncatedFeedFails() = runBlocking {
        server.enqueue(MockResponse().setBody(TbrShapedFeed.xml.substringBefore("</channel>")))
        val r = assertIs<FeedFetchResult.Failed>(fetcher.fetch(url, null, null))
        assertFalse(r.retryable)
    }

    @Test
    fun aDroppedConnectionIsRetryable() = runBlocking {
        server.shutdown()
        assertTrue(assertIs<FeedFetchResult.Failed>(fetcher.fetch(url, null, null)).retryable)
    }

    @Test
    fun aBadAddressIsNotFetched() = runBlocking {
        assertFalse(assertIs<FeedFetchResult.Failed>(fetcher.fetch("ftp://example.com/feed", null, null)).retryable)
    }
}
