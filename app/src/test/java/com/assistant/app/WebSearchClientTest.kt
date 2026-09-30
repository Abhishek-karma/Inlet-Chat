package com.assistant.app

import com.assistant.app.llm.DuckDuckGoSearchProvider
import com.assistant.app.llm.HttpPageFetcher
import com.assistant.app.llm.JsoupContentExtractor
import com.assistant.app.llm.WebSearchClient
import com.assistant.app.llm.model.SearchError
import com.assistant.app.llm.model.SearchOutcome
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
import java.net.URLEncoder
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

    private fun client(maxPagesToFetch: Int = 2) = WebSearchClient(
        provider = DuckDuckGoSearchProvider(
            client = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build(),
            dispatcher = Dispatchers.Unconfined,
            endpoint = server.url("/html").toString(),
        ),
        pageFetcher = HttpPageFetcher(
            client = OkHttpClient(),
            dispatcher = Dispatchers.Unconfined,
            allowPrivateHosts = true,
        ),
        contentExtractor = JsoupContentExtractor(),
        dispatcher = Dispatchers.Unconfined,
        maxPagesToFetch = maxPagesToFetch,
    )

    /** One DuckDuckGo-style result entry: title, target url, snippet. */
    private fun ddgHtml(vararg results: Triple<String, String, String>): String =
        results.joinToString(prefix = "<html><body>", postfix = "</body></html>") { (title, url, snippet) ->
            """
            <div class="result results_links">
              <h2><a class="result__a" href="//duckduckgo.com/l/?uddg=${URLEncoder.encode(url, "UTF-8")}&amp;rut=abc">$title</a></h2>
              <a class="result__snippet">$snippet</a>
            </div>
            """.trimIndent()
        }

    @Test
    fun `results are enriched with fetched article text`() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/html")
                .setBody(ddgHtml(Triple("Kotlin", server.url("/page1").toString(), "Official site"))),
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/html; charset=utf-8")
                .setBody(
                    "<html><head><title>Kotlin Lang</title></head><body><article><p>" +
                        "Kotlin is a modern language designed to make developers happier." +
                        "</p></article></body></html>",
                ),
        )

        val outcome = runBlocking { client().search("kotlin") }

        assertTrue(outcome is SearchOutcome.Success)
        val result = (outcome as SearchOutcome.Success).results.single()
        assertEquals("Kotlin", result.title)
        assertTrue(result.snippet.contains("Kotlin is a modern language"))
    }

    @Test
    fun `duplicate result urls are collapsed`() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/html")
                .setBody(
                    ddgHtml(
                        Triple("Page 1", "https://example.com/item", "Desc 1"),
                        Triple("Page 1 Duplicate", "https://example.com/item/", "Desc 2"),
                    ),
                ),
        )
        val provider = DuckDuckGoSearchProvider(
            client = OkHttpClient(),
            dispatcher = Dispatchers.Unconfined,
            endpoint = server.url("/html").toString(),
        )

        val outcome = runBlocking { provider.search("query", maxResults = 5) }

        assertTrue(outcome is SearchOutcome.Success)
        assertEquals(1, (outcome as SearchOutcome.Success).results.size)
    }

    @Test
    fun `an oversized search response is rejected instead of parsed`() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/html")
                .setBody("x".repeat(2 * 1024 * 1024)),
        )

        val outcome = runBlocking { client().search("kotlin") }

        assertEquals(SearchOutcome.Failure(SearchError.InvalidResponse), outcome)
    }

    @Test
    fun `duckduckgo html results parse without an api key`() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/html")
                .setBody(
                    """
                    <html><body>
                      <div class="result results_links">
                        <h2><a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fkotlinlang.org%2F&amp;rut=abc">Kotlin</a></h2>
                        <a class="result__snippet">Official Kotlin site</a>
                      </div>
                      <div class="result results_links">
                        <h2><a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fdeveloper.android.com%2F&amp;rut=def">Android</a></h2>
                        <a class="result__snippet">Android developers</a>
                      </div>
                    </body></html>
                    """.trimIndent(),
                ),
        )
        val provider = DuckDuckGoSearchProvider(
            client = OkHttpClient(),
            dispatcher = Dispatchers.Unconfined,
            endpoint = server.url("/html").toString(),
        )

        val outcome = runBlocking { provider.search("kotlin", maxResults = 5) }

        val results = (outcome as SearchOutcome.Success).results
        assertEquals(2, results.size)
        assertEquals("https://kotlinlang.org/", results[0].url)
        assertEquals("Official Kotlin site", results[0].snippet)
    }

    @Test
    fun `a rate limited duckduckgo response maps to RateLimited`() {
        server.enqueue(MockResponse().setResponseCode(202).setBody("<html></html>"))
        val provider = DuckDuckGoSearchProvider(
            client = OkHttpClient(),
            dispatcher = Dispatchers.Unconfined,
            endpoint = server.url("/html").toString(),
        )
        val outcome = runBlocking { provider.search("kotlin", maxResults = 5) }
        assertEquals(SearchError.RateLimited, (outcome as SearchOutcome.Failure).error)
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
        val pageUrl = server.url("/error-page").toString()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/html")
                .setBody(ddgHtml(Triple("Page 1", pageUrl, "Original search snippet text"))),
        )
        server.enqueue(MockResponse().setResponseCode(404))

        val outcome = runBlocking { client().search("query", maxResults = 1) }

        assertTrue(outcome is SearchOutcome.Success)
        val result = (outcome as SearchOutcome.Success).results.first()
        assertEquals("Page 1", result.title)
        assertEquals("Original search snippet text", result.snippet)
    }

    @Test
    fun `page fetcher follows legitimate redirect within limit`() {
        val fetcher = HttpPageFetcher(
            client = OkHttpClient(),
            dispatcher = Dispatchers.Unconfined,
            allowPrivateHosts = true,
        )
        val finalUrl = server.url("/destination")
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", finalUrl.toString()),
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/plain")
                .setBody("Redirected article content"),
        )

        val result = runBlocking { fetcher.fetch(server.url("/start").toString()) }
        assertEquals("Redirected article content", result)
    }

    @Test
    fun `page fetcher blocks redirect to private address when allowPrivateHosts is false`() {
        val fetcher = HttpPageFetcher(
            client = OkHttpClient(),
            dispatcher = Dispatchers.Unconfined,
            allowPrivateHosts = false,
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", "http://127.0.0.1:8080/secret"),
        )

        val result = runBlocking { fetcher.fetch(server.url("/start").toString()) }
        assertNull(result)
    }

    @Test
    fun `isBlockedHostOrIp detects private and loopback addresses`() {
        val fetcher = HttpPageFetcher(
            client = OkHttpClient(),
            dispatcher = Dispatchers.Unconfined,
        )
        assertTrue(fetcher.isBlockedHostOrIp("127.0.0.1"))
        assertTrue(fetcher.isBlockedHostOrIp("localhost"))
        assertTrue(fetcher.isBlockedHostOrIp("192.168.1.1"))
        assertTrue(fetcher.isBlockedHostOrIp("10.0.0.1"))
        assertTrue(fetcher.isBlockedHostOrIp("169.254.169.254"))
        assertTrue(fetcher.isBlockedHostOrIp("router.local"))
    }
}
