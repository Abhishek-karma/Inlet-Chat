package com.assistant.app

import com.assistant.app.llm.GeminiProvider
import com.assistant.app.llm.LlmProvider
import com.assistant.app.llm.model.ChatChunk
import com.assistant.app.llm.model.ChatRequest
import com.assistant.app.llm.model.ProviderError
import com.assistant.app.llm.model.ReasoningConfig
import com.assistant.app.llm.model.ReasoningEffort
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GeminiProviderTest {

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

    private fun provider(readTimeoutMs: Long = 60_000): GeminiProvider =
        GeminiProvider(
            client = OkHttpClient.Builder()
                .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
                .build(),
            apiKey = "AIzaSyTestKey123",
            model = "gemini-2.5-flash",
            baseUrl = server.url("/v1beta").toString(),
        )

    private fun request(model: String = "gemini-2.5-flash") =
        ChatRequest(model = model, messages = listOf(Role.USER to "Hello Gemini"))

    private fun collect(provider: LlmProvider, request: ChatRequest): List<ChatChunk> =
        runBlocking {
            val chunks = mutableListOf<ChatChunk>()
            provider.stream(request).collect { chunks += it }
            chunks
        }

    private fun geminiChunk(text: String) =
        "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"$text\"}],\"role\":\"model\"}}]}\n\n"

    private fun geminiThoughtChunk(thought: String, text: String? = null): String {
        val parts = mutableListOf<String>()
        parts += "{\"thought\":true,\"text\":\"$thought\"}"
        if (text != null) {
            parts += "{\"text\":\"$text\"}"
        }
        return "data: {\"candidates\":[{\"content\":{\"parts\":[${parts.joinToString(",")}],\"role\":\"model\"}}]}\n\n"
    }

    @Test
    fun `happy path streams deltas then Done`() {
        server.enqueue(
            MockResponse().setBody(
                geminiChunk("Hello") +
                    geminiChunk(" from Gemini") +
                    "data: {\"candidates\":[{\"finishReason\":\"STOP\"}]}\n\n",
            ),
        )

        val chunks = collect(provider(), request())

        assertEquals(
            listOf(ChatChunk.Delta("Hello"), ChatChunk.Delta(" from Gemini"), ChatChunk.Done),
            chunks,
        )
    }

    @Test
    fun `streams model reasoning thought parts`() {
        server.enqueue(
            MockResponse().setBody(
                geminiThoughtChunk("Analyzing problem...", "The answer is ") +
                    geminiChunk("42.") +
                    "data: {\"candidates\":[{\"finishReason\":\"STOP\"}]}\n\n",
            ),
        )

        val chunks = collect(provider(), request())

        assertEquals(
            listOf(
                ChatChunk.Reasoning("Analyzing problem..."),
                ChatChunk.Delta("The answer is "),
                ChatChunk.Delta("42."),
                ChatChunk.Done,
            ),
            chunks,
        )
    }

    @Test
    fun `sends x-goog-api-key header and formats contents`() {
        server.enqueue(
            MockResponse().setBody(
                geminiChunk("ok") + "data: {\"candidates\":[{\"finishReason\":\"STOP\"}]}\n\n",
            ),
        )

        val req = ChatRequest(
            model = "gemini-2.5-flash",
            messages = listOf(
                Role.SYSTEM to "You are a concise assistant.",
                Role.USER to "What is 2+2?",
                Role.ASSISTANT to "4",
                Role.USER to "And times 2?",
            ),
        )

        collect(provider(), req)

        val recorded = server.takeRequest()
        assertEquals("AIzaSyTestKey123", recorded.getHeader("x-goog-api-key"))
        assertTrue(recorded.path!!.contains("models/gemini-2.5-flash:streamGenerateContent?alt=sse"))

        val body = JSONObject(recorded.body.readUtf8())
        // Verify systemInstruction
        val sysParts = body.getJSONObject("systemInstruction").getJSONArray("parts")
        assertEquals("You are a concise assistant.", sysParts.getJSONObject(0).getString("text"))

        // Verify contents
        val contents = body.getJSONArray("contents")
        assertEquals(3, contents.length())
        assertEquals("user", contents.getJSONObject(0).getString("role"))
        assertEquals("What is 2+2?", contents.getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text"))
        assertEquals("model", contents.getJSONObject(1).getString("role"))
        assertEquals("4", contents.getJSONObject(1).getJSONArray("parts").getJSONObject(0).getString("text"))
        assertEquals("user", contents.getJSONObject(2).getString("role"))
        assertEquals("And times 2?", contents.getJSONObject(2).getJSONArray("parts").getJSONObject(0).getString("text"))
    }

    @Test
    fun `formats inline image attachments for multimodal requests`() {
        server.enqueue(
            MockResponse().setBody(
                geminiChunk("I see an image.") + "data: {\"candidates\":[{\"finishReason\":\"STOP\"}]}\n\n",
            ),
        )

        val req = ChatRequest(
            model = "gemini-2.5-flash",
            messages = listOf(Role.USER to "Describe this"),
            images = listOf("data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="),
        )

        collect(provider(), req)

        val recorded = server.takeRequest()
        val body = JSONObject(recorded.body.readUtf8())
        val parts = body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
        assertEquals(2, parts.length())
        assertEquals("Describe this", parts.getJSONObject(0).getString("text"))

        val inlineData = parts.getJSONObject(1).getJSONObject("inlineData")
        assertEquals("image/png", inlineData.getString("mimeType"))
        assertTrue(inlineData.getString("data").startsWith("iVBORw0KGgo"))
    }

    @Test
    fun `valid gemini-3_6-flash configuration streams correctly`() {
        server.enqueue(
            MockResponse().setBody(
                geminiChunk("Gemini 3.6 response") +
                    "data: {\"candidates\":[{\"finishReason\":\"STOP\"}]}\n\n",
            ),
        )

        val gemini36Provider = GeminiProvider(
            client = OkHttpClient(),
            apiKey = "AIzaSyValidKey",
            model = "gemini-3.6-flash",
            baseUrl = server.url("/v1beta").toString(),
        )
        val req = ChatRequest(model = "gemini-3.6-flash", messages = listOf(Role.USER to "Hello"))
        val chunks = collect(gemini36Provider, req)

        assertEquals(
            listOf(ChatChunk.Delta("Gemini 3.6 response"), ChatChunk.Done),
            chunks,
        )

        val recorded = server.takeRequest()
        assertEquals("AIzaSyValidKey", recorded.getHeader("x-goog-api-key"))
        assertTrue(recorded.path!!.contains("models/gemini-3.6-flash:streamGenerateContent?alt=sse"))
    }

    @Test
    fun `thinking budget maps into generationConfig thinkingConfig`() {
        server.enqueue(MockResponse().setBody(geminiChunk("ok") + "data: [DONE]\n\n"))

        collect(provider(), request().copy(reasoning = ReasoningConfig.Budget(4096)))

        val body = JSONObject(server.takeRequest().body.readUtf8())
        val thinkingConfig = body.getJSONObject("generationConfig").getJSONObject("thinkingConfig")
        assertEquals(4096, thinkingConfig.getInt("thinkingBudget"))
    }

    @Test
    fun `off maps to thinkingBudget zero and effort maps to thinkingLevel`() {
        server.enqueue(MockResponse().setBody(geminiChunk("ok") + "data: [DONE]\n\n"))
        server.enqueue(MockResponse().setBody(geminiChunk("ok") + "data: [DONE]\n\n"))

        collect(provider(), request().copy(reasoning = ReasoningConfig.Off))
        collect(provider(), request().copy(reasoning = ReasoningConfig.Effort(ReasoningEffort.LOW)))

        val offBody = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals(0, offBody.getJSONObject("generationConfig").getJSONObject("thinkingConfig").getInt("thinkingBudget"))

        val effortBody = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("low", effortBody.getJSONObject("generationConfig").getJSONObject("thinkingConfig").getString("thinkingLevel"))
    }

    @Test
    fun `auto and null reasoning send no thinkingConfig`() {
        server.enqueue(MockResponse().setBody(geminiChunk("ok") + "data: [DONE]\n\n"))
        server.enqueue(MockResponse().setBody(geminiChunk("ok") + "data: [DONE]\n\n"))

        collect(provider(), request().copy(reasoning = ReasoningConfig.Auto))
        collect(provider(), request())

        repeat(2) {
            val body = JSONObject(server.takeRequest().body.readUtf8())
            assertFalse(body.has("generationConfig"))
        }
    }

    @Test
    fun `maps invalid api key to InvalidCredentials`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody("""{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT"}}"""),
        )

        val chunks = collect(provider(), request())

        assertEquals(1, chunks.size)
        val failure = chunks.single() as ChatChunk.Failure
        assertEquals(ProviderError.InvalidCredentials, failure.error)
    }

    @Test
    fun `maps 403 to InvalidCredentials`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setBody("""{"error":{"code":403,"message":"Method doesn't allow unregistered callers (callers without established identity).","status":"PERMISSION_DENIED"}}"""),
        )

        val chunks = collect(provider(), request())

        val failure = chunks.single() as ChatChunk.Failure
        assertEquals(ProviderError.InvalidCredentials, failure.error)
    }

    @Test
    fun `maps 429 quota exhausted to QuotaExceeded`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setBody("""{"error":{"code":429,"message":"Resource has been exhausted (e.g. check quota).","status":"RESOURCE_EXHAUSTED"}}"""),
        )

        val chunks = collect(provider(), request())

        val failure = chunks.single() as ChatChunk.Failure
        assertEquals(ProviderError.QuotaExceeded, failure.error)
    }

    @Test
    fun `maps 404 to ModelNotFound`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setBody("""{"error":{"code":404,"message":"models/nonexistent is not found.","status":"NOT_FOUND"}}"""),
        )

        val chunks = collect(provider(), request())

        val failure = chunks.single() as ChatChunk.Failure
        assertEquals(ProviderError.ModelNotFound, failure.error)
        assertTrue(failure.detail!!.contains("Model not found") || failure.detail!!.contains("nonexistent"))
    }

    @Test
    fun `maps 500 to ServerError`() {
        server.enqueue(MockResponse().setResponseCode(500))

        val chunks = collect(provider(), request())

        val failure = chunks.single() as ChatChunk.Failure
        assertEquals(ProviderError.ServerError, failure.error)
    }

    @Test
    fun `maps malformed json to InvalidResponse`() {
        server.enqueue(MockResponse().setBody("data: {invalid json\n\n"))

        val chunks = collect(provider(), request())

        assertEquals(1, chunks.size)
        val failure = chunks.single() as ChatChunk.Failure
        assertEquals(ProviderError.InvalidResponse, failure.error)
    }

    @Test
    fun `maps empty stream to InvalidResponse`() {
        server.enqueue(MockResponse().setBody(""))

        val chunks = collect(provider(), request())

        assertEquals(1, chunks.size)
        val failure = chunks.single() as ChatChunk.Failure
        assertEquals(ProviderError.InvalidResponse, failure.error)
    }

    @Test
    fun `maps read timeout to Timeout`() {
        server.enqueue(MockResponse().setBody(geminiChunk("slow")).setBodyDelay(3, TimeUnit.SECONDS))

        val slowProvider = provider(readTimeoutMs = 100)
        val chunks = collect(slowProvider, request())

        assertEquals(1, chunks.size)
        val failure = chunks.single() as ChatChunk.Failure
        assertEquals(ProviderError.Timeout, failure.error)
    }

    @Test
    fun `cancelling stream cancels http call`() = runBlocking {
        val body = buildString {
            repeat(300) { index -> append(geminiChunk("chunk $index")) }
        }
        server.enqueue(MockResponse().setBody(body).throttleBody(64, 25, TimeUnit.MILLISECONDS))

        val received = Collections.synchronizedList(mutableListOf<ChatChunk>())
        val job = launch(Dispatchers.IO) {
            provider().stream(request()).collect { received += it }
        }

        withTimeout(10_000) {
            while (received.isEmpty()) delay(10)
        }
        assertTrue(received.first() is ChatChunk.Delta)

        job.cancel()
        withTimeout(5_000) { job.join() }

        val sizeAfterJoin = received.size
        delay(300)
        assertEquals("no reads after cancellation", sizeAfterJoin, received.size)
        assertTrue("stopped mid-stream, not at the end", sizeAfterJoin < 300)
    }
}
