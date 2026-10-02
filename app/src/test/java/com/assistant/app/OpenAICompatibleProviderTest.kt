package com.assistant.app

import com.assistant.app.llm.LlmProvider
import com.assistant.app.llm.OpenAICompatibleProvider
import com.assistant.app.llm.model.ChatChunk
import com.assistant.app.llm.model.ChatRequest
import com.assistant.app.llm.model.ProviderError
import com.assistant.app.llm.model.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.json.JSONObject
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
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * Behavior contract for the OpenAI-compatible streaming provider against
 * MockWebServer: happy-path streaming, error mapping, request shape, timeout,
 * empty response, and mid-stream cancellation.
 *
 * Runs under Robolectric because the provider parses responses with org.json.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OpenAICompatibleProviderTest {

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

    private fun provider(readTimeoutMs: Long = 60_000): OpenAICompatibleProvider =
        OpenAICompatibleProvider(
            client = OkHttpClient.Builder()
                .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
                .build(),
            baseUrl = server.url("/v1").toString(),
            apiKey = "sk-test-key",
            model = "configured-model",
        )

    private fun request(model: String = "request-model") =
        ChatRequest(model = model, messages = listOf(Role.USER to "Hi"))

    /** Collects the whole stream; any exception escaping the flow fails the test. */
    private fun collect(provider: LlmProvider, request: ChatRequest): List<ChatChunk> =
        runBlocking {
            val chunks = mutableListOf<ChatChunk>()
            provider.stream(request).collect { chunks += it }
            chunks
        }

    private fun delta(content: String) =
        "data: {\"choices\":[{\"delta\":{\"content\":\"$content\"}}]}\n\n"

    @Test
    fun `happy path streams deltas then Done`() {
        server.enqueue(
            MockResponse().setBody(
                "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}\n\n" +
                    delta("Hello") +
                    delta(" world") +
                    "data: [DONE]\n\n",
            ),
        )

        val chunks = collect(provider(), request())

        assertEquals(
            listOf(ChatChunk.Delta("Hello"), ChatChunk.Delta(" world"), ChatChunk.Done),
            chunks,
        )
    }

    @Test
    fun `text-only request keeps plain string content`() {
        server.enqueue(MockResponse().setBody(delta("ok") + "data: [DONE]\n\n"))

        collect(provider(), request())

        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("Hi", body.getJSONArray("messages").getJSONObject(0).get("content"))
    }

    @Test
    fun `request with images sends a multi-content final message`() {
        server.enqueue(MockResponse().setBody(delta("ok") + "data: [DONE]\n\n"))

        collect(
            provider(),
            request().copy(images = listOf("data:image/jpeg;base64,QUJD")),
        )

        val messages = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages")
        assertEquals(1, messages.length())
        val content = messages.getJSONObject(0).getJSONArray("content")
        assertEquals(2, content.length())
        assertEquals("text", content.getJSONObject(0).get("type"))
        assertEquals("Hi", content.getJSONObject(0).get("text"))
        assertEquals("image_url", content.getJSONObject(1).get("type"))
        assertEquals(
            "data:image/jpeg;base64,QUJD",
            content.getJSONObject(1).getJSONObject("image_url").get("url"),
        )
    }

    @Test
    fun `reasoning_content deltas stream as Reasoning chunks`() {
        server.enqueue(
            MockResponse().setBody(
                "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"think\"}}]}\n\n" +
                    delta("ans") +
                    "data: [DONE]\n\n",
            ),
        )

        val chunks = collect(provider(), request())

        assertEquals(
            listOf(ChatChunk.Reasoning("think"), ChatChunk.Delta("ans"), ChatChunk.Done),
            chunks,
        )
    }
    @Test
    fun `reasoning key is honored as fallback`() {
        server.enqueue(
            MockResponse().setBody(
                "data: {\"choices\":[{\"delta\":{\"reasoning\":\"hmm\"}}]}\n\n" +
                    delta("ans") +
                    "data: [DONE]\n\n",
            ),
        )

        assertEquals(
            listOf(ChatChunk.Reasoning("hmm"), ChatChunk.Delta("ans"), ChatChunk.Done),
            collect(provider(), request()),
        )
    }

    @Test
    fun `reasoning-only stream without answer content fails with InvalidResponse`() {
        server.enqueue(
            MockResponse().setBody(
                "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"thought hard\"}}]}\n\n" +
                    "data: [DONE]\n\n",
            ),
        )

        assertEquals(
            listOf(ChatChunk.Reasoning("thought hard"), ChatChunk.Failure(ProviderError.InvalidResponse)),
            collect(provider(), request()),
        )
    }

    @Test
    fun `401 and 403 map to InvalidCredentials`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":{\"message\":\"bad key\"}}"))
        server.enqueue(MockResponse().setResponseCode(403).setBody("{}"))

        val unauthorized = collect(provider(), request())
        val forbidden = collect(provider(), request())

        assertEquals(ProviderError.InvalidCredentials, (unauthorized.single() as ChatChunk.Failure).error)
        assertEquals(ProviderError.InvalidCredentials, (forbidden.single() as ChatChunk.Failure).error)
    }

    @Test
    fun `429 maps to RateLimited and 500 to ServerError`() {
        server.enqueue(MockResponse().setResponseCode(429).setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(500).setBody("{}"))

        val rateLimited = collect(provider(), request())
        val serverError = collect(provider(), request())

        assertEquals(ProviderError.RateLimited, (rateLimited.single() as ChatChunk.Failure).error)
        assertEquals(ProviderError.ServerError, (serverError.single() as ChatChunk.Failure).error)
    }

    @Test
    fun `malformed delta maps to InvalidResponse`() {
        server.enqueue(MockResponse().setBody("data: {not json}\n\n"))

        val chunks = collect(provider(), request())

        assertEquals(1, chunks.size)
        assertEquals(ProviderError.InvalidResponse, (chunks.single() as ChatChunk.Failure).error)
    }

    @Test
    fun `CRLF framing streams cleanly`() {
        server.enqueue(
            MockResponse().setBody(
                "data: {\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}\r\n\r\n" +
                    "data: [DONE]\r\n\r\n",
            ),
        )

        val chunks = collect(provider(), request())

        assertEquals(listOf(ChatChunk.Delta("Hi"), ChatChunk.Done), chunks)
    }

    @Test
    fun `read timeout maps to Timeout and completes normally`() {
        server.enqueue(MockResponse().setBody("data: [DONE]\n\n").setBodyDelay(5, TimeUnit.SECONDS))

        val chunks = collect(provider(readTimeoutMs = 150), request())

        assertEquals(1, chunks.size)
        assertEquals(ProviderError.Timeout, (chunks.single() as ChatChunk.Failure).error)
    }

    @Test
    fun `empty stream maps to InvalidResponse`() {
        server.enqueue(MockResponse().setBody("data: [DONE]\n\n"))
        server.enqueue(MockResponse().setBody(""))

        val doneOnly = collect(provider(), request())
        val immediateEof = collect(provider(), request())

        assertEquals(ProviderError.InvalidResponse, (doneOnly.single() as ChatChunk.Failure).error)
        assertEquals(ProviderError.InvalidResponse, (immediateEof.single() as ChatChunk.Failure).error)
    }

    @Test
    fun `request has model messages stream and single Authorization header`() {
        server.enqueue(MockResponse().setBody(delta("ok") + "data: [DONE]\n\n"))

        collect(provider(), request())

        val recorded = server.takeRequest()
        assertEquals("/v1/chat/completions", recorded.path)

        val body = recorded.body.readUtf8()
        val json = org.json.JSONObject(body)
        assertEquals("request-model", json.getString("model"))
        assertEquals(true, json.getBoolean("stream"))
        val messages = json.getJSONArray("messages")
        assertEquals(1, messages.length())
        assertEquals("user", messages.getJSONObject(0).getString("role"))
        assertEquals("Hi", messages.getJSONObject(0).getString("content"))
        assertFalse(json.has("temperature"))
        assertFalse(json.has("max_tokens"))

        assertEquals(1, recorded.headers.values("Authorization").size)
        assertTrue(recorded.headers.values("Authorization").single().startsWith("Bearer "))
        assertEquals("application/json; charset=utf-8", recorded.getHeader("Content-Type"))
    }

    @Test
    fun `cancelling mid-stream stops reading and completes promptly`() = runBlocking {
        val body = buildString {
            repeat(300) { index -> append(delta("chunk $index")) }
        }
        // ~64 bytes every 25ms keeps the stream open for seconds.
        server.enqueue(MockResponse().setBody(body).throttleBody(64, 25, TimeUnit.MILLISECONDS))

        val received = Collections.synchronizedList(mutableListOf<ChatChunk>())
        val job = launch(Dispatchers.IO) {
            provider().stream(request()).collect { received += it }
        }

        withTimeout(10_000) { while (received.isEmpty()) delay(10) }
        assertTrue(received.first() is ChatChunk.Delta)

        job.cancel()
        withTimeout(5_000) { job.join() } // prompt completion after cancel

        val sizeAfterJoin = received.size
        delay(300)
        assertEquals("no reads after cancellation", sizeAfterJoin, received.size)
        assertTrue("stopped mid-stream, not at the end", sizeAfterJoin < 300)
    }

    @Test
    fun `over-long line maps to InvalidResponse`() {
        val overLong = "x".repeat(128 * 1024)
        server.enqueue(MockResponse().setBody(overLong + "\n\n"))

        val chunks = collect(provider(), request())

        assertEquals(1, chunks.size)
        assertEquals(ProviderError.InvalidResponse, (chunks.single() as ChatChunk.Failure).error)
    }

    @Test
    fun `oversize error body maps without detail`() {
        server.enqueue(
            MockResponse().setResponseCode(418)
                .setBody("{\"error\":{\"message\":\"small\"}}"),
        )
        server.enqueue(
            MockResponse().setResponseCode(418)
                .setBody("{\"error\":{\"message\":\"" + "x".repeat(128 * 1024) + "\"}}"),
        )

        val small = collect(provider(), request())
        val oversize = collect(provider(), request())

        assertEquals(ProviderError.Unknown, (small.single() as ChatChunk.Failure).error)
        assertEquals("small", (small.single() as ChatChunk.Failure).detail)
        assertEquals(ProviderError.Unknown, (oversize.single() as ChatChunk.Failure).error)
        assertNull((oversize.single() as ChatChunk.Failure).detail)
    }

    @Test
    fun `event error frame settles a failure`() {
        server.enqueue(
            MockResponse().setBody("event: error\ndata: {\"error\":{\"message\":\"overloaded\"}}\n\n"),
        )
        server.enqueue(MockResponse().setBody("event: error\ndata: boom\n\n"))

        val withJson = collect(provider(), request())
        val plain = collect(provider(), request())

        val jsonFailure = withJson.single() as ChatChunk.Failure
        assertEquals(ProviderError.Unknown, jsonFailure.error)
        assertEquals("overloaded", jsonFailure.detail)
        assertEquals(ProviderError.ServerError, (plain.single() as ChatChunk.Failure).error)
    }

    @Test
    fun `non-ascii deltas survive byte-level chunked delivery`() {
        val body = Buffer().writeUtf8(
            "data: {\"choices\":[{\"delta\":{\"content\":\"你好，世界\"}}]}\n\n" +
                "data: [DONE]\n\n",
        )
        server.enqueue(MockResponse().setChunkedBody(body, 1))

        val chunks = collect(provider(), request())

        assertEquals(listOf(ChatChunk.Delta("你好，世界"), ChatChunk.Done), chunks)
    }

    @Test
    fun `final frame without blank line survives stream close`() {
        server.enqueue(
            MockResponse().setBody(
                delta("Hello") + "data: {\"choices\":[{\"delta\":{\"content\":\" world\"}}]}\n",
            ),
        )

        val chunks = collect(provider(), request())

        assertEquals(listOf(ChatChunk.Delta("Hello"), ChatChunk.Delta(" world"), ChatChunk.Done), chunks)
    }

    @Test
    fun `large multi-kilobyte streamed response arrives complete`() {
        val chunkText = "x".repeat(2 * 1024)
        val body = buildString {
            repeat(200) { append(delta(chunkText)) }
            append("data: [DONE]\n\n")
        }
        server.enqueue(MockResponse().setBody(body))

        val chunks = collect(provider(), request())

        val deltas = chunks.filterIsInstance<ChatChunk.Delta>()
        assertEquals(200, deltas.size)
        assertEquals(200 * chunkText.length, deltas.sumOf { it.text.length })
        assertTrue(deltas.all { it.text == chunkText })
        assertEquals(ChatChunk.Done, chunks.last())
    }

    @Test
    fun `400 and 404 map to Unknown with provider detail`() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("{\"error\":{\"message\":\"bad request\"}}"))
        server.enqueue(MockResponse().setResponseCode(404).setBody("{\"error\":{\"message\":\"no such model\"}}"))

        val badRequest = collect(provider(), request())
        val notFound = collect(provider(), request())

        val badFailure = badRequest.single() as ChatChunk.Failure
        assertEquals(ProviderError.Unknown, badFailure.error)
        assertEquals("bad request", badFailure.detail)
        val notFoundFailure = notFound.single() as ChatChunk.Failure
        assertEquals(ProviderError.Unknown, notFoundFailure.error)
        assertEquals("no such model", notFoundFailure.detail)
    }

    @Test
    fun `unparseable base url maps to Unknown`() {
        val broken = OpenAICompatibleProvider(
            client = OkHttpClient(),
            baseUrl = "not a url",
            apiKey = "sk-test-key",
            model = "m",
        )

        val chunks = collect(broken, request())

        assertEquals(ProviderError.Unknown, (chunks.single() as ChatChunk.Failure).error)
    }

    @Test
    fun `unreachable host maps to NetworkUnavailable`() {
        val unreachable = OpenAICompatibleProvider(
            client = OkHttpClient.Builder()
                .callTimeout(5, TimeUnit.SECONDS)
                .build(),
            // Port 9 (discard) is closed everywhere the tests run.
            baseUrl = "http://127.0.0.1:9/v1",
            apiKey = "sk-test-key",
            model = "m",
        )

        val chunks = collect(unreachable, request())

        assertEquals(ProviderError.NetworkUnavailable, (chunks.single() as ChatChunk.Failure).error)
    }

    @Test
    fun `connection loss mid-stream maps to NetworkUnavailable after partial deltas`() {
        val body = Buffer().writeUtf8(
            delta("partial") + delta(" more") + delta(" content"),
        )
        server.enqueue(
            MockResponse()
                .setChunkedBody(body, 16)
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )

        val chunks = collect(provider(), request())

        assertFalse(chunks.contains(ChatChunk.Done))
        assertEquals(ProviderError.NetworkUnavailable, (chunks.last() as ChatChunk.Failure).error)
        assertTrue("deltas arrived before the drop", chunks.dropLast(1).all { it is ChatChunk.Delta })
    }
}
