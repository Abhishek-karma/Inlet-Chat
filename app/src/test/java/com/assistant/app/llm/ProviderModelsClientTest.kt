package com.assistant.app.llm

import kotlinx.coroutines.test.runTest
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

/**
 * The model picker lists what the endpoint actually serves, so the client has
 * to parse the OpenAI-compatible `/models` envelope and turn anything
 * unexpected into a reported failure rather than a silent empty list.
 *
 * Robolectric supplies the real `org.json` implementation, which is not mocked
 * in a plain JVM test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProviderModelsClientTest {

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

    private fun client() = ProviderModelsClient(OkHttpClient())

    @Test
    fun parsesModelIdsSortedAndDeduped() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"data":[{"id":"gpt-4o"},{"id":"claude-3"},{"id":"gpt-4o"}]}""",
            ),
        )

        val models = client().listModels(server.url("/v1").toString(), "sk-1").getOrThrow()

        assertEquals(listOf("claude-3", "gpt-4o"), models)
    }

    @Test
    fun sendsBearerTokenToTheModelsEndpoint() = runTest {
        server.enqueue(MockResponse().setBody("""{"data":[]}"""))

        client().listModels(server.url("/v1").toString(), "sk-secret").getOrThrow()

        val request = server.takeRequest()
        assertEquals("/v1/models", request.path)
        assertEquals("Bearer sk-secret", request.getHeader("Authorization"))
    }

    @Test
    fun httpErrorIsReportedAsFailure() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = client().listModels(server.url("/v1").toString(), "bad")

        assertTrue(result.isFailure)
    }

    @Test
    fun malformedBodyIsReportedAsFailure() = runTest {
        server.enqueue(MockResponse().setBody("not json at all"))

        val result = client().listModels(server.url("/v1").toString(), "sk-1")

        assertTrue(result.isFailure)
    }

    @Test
    fun envelopeWithoutDataYieldsEmptyList() = runTest {
        server.enqueue(MockResponse().setBody("""{"object":"list"}"""))

        val models = client().listModels(server.url("/v1").toString(), "sk-1").getOrThrow()

        assertEquals(emptyList<String>(), models)
    }

    @Test
    fun blankBaseUrlFailsWithoutCallingTheServer() = runTest {
        val result = client().listModels("   ", "sk-1")

        assertTrue(result.isFailure)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun blankApiKeyFailsWithoutCallingTheServer() = runTest {
        val result = client().listModels(server.url("/v1").toString(), "  ")

        assertTrue(result.isFailure)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun blankBodyYieldsEmptyList() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(""))

        val models = client().listModels(server.url("/v1").toString(), "sk-1").getOrThrow()

        assertEquals(emptyList<String>(), models)
    }

    @Test
    fun parsesGeminiModelIdsFilteredAndStripped() = runTest {
        val geminiJson = """
        {
          "models": [
            {
              "name": "models/gemini-2.5-flash",
              "supportedGenerationMethods": ["generateContent"]
            },
            {
              "name": "models/gemini-embedding-001",
              "supportedGenerationMethods": ["embedContent"]
            },
            {
              "name": "models/gemini-2.5-pro",
              "supportedGenerationMethods": ["generateContent", "countTokens"]
            }
          ]
        }
        """.trimIndent()

        server.enqueue(MockResponse().setBody(geminiJson))

        val models = client().listModels(
            baseUrl = server.url("/").toString() + "generativelanguage.googleapis.com",
            apiKey = "AIzaSyKey",
        ).getOrThrow()

        assertEquals(listOf("gemini-2.5-flash", "gemini-2.5-pro"), models)

        val req = server.takeRequest()
        assertEquals("AIzaSyKey", req.getHeader("x-goog-api-key"))
        assertTrue(req.path!!.endsWith("/models"))
    }

    @Test
    fun geminiHttpErrorExtractsErrorMessage() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody("""{"error":{"code":400,"message":"API key not valid","status":"INVALID_ARGUMENT"}}"""),
        )

        val result = client().listModels(
            baseUrl = server.url("/").toString() + "generativelanguage.googleapis.com",
            apiKey = "bad-key",
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("API key not valid") == true)
    }
}
