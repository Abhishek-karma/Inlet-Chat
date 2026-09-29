package com.assistant.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.assistant.app.R
import com.assistant.app.ui.components.AppIcons
import com.assistant.app.ui.theme.AppSpacing

@Composable
internal fun AboutPage(versionName: String, onOpen: (SettingsPage) -> Unit) {
    SettingsSectionHeader(text = stringResource(R.string.app_name), isFirst = true)
    SettingsCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.lg, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_inlet_logo),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.width(AppSpacing.md))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            )
        }
        Text(
            text = stringResource(R.string.settings_about_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = AppSpacing.lg, end = AppSpacing.lg, bottom = AppSpacing.md),
        )
        SettingsDivider()
        SettingsRow(
            label = stringResource(R.string.settings_version),
            icon = AppIcons.Info,
            value = versionName,
        )
    }

    SettingsSectionHeader(text = stringResource(R.string.settings_group_data))
    SettingsCard {
        SettingsNavRow(
            label = stringResource(R.string.settings_about_privacy),
            icon = AppIcons.Info,
            onClick = { onOpen(SettingsPage.Privacy) },
        )
        SettingsDivider()
        SettingsNavRow(
            label = stringResource(R.string.settings_about_terms),
            icon = AppIcons.Flag,
            onClick = { onOpen(SettingsPage.Terms) },
        )
    }

    SettingsSectionHeader(text = stringResource(R.string.settings_group_support))
    SettingsCard {
        SettingsNavRow(
            label = stringResource(R.string.settings_about_help),
            icon = AppIcons.Chat,
            onClick = { onOpen(SettingsPage.Help) },
        )
        SettingsDivider()
        SettingsNavRow(
            label = stringResource(R.string.settings_about_licenses),
            icon = AppIcons.Flag,
            onClick = { onOpen(SettingsPage.Licenses) },
        )
    }
}
