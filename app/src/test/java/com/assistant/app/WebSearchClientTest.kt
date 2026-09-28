package com.assistant.app

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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * Behavior contract for the web search client against MockWebServer:
 * response-shape tolerance, result capping and filtering, and error mapping.
 *
 * Runs under Robolectric because the client parses responses with org.json.
 */
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

    private fun client(apiKey: String = "sk-search-key") = WebSearchClient(
        client = OkHttpClient.Builder()
            .callTimeout(5, TimeUnit.SECONDS)
            .build(),
        apiKey = apiKey,
        endpoint = server.url("/search").toString(),
        dispatcher = Dispatchers.Unconfined,
    )

    private fun search(client: WebSearchClient = client(), query: String = "hello", max: Int = 5) =
        runBlocking { client.search(query, max) }

    @Test
    fun `brave shaped response parses nested web results`() {
        server.enqueue(
            MockResponse().setBody(
                """{"web":{"results":[
                    {"title":"Brave","url":"https://brave.example","description":"desc here"}
                ]}}""",
            ),
        )

        val outcome = search()

        assertEquals(
            SearchOutcome.Success(
                listOf(com.assistant.app.llm.model.SearchResult("Brave", "https://brave.example", "desc here")),
            ),
            outcome,
        )
    }

    @Test
    fun `the key is sent as a header and never in the query`() {
        server.enqueue(
            MockResponse().setBody("""{"web":{"results":[{"title":"t","url":"https://e.example","description":"d"}]}}"""),
        )

        search()

        val recorded = server.takeRequest()
        assertEquals("sk-search-key", recorded.getHeader("X-Subscription-Token"))
        assertTrue(!recorded.path.orEmpty().contains("sk-search-key"))
    }

    @Test
    fun `a blank key fails without a request`() {
        val outcome = search(client(apiKey = "  "))

        assertEquals(SearchError.NoResults, (outcome as SearchOutcome.Failure).error)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `results are capped at maxResults`() {
        val entries = (1..10).joinToString(",") {
            """{"title":"T$it","url":"https://e$it.example","description":"s"}"""
        }
        server.enqueue(MockResponse().setBody("""{"web":{"results":[$entries]}}"""))

        val results = (search(max = 3) as SearchOutcome.Success).results

        assertEquals(3, results.size)
        assertEquals("https://e1.example", results[0].url)
        assertEquals("https://e3.example", results[2].url)
    }

    @Test
    fun `entries without a url are skipped`() {
        server.enqueue(
            MockResponse().setBody(
                """{"web":{"results":[
                    {"title":"no url","description":"s"},
                    {"title":"good","url":"https://good.example","description":"s"}
                ]}}""",
            ),
        )

        val outcome = search()

        assertEquals(
            SearchOutcome.Success(
                listOf(com.assistant.app.llm.model.SearchResult("good", "https://good.example", "s")),
            ),
            outcome,
        )
    }

    @Test
    fun `empty results map to NoResults`() {
        server.enqueue(MockResponse().setBody("""{"web":{"results":[]}}"""))
        server.enqueue(MockResponse().setBody("""{}"""))

        assertEquals(SearchError.NoResults, (search() as SearchOutcome.Failure).error)
        assertEquals(SearchError.NoResults, (search() as SearchOutcome.Failure).error)
    }

    @Test
    fun `malformed json maps to InvalidResponse`() {
        server.enqueue(MockResponse().setBody("{not json"))

        assertEquals(SearchError.InvalidResponse, (search() as SearchOutcome.Failure).error)
    }

    @Test
    fun `401 and 500 map to InvalidCredentials and ServerError`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(500).setBody("{}"))

        assertEquals(SearchError.InvalidCredentials, (search() as SearchOutcome.Failure).error)
        assertEquals(SearchError.ServerError, (search() as SearchOutcome.Failure).error)
    }

    @Test
    fun `connection failure maps to NetworkUnavailable`() {
        val unreachable = WebSearchClient(
            client = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build(),
            apiKey = "sk-search-key",
            endpoint = "http://127.0.0.1:9/search",
            dispatcher = Dispatchers.Unconfined,
        )

        val outcome = runBlocking { unreachable.search("q") }

        assertEquals(SearchError.NetworkUnavailable, (outcome as SearchOutcome.Failure).error)
    }

    @Test
    fun `request carries the encoded query`() {
        server.enqueue(MockResponse().setBody("""{"web":{"results":[]}}"""))

        search(query = "kotlin coroutines & flow", max = 5)

        assertEquals("/search?q=kotlin%20coroutines%20%26%20flow&count=5", server.takeRequest().path)
    }
}
