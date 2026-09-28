package com.assistant.app

import android.app.Application

/**
 * Application entry point. Owns the single [AppContainer] used for
 * dependency wiring across the app.
 */
class AssistantApp : Application() {
    val container: AppContainer = AppContainer(this)
}
