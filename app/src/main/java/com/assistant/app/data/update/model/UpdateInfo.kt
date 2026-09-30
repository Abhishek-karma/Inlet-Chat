package com.assistant.app.data.update.model

/**
 * Metadata for a released version fetched from GitHub Releases.
 */
data class UpdateInfo(
    val latestVersion: String,
    val releaseTitle: String,
    val releaseNotes: String,
    val htmlUrl: String,
    val downloadUrl: String,
    val publishedAt: String? = null,
)

/**
 * Result returned by the [com.assistant.app.data.update.UpdateChecker].
 */
sealed interface UpdateCheckResult {
    data class Available(val updateInfo: UpdateInfo) : UpdateCheckResult
    data class UpToDate(val currentVersion: String) : UpdateCheckResult
    data class Error(val message: String) : UpdateCheckResult
}

/**
 * Observable UI state for update status.
 */
sealed interface UpdateStatus {
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data class Available(val info: UpdateInfo) : UpdateStatus
    data class UpToDate(val currentVersion: String) : UpdateStatus
    data class Error(val message: String) : UpdateStatus
}
