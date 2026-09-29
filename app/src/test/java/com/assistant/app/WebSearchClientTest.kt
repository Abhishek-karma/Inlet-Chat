package com.assistant.app

import com.assistant.app.llm.ContentExtractor
import com.assistant.app.llm.HttpPageFetcher
import com.assistant.app.llm.JsoupContentExtractor
import com.assistant.app.llm.PageFetcher
import com.assistant.app.llm.SearXNGSearchProvider
import com.assistant.app.llm.WebSearchClient
import com.assistant.app.llm.WebSearchProvider
import com.assistant.app.llm.model.SearchError
import com.assistant.app.llm.model.SearchOutcome
import com.assistant.app.llm.model.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WebSearchClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(
        apiKey: String? = null,
        maxPagesToFetch: Int = 2,
    ) = WebSearchClient(
        client = OkHttpClient.Builder()
            .callTimeout(5, TimeUnit.SECONDS)
            .build(),
        endpoint = server.url("/search").toString(),
        apiKey = apiKey,
        dispatcher = Dispatchers.Unconfined,
        maxPagesToFetch = maxPagesToFetch,
        allowPrivateHosts = true,
    )

    @Test
    fun `a search result pointing at a private address is not fetched`() = runBlocking {
        val fetcher = HttpPageFetcher(OkHttpClient(), dispatcher = Dispatchers.Unconfined)
        val outcome = fetcher.fetch("http://169.254.169.254/latest/meta-data/")

        assertNull(outcome)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an oversized search response is rejected instead of parsed`() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"results\":[" + "x".repeat(2 * 1024 * 1024) + "]}"),
        )

        val outcome = runBlocking { client().search("kotlin") }

        assertEquals(SearchOutcome.Failure(SearchError.InvalidResponse), outcome)
    }

    @Test
    fun `searxng json response parses results correctly without requiring an api key`() {
        server.enqueue(
            MockResponse().setBody(
                """{
                    "query": "kotlin",
                    "results": [
                        {
                            "title": "Kotlin Programming Language",
                            "url": "${server.url("/page1")}",
                            "content": "Official Kotlin site",
                            "engine": "google"
                        },
                        {
                            "title": "Kotlin Wikipedia",
                            "url": "${server.url("/page2")}",
                            "content": "Wikipedia article on Kotlin",
                            "engine": "wikipedia"
                        }
                    ]
                }""",
            ),
        )
        // Enqueue HTML for page1 and page2
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/html; charset=utf-8")
                .setBody("<html><head><title>Kotlin Lang</title></head><body><article><p>Kotlin is a modern language designed to make developers happier.</p></article></body></html>"),
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/html; charset=utf-8")
                .setBody("<html><head><title>Kotlin Wiki</title></head><body><p>General purpose programming language.</p></body></html>"),
        )

        val outcome = runBlocking { client(apiKey = null).search("kotlin", maxResults = 5) }

        assertTrue(outcome is SearchOutcome.Success)
        val results = (outcome as SearchOutcome.Success).results
        assertEquals(2, results.size)
        assertEquals("Kotlin Programming Language", results[0].title)
        assertTrue(results[0].snippet.contains("Kotlin is a modern language"))
        assertEquals(server.url("/page1").toString(), results[0].url)
        assertEquals("google", results[0].engine)
    }

    @Test
    fun `duplicate URLs are deduplicated`() {
        server.enqueue(
            MockResponse().setBody(
                """{
                    "results": [
                        {"title": "Page 1", "url": "https://example.com/item", "content": "Desc 1"},
                        {"title": "Page 1 Duplicate", "url": "https://example.com/item/", "content": "Desc 2"},
                        {"title": "Page 2", "url": "https://example.com/other", "content": "Desc 3"}
                    ]
                }""",
            ),
        )

        val provider = SearXNGSearchProvider(
            client = OkHttpClient(),
            endpoint = server.url("/search").toString(),
            dispatcher = Dispatchers.Unconfined,
        )

        val outcome = runBlocking { provider.search("query", maxResults = 5) }
        assertTrue(outcome is SearchOutcome.Success)
        val results = (outcome as SearchOutcome.Success).results
        assertEquals(2, results.size)
        assertEquals("https://example.com/item", results[0].url)
        assertEquals("https://example.com/other", results[1].url)
    }

    @Test
    fun `invalid and non-http URLs are skipped`() {
        server.enqueue(
            MockResponse().setBody(
                """{
                    "results": [
                        {"title": "Invalid 1", "url": "javascript:alert(1)", "content": "bad"},
                        {"title": "Invalid 2", "url": "", "content": "empty"},
                        {"title": "Valid", "url": "https://example.com/valid", "content": "good"}
                    ]
                }""",
            ),
        )

        val provider = SearXNGSearchProvider(
            client = OkHttpClient(),
            endpoint = server.url("/search").toString(),
            dispatcher = Dispatchers.Unconfined,
        )

        val outcome = runBlocking { provider.search("query", maxResults = 5) }
        assertTrue(outcome is SearchOutcome.Success)
        val results = (outcome as SearchOutcome.Success).results
        assertEquals(1, results.size)
        assertEquals("https://example.com/valid", results[0].url)
    }

    @Test
    fun `optional api key is sent in auth headers`() {
        server.enqueue(MockResponse().setBody("""{"results":[]}"""))

        val provider = SearXNGSearchProvider(
            client = OkHttpClient(),
            endpoint = server.url("/search").toString(),
            apiKey = "custom-secret-key",
            dispatcher = Dispatchers.Unconfined,
        )

        runBlocking { provider.search("query", maxResults = 5) }

        val request = server.takeRequest()
        assertEquals("Bearer custom-secret-key", request.getHeader("Authorization"))
        assertEquals("custom-secret-key", request.getHeader("X-Subscription-Token"))
    }

    @Test
    fun `empty results map to NoResults`() {
        server.enqueue(MockResponse().setBody("""{"results":[]}"""))
        val provider = SearXNGSearchProvider(
            client = OkHttpClient(),
            endpoint = server.url("/search").toString(),
            dispatcher = Dispatchers.Unconfined,
        )
        val outcome = runBlocking { provider.search("query", maxResults = 5) }
        assertEquals(SearchError.NoResults, (outcome as SearchOutcome.Failure).error)
    }

    @Test
    fun `malformed json maps to InvalidResponse`() {
        server.enqueue(MockResponse().setBody("{invalid-json"))
        val provider = SearXNGSearchProvider(
            client = OkHttpClient(),
            endpoint = server.url("/search").toString(),
            dispatcher = Dispatchers.Unconfined,
        )
        val outcome = runBlocking { provider.search("query", maxResults = 5) }
        assertEquals(SearchError.InvalidResponse, (outcome as SearchOutcome.Failure).error)
    }

    @Test
    fun `401 and 429 and 500 error codes map to proper SearchError`() {
        val provider = SearXNGSearchProvider(
            client = OkHttpClient(),
            endpoint = server.url("/search").toString(),
            dispatcher = Dispatchers.Unconfined,
        )

        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        assertEquals(SearchError.InvalidCredentials, (runBlocking { provider.search("q", 5) } as SearchOutcome.Failure).error)

        server.enqueue(MockResponse().setResponseCode(429).setBody("{}"))
        assertEquals(SearchError.RateLimited, (runBlocking { provider.search("q", 5) } as SearchOutcome.Failure).error)

        server.enqueue(MockResponse().setResponseCode(503).setBody("{}"))
        assertEquals(SearchError.ServerError, (runBlocking { provider.search("q", 5) } as SearchOutcome.Failure).error)
    }

    @Test
    fun `page fetcher skips non-text content types`() {
        val fetcher = HttpPageFetcher(
            client = OkHttpClient(),
            dispatcher = Dispatchers.Unconfined,
        )

        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "image/png")
                .setBody("binary-png-data"),
        )

        val result = runBlocking { fetcher.fetch(server.url("/image.png").toString()) }
        assertEquals(null, result)
    }

    @Test
    fun `jsoup extractor removes clutter and extracts clean article text`() {
        val extractor = JsoupContentExtractor()
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <title>Test Article Title</title>
            </head>
            <body>
                <header><nav><a href="/">Home</a></nav></header>
                <div class="cookie-banner">Please accept our cookies</div>
                <div class="advertisement">Buy this product!</div>
                <article>
                    <h1>Main Headline</h1>
                    <p>First paragraph with informative text.</p>
                    <ul>
                        <li>Bullet point 1</li>
                        <li>Bullet point 2</li>
                    </ul>
                </article>
                <footer>Copyright 2026</footer>
            </body>
            </html>
        """.trimIndent()

        val extracted = extractor.extract(html, "https://example.com/post")
        assertEquals("Test Article Title", extracted.title)
        assertTrue(extracted.text.contains("Main Headline"))
        assertTrue(extracted.text.contains("First paragraph with informative text."))
        assertTrue(extracted.text.contains("• Bullet point 1"))
        assertFalse(extracted.text.contains("Please accept our cookies"))
        assertFalse(extracted.text.contains("Buy this product!"))
        assertFalse(extracted.text.contains("Copyright 2026"))
    }

    @Test
    fun `page fetch failure falls back gracefully to search snippet`() {
        server.enqueue(
            MockResponse().setBody(
                """{
                    "results": [
                        {
                            "title": "Page 1",
                            "url": "${server.url("/error-page")}",
                            "content": "Original search snippet text"
                        }
                    ]
                }""",
            ),
        )
        // Enqueue 404 for page fetch
        server.enqueue(MockResponse().setResponseCode(404))

        val outcome = runBlocking { client().search("query", maxResults = 1) }

        assertTrue(outcome is SearchOutcome.Success)
        val result = (outcome as SearchOutcome.Success).results.first()
        assertEquals("Page 1", result.title)
        assertEquals("Original search snippet text", result.snippet)
    }
}
