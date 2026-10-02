package com.assistant.app.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.assistant.app.ui.theme.AppShape
import com.assistant.app.ui.theme.AppSpacing

import androidx.compose.ui.platform.testTag

/**
 * Horizontally scrollable chips for follow-up prompt suggestions.
 */
@Composable
fun SuggestionsRow(
    suggestions: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier
            .readingColumn()
            .padding(top = 0.dp, bottom = 0.dp),
        contentPadding = PaddingValues(horizontal = AppSpacing.lg),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
    ) {
        items(suggestions) { suggestion ->
            Surface(
                onClick = { onSelect(suggestion) },
                shape = AppShape.pill,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                modifier = Modifier.testTag("suggestion_chip"),
            ) {
                Text(
                    text = suggestion,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = AppSpacing.md, vertical = 6.dp),
                )
            }
        }
    }
}
