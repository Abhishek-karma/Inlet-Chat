package com.assistant.app.data.update

import com.assistant.app.data.update.model.UpdateCheckResult
import com.assistant.app.data.update.model.UpdateInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

interface UpdateChecker {
    suspend fun checkForUpdate(currentVersion: String): UpdateCheckResult
}

class GitHubUpdateChecker(
    private val client: OkHttpClient,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val endpoint: String = DEFAULT_ENDPOINT,
) : UpdateChecker {

    override suspend fun checkForUpdate(currentVersion: String): UpdateCheckResult = withContext(dispatcher) {
        val request = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/vnd.github.v3+json")
            .header("User-Agent", "L0/$currentVersion")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> {
                        val body = response.body?.string()
                            ?: return@withContext UpdateCheckResult.Error("Empty response from update server")
                        val json = JSONObject(body)
                        val tagName = json.optString("tag_name")
                        val cleanVersion = tagName.removePrefix("v").removePrefix("V")
                        val releaseTitle = json.optString("name").ifBlank { tagName }
                        val releaseNotes = json.optString("body")
                        val htmlUrl = json.optString("html_url")
                        val publishedAt = json.optString("published_at").takeIf { it.isNotBlank() }

                        var downloadUrl = htmlUrl
                        val assets = json.optJSONArray("assets")
                        if (assets != null) {
                            for (i in 0 until assets.length()) {
                                val asset = assets.optJSONObject(i) ?: continue
                                val assetName = asset.optString("name")
                                if (assetName.endsWith(".apk", ignoreCase = true)) {
                                    val browserDownloadUrl = asset.optString("browser_download_url")
                                    if (browserDownloadUrl.isNotBlank()) {
                                        downloadUrl = browserDownloadUrl
                                        break
                                    }
                                }
                            }
                        }

                        val isNewer = SemanticVersion.isNewer(latest = cleanVersion, current = currentVersion)
                        if (isNewer) {
                            UpdateCheckResult.Available(
                                UpdateInfo(
                                    latestVersion = cleanVersion,
                                    releaseTitle = releaseTitle,
                                    releaseNotes = releaseNotes,
                                    htmlUrl = htmlUrl,
                                    downloadUrl = downloadUrl,
                                    publishedAt = publishedAt,
                                ),
                            )
                        } else {
                            UpdateCheckResult.UpToDate(currentVersion)
                        }
                    }
                    404 -> UpdateCheckResult.UpToDate(currentVersion)
                    403 -> UpdateCheckResult.Error("Update check rate limited. Please try again later.")
                    else -> UpdateCheckResult.Error("Update server returned error (${response.code})")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            UpdateCheckResult.Error("Network error checking for updates: ${e.message ?: "unavailable"}")
        } catch (e: Exception) {
            UpdateCheckResult.Error("Failed to check for updates: ${e.message ?: "unknown error"}")
        }
    }

    companion object {
        const val REPO_OWNER = "Abhishek-karma"
        const val REPO_NAME = "Inlet-Chat"
        const val DEFAULT_ENDPOINT = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"
    }
}
