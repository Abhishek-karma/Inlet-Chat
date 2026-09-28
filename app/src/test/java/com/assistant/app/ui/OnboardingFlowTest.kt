package com.assistant.app.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.assistant.app.data.settings.AppPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OnboardingFlowTest {

    @Test
    fun `default onboarding state is incomplete`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AppPreferences(context)

        val done = prefs.onboardingDone.first()
        assertFalse(done)
    }

    @Test
    fun `completing onboarding updates preference state`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = AppPreferences(context)

        prefs.setOnboardingDone(true)

        val done = prefs.onboardingDone.first()
        assertTrue(done)
    }
}
