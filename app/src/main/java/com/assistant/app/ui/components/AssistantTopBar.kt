package com.assistant.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.assistant.app.R
import com.assistant.app.data.local.ProviderEntity
import com.assistant.app.ui.theme.AppShape
import com.assistant.app.ui.theme.AppSpacing

/**
 * Chat top bar: transparent at rest so the conversation stays the visual
 * priority; when content scrolls beneath it, a scrim and hairline fade in
 * to keep the title and icons readable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onOpenDrawer: (() -> Unit)? = null,
    onSearch: (() -> Unit)? = null,
    onToggleVoiceOutput: (() -> Unit)? = null,
    voiceOutputEnabled: Boolean = false,
    activeProvider: ProviderEntity? = null,
    savedProviders: List<ProviderEntity> = emptyList(),
    onProviderSelected: ((Long) -> Unit)? = null,
    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    val scrim = MaterialTheme.colorScheme.background.copy(alpha = 0.94f)
    val hairline = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)

    TopAppBar(
        modifier = modifier.drawBehind {
            val overlap = scrollBehavior?.state?.overlappedFraction ?: 0f
            if (overlap > 0.02f) {
                drawLine(
                    color = hairline.copy(alpha = hairline.alpha * overlap),
                    strokeWidth = 0.5.dp.toPx(),
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                )
            }
        },
        scrollBehavior = scrollBehavior,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = scrim,
        ),
        navigationIcon = {
            when {
                onBack != null -> IconButton(onClick = onBack) {
                    Icon(
                        painter = painterResource(AppIcons.ArrowLeft),
                        contentDescription = stringResource(R.string.cd_back),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                onOpenDrawer != null -> TopBarChip(
                    contentDescription = stringResource(R.string.cd_open_drawer),
                    icon = AppIcons.Menu,
                    onClick = onOpenDrawer,
                )
            }
        },
        title = {
            if (onBack != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                var pickerOpen by remember { mutableStateOf(false) }
                val isInteractive = onProviderSelected != null && savedProviders.size > 1

                Row(
                    modifier = Modifier
                        .height(36.dp)
                        .clip(AppShape.small)
                        .then(
                            if (isInteractive) {
                                Modifier.clickable { pickerOpen = true }
                            } else {
                                Modifier
                            },
                        )
                        .padding(horizontal = AppSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (isInteractive) {
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowDown,
                            contentDescription = stringResource(R.string.cd_switch_provider),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(start = 4.dp)
                                .size(18.dp),
                        )
                    }
                }

                if (pickerOpen && savedProviders.isNotEmpty()) {
                    NaraActionSheet(
                        actions = savedProviders.map { provider ->
                            NaraAction(
                                label = provider.name,
                                icon = AppIcons.Sparkle,
                                trailing = provider.model,
                                selected = if (provider.id == activeProvider?.id) true else null,
                                onClick = {
                                    if (provider.id != activeProvider?.id) {
                                        onProviderSelected?.invoke(provider.id)
                                    }
                                },
                            )
                        },
                        onDismiss = { pickerOpen = false },
                    )
                }
            }
        },
        actions = {
            if (onSearch != null) {
                TopBarChip(
                    contentDescription = stringResource(R.string.drawer_search),
                    icon = AppIcons.Search,
                    onClick = onSearch,
                )
            }
            if (onToggleVoiceOutput != null) {
                TopBarChip(
                    contentDescription = stringResource(R.string.cd_toggle_speaker),
                    icon = if (voiceOutputEnabled) AppIcons.SpeakerOn else AppIcons.SpeakerOff,
                    active = voiceOutputEnabled,
                    onClick = onToggleVoiceOutput,
                )
            }
        },
    )
}

/** Icon on a quiet pill container, matching the composer's action pills. */
@Composable
private fun TopBarChip(
    contentDescription: String,
    icon: Int,
    onClick: () -> Unit,
    active: Boolean = false,
) {
    val tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        onClick = onClick,
        shape = AppShape.pill,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.size(36.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(icon),
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
