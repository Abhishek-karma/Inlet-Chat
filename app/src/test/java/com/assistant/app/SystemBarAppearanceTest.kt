package com.assistant.app

import android.app.Activity
import android.view.View
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The in-app theme override must drive the status/navigation bar icon
 * contrast. `enableEdgeToEdge` alone reads the system dark-mode flag, so a
 * forced DARK theme on a light-mode device used to leave dark icons on a dark
 * background.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SystemBarAppearanceTest {

    private fun controller(activity: Activity, view: View) =
        WindowCompat.getInsetsController(activity.window, view)

    @Test
    fun darkThemeRequestsLightIcons() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val view = View(activity)

        applySystemBarIconAppearance(view, darkTheme = true)

        assertFalse(controller(activity, view).isAppearanceLightStatusBars)
        assertFalse(controller(activity, view).isAppearanceLightNavigationBars)
    }

    @Test
    fun lightThemeRequestsDarkIcons() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val view = View(activity)

        applySystemBarIconAppearance(view, darkTheme = false)

        assertTrue(controller(activity, view).isAppearanceLightStatusBars)
        assertTrue(controller(activity, view).isAppearanceLightNavigationBars)
    }

    @Test
    fun appearanceFollowsTheLastAppliedTheme() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val view = View(activity)

        applySystemBarIconAppearance(view, darkTheme = true)
        assertFalse(controller(activity, view).isAppearanceLightStatusBars)

        applySystemBarIconAppearance(view, darkTheme = false)
        assertTrue(controller(activity, view).isAppearanceLightStatusBars)
    }
}
