package com.assistant.app.data.update

import com.assistant.app.data.settings.AppPreferences
import com.assistant.app.data.update.model.UpdateCheckResult
import com.assistant.app.data.update.model.UpdateInfo
import com.assistant.app.data.update.model.UpdateStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Coordinates app update checks, notifications, periodic background checks,
 * and UI state.
 */
class UpdateManager(
    private val currentVersion: String,
    private val updateChecker: UpdateChecker,
    private val updateNotifier: UpdateNotifier,
    private val appPreferences: AppPreferences,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _updateStatus = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val updateStatus: StateFlow<UpdateStatus> = _updateStatus.asStateFlow()

    val autoCheckUpdates: Flow<Boolean> = appPreferences.autoCheckUpdates

    suspend fun setAutoCheckUpdates(enabled: Boolean) {
        appPreferences.setAutoCheckUpdates(enabled)
    }

    /**
     * Checks for updates.
     * @param manual If true, triggers check regardless of interval and notifies user.
     */
    suspend fun checkForUpdates(manual: Boolean = false): UpdateCheckResult {
        _updateStatus.value = UpdateStatus.Checking
        val result = updateChecker.checkForUpdate(currentVersion)
        when (result) {
            is UpdateCheckResult.Available -> {
                _updateStatus.value = UpdateStatus.Available(result.updateInfo)
                val lastNotified = appPreferences.lastNotifiedVersion.first()
                if (manual || lastNotified != result.updateInfo.latestVersion) {
                    updateNotifier.showUpdateNotification(result.updateInfo)
                    appPreferences.setLastNotifiedVersion(result.updateInfo.latestVersion)
                }
            }
            is UpdateCheckResult.UpToDate -> {
                _updateStatus.value = UpdateStatus.UpToDate(result.currentVersion)
            }
            is UpdateCheckResult.Error -> {
                _updateStatus.value = UpdateStatus.Error(result.message)
            }
        }
        appPreferences.setLastUpdateCheckTime(clock())
        return result
    }

    /**
     * Called during app startup. Performs a background update check if auto-check
     * is enabled and the check interval has elapsed.
     */
    fun checkOnLaunch() {
        scope.launch {
            val autoCheck = appPreferences.autoCheckUpdates.first()
            if (!autoCheck) return@launch

            val lastCheck = appPreferences.lastUpdateCheckTime.first()
            val now = clock()
            if (now - lastCheck >= CHECK_INTERVAL_MS) {
                checkForUpdates(manual = false)
            }
        }
    }

    fun dismissUpdate() {
        _updateStatus.value = UpdateStatus.Idle
    }

    companion object {
        const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L // 24 hours
    }
}
