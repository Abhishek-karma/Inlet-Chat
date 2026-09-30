package com.assistant.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.assistant.app.data.settings.AppPreferences
import com.assistant.app.data.update.UpdateChecker
import com.assistant.app.data.update.UpdateManager
import com.assistant.app.data.update.UpdateNotifier
import com.assistant.app.data.update.model.UpdateCheckResult
import com.assistant.app.data.update.model.UpdateInfo
import com.assistant.app.data.update.model.UpdateStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UpdateManagerTest {

    private lateinit var tempFile: File
    private lateinit var appPreferences: AppPreferences
    private val notifiedList = mutableListOf<UpdateInfo>()

    private val fakeNotifier = object : UpdateNotifier {
        override fun showUpdateNotification(updateInfo: UpdateInfo) {
            notifiedList.add(updateInfo)
        }
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        tempFile = File(context.filesDir, "test_${java.util.UUID.randomUUID()}.preferences_pb")
        appPreferences = AppPreferences(
            context = context,
            ioDispatcher = Dispatchers.Unconfined,
            dataStoreFile = tempFile,
        )
    }

    @After
    fun tearDown() {
        tempFile.delete()
    }

    @Test
    fun `manual check transitions state to Available and notifies`() = runBlocking {
        val updateInfo = UpdateInfo(
            latestVersion = "1.2.0",
            releaseTitle = "v1.2.0",
            releaseNotes = "New features",
            htmlUrl = "https://example.com/v1.2.0",
            downloadUrl = "https://example.com/v1.2.0.apk",
        )
        val fakeChecker = object : UpdateChecker {
            override suspend fun checkForUpdate(currentVersion: String): UpdateCheckResult {
                return UpdateCheckResult.Available(updateInfo)
            }
        }

        val manager = UpdateManager(
            currentVersion = "1.0.0",
            updateChecker = fakeChecker,
            updateNotifier = fakeNotifier,
            appPreferences = appPreferences,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )

        val result = manager.checkForUpdates(manual = true)
        assertTrue(result is UpdateCheckResult.Available)
        assertTrue(manager.updateStatus.value is UpdateStatus.Available)
        assertEquals(1, notifiedList.size)
        assertEquals("1.2.0", notifiedList.first().latestVersion)
        assertEquals("1.2.0", appPreferences.lastNotifiedVersion.first())
    }

    @Test
    fun `automatic check does not notify twice for same version`() = runBlocking {
        val updateInfo = UpdateInfo(
            latestVersion = "1.2.0",
            releaseTitle = "v1.2.0",
            releaseNotes = "New features",
            htmlUrl = "https://example.com/v1.2.0",
            downloadUrl = "https://example.com/v1.2.0.apk",
        )
        val fakeChecker = object : UpdateChecker {
            override suspend fun checkForUpdate(currentVersion: String): UpdateCheckResult {
                return UpdateCheckResult.Available(updateInfo)
            }
        }

        val manager = UpdateManager(
            currentVersion = "1.0.0",
            updateChecker = fakeChecker,
            updateNotifier = fakeNotifier,
            appPreferences = appPreferences,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )

        // Pre-set that 1.2.0 was already notified
        appPreferences.setLastNotifiedVersion("1.2.0")

        manager.checkForUpdates(manual = false)
        assertTrue(manager.updateStatus.value is UpdateStatus.Available)
        assertEquals(0, notifiedList.size) // Not notified again
    }
}
