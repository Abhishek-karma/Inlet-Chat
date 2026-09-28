package com.assistant.app

import android.app.Activity
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import com.assistant.app.data.settings.AppTheme
import com.assistant.app.ui.AssistantNavHost
import com.assistant.app.ui.theme.ChatTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Target SDK 35 enforces edge-to-edge; enable it on older versions too
        // so inset handling is identical everywhere.
        enableEdgeToEdge()
        val container = (application as AssistantApp).container
        setContent {
            val appearance by container.appearance.collectAsState()
            val darkTheme = when (appearance) {
                AppTheme.SYSTEM -> isSystemInDarkTheme()
                AppTheme.LIGHT -> false
                AppTheme.DARK -> true
            }
            val view = LocalView.current
            SideEffect { applySystemBarIconAppearance(view, darkTheme) }
            val textSize by container.textSize.collectAsState()
            ChatTheme(darkTheme = darkTheme) {
                // The reading size multiplies the system font scale, so the
                // accessibility size setting keeps applying.
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, density.fontScale * textSize.scale),
                ) {
                    AssistantNavHost(
                        chatViewModelFactory = container.chatViewModelFactory(),
                        settingsViewModelFactory = container.settingsViewModelFactory(),
                        appPreferences = container.appPreferences,
                    )
                }
            }
        }
    }
}

/**
 * Forces the status and navigation bar icon contrast to match the app theme
 * rather than the system one: `enableEdgeToEdge` picks its contrast from the
 * system dark-mode flag, so the in-app override would otherwise leave dark
 * icons on a dark background. Light icons for [darkTheme], dark icons
 * otherwise.
 */
internal fun applySystemBarIconAppearance(view: View, darkTheme: Boolean) {
    val window = (view.context as Activity).window
    WindowCompat.getInsetsController(window, view).apply {
        isAppearanceLightStatusBars = !darkTheme
        isAppearanceLightNavigationBars = !darkTheme
    }
}
