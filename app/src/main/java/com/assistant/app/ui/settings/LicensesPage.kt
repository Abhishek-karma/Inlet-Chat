package com.assistant.app.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.assistant.app.R
import com.assistant.app.ui.theme.AppCodeFontFamily

private const val LICENSES_ASSET = "licenses.txt"

@Composable
internal fun LicensesPage() {
    val context = LocalContext.current
    val text = remember {
        runCatching {
            context.assets.open(LICENSES_ASSET).bufferedReader().use { it.readText() }
        }.getOrNull()
    }
    Text(
        text = stringResource(R.string.licenses_intro),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (text == null) {
        Text(
            text = stringResource(R.string.licenses_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        return
    }
    val entries = remember(text) { LibraryLicenses.parse(text) }
    Text(
        text = stringResource(R.string.licenses_count, entries.size),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    entries.forEach { entry ->
        SettingsSectionHeader(text = entry.name)
        if (entry.license.isNotEmpty()) {
            Text(
                text = entry.license,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = AppCodeFontFamily),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
