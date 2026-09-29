package com.assistant.app.ui.settings

import androidx.compose.runtime.Composable
import com.assistant.app.R

internal val HELP_SECTIONS = listOf(
    LegalSection(R.string.help_add_provider_title, R.string.help_add_provider_body),
    LegalSection(R.string.help_api_key_title, R.string.help_api_key_body),
    LegalSection(R.string.help_test_title, R.string.help_test_body),
    LegalSection(R.string.help_invalid_key_title, R.string.help_invalid_key_body),
    LegalSection(R.string.help_endpoint_title, R.string.help_endpoint_body),
    LegalSection(R.string.help_model_title, R.string.help_model_body),
    LegalSection(R.string.help_voice_title, R.string.help_voice_body),
    LegalSection(R.string.help_attachments_title, R.string.help_attachments_body),
    LegalSection(R.string.help_search_title, R.string.help_search_body),
    LegalSection(R.string.help_contact_title, R.string.help_contact_body),
)

@Composable
internal fun HelpPage() {
    LegalPage(introRes = R.string.help_intro, sections = HELP_SECTIONS)
}
