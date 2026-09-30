package com.assistant.app

import com.assistant.app.data.update.GitHubUpdateChecker
import com.assistant.app.data.update.model.UpdateCheckResult
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UpdateCheckerTest {

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

    private fun checker(): GitHubUpdateChecker = GitHubUpdateChecker(
        client = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build(),
        dispatcher = Dispatchers.Unconfined,
        endpoint = server.url("/releases/latest").toString(),
    )

    @Test
    fun `when newer release exists returns UpdateCheckResult Available`() {
        val json = """
            {
                "tag_name": "v1.2.0",
                "name": "Version 1.2.0 Release",
                "body": "Fixed web search and added updates",
                "html_url": "https://github.com/Abhishek-karma/Inlet-Chat/releases/tag/v1.2.0",
                "assets": [
                    {
                        "name": "InletChat-1.2.0.apk",
                        "browser_download_url": "https://github.com/Abhishek-karma/Inlet-Chat/releases/download/v1.2.0/InletChat-1.2.0.apk"
                    }
                ]
            }
        """.trimIndent()

        server.enqueue(MockResponse().setResponseCode(200).setBody(json))

        val result = runBlocking { checker().checkForUpdate(currentVersion = "1.0.0") }

        assertTrue(result is UpdateCheckResult.Available)
        val available = result as UpdateCheckResult.Available
        assertEquals("1.2.0", available.updateInfo.latestVersion)
        assertEquals("Version 1.2.0 Release", available.updateInfo.releaseTitle)
        assertEquals("Fixed web search and added updates", available.updateInfo.releaseNotes)
        assertEquals(
            "https://github.com/Abhishek-karma/Inlet-Chat/releases/download/v1.2.0/InletChat-1.2.0.apk",
            available.updateInfo.downloadUrl,
        )
    }

    @Test
    fun `when same version exists returns UpToDate`() {
        val json = """
            {
                "tag_name": "v1.0.0",
                "name": "Initial Release",
                "body": "First public release",
                "html_url": "https://github.com/Abhishek-karma/Inlet-Chat/releases/tag/v1.0.0",
                "assets": []
            }
        """.trimIndent()

        server.enqueue(MockResponse().setResponseCode(200).setBody(json))

        val result = runBlocking { checker().checkForUpdate(currentVersion = "1.0.0") }

        assertTrue(result is UpdateCheckResult.UpToDate)
        assertEquals("1.0.0", (result as UpdateCheckResult.UpToDate).currentVersion)
    }

    @Test
    fun `when 404 returned returns UpToDate`() {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = runBlocking { checker().checkForUpdate(currentVersion = "1.0.0") }

        assertTrue(result is UpdateCheckResult.UpToDate)
    }

    @Test
    fun `when 403 returned returns Error`() {
        server.enqueue(MockResponse().setResponseCode(403))

        val result = runBlocking { checker().checkForUpdate(currentVersion = "1.0.0") }

        assertTrue(result is UpdateCheckResult.Error)
    }
}
