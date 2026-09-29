package com.assistant.app.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.assistant.app.R
import com.assistant.app.ui.components.AppIcons

internal data class LegalSection(val titleRes: Int, val bodyRes: Int)

internal val PRIVACY_SECTIONS = listOf(
    LegalSection(R.string.privacy_local_title, R.string.privacy_local_body),
    LegalSection(R.string.privacy_provider_title, R.string.privacy_provider_body),
    LegalSection(R.string.privacy_search_title, R.string.privacy_search_body),
    LegalSection(R.string.privacy_voice_title, R.string.privacy_voice_body),
    LegalSection(R.string.privacy_analytics_title, R.string.privacy_analytics_body),
    LegalSection(R.string.privacy_deletion_title, R.string.privacy_deletion_body),
)

internal val TERMS_SECTIONS = listOf(
    LegalSection(R.string.terms_byok_title, R.string.terms_byok_body),
    LegalSection(R.string.terms_providers_title, R.string.terms_providers_body),
    LegalSection(R.string.terms_cost_title, R.string.terms_cost_body),
    LegalSection(R.string.terms_content_title, R.string.terms_content_body),
    LegalSection(R.string.terms_search_title, R.string.terms_search_body),
    LegalSection(R.string.terms_availability_title, R.string.terms_availability_body),
    LegalSection(R.string.terms_conduct_title, R.string.terms_conduct_body),
)

/**
 * One heading, one block of text per [LegalSection]. Sections are plain
 * scrolling text, not cards, so a long policy stays readable.
 */
@Composable
internal fun LegalPage(introRes: Int, sections: List<LegalSection>) {
    Text(
        text = stringResource(introRes),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    sections.forEach { section ->
        SettingsSectionHeader(text = stringResource(section.titleRes))
        Text(
            text = stringResource(section.bodyRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    SettingsSectionHeader(text = stringResource(R.string.legal_todo_header))
    SettingsCard {
        SettingsRow(
            label = stringResource(R.string.legal_todo_label),
            icon = AppIcons.Flag,
            description = stringResource(
                if (sections === TERMS_SECTIONS) R.string.legal_todo_terms else R.string.legal_todo_privacy,
            ),
        )
    }
}
