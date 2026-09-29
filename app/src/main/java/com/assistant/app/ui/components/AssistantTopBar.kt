package com.assistant.app.ui.components

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
 * Inlet Top Bar: Rock-solid, stable navigation and model indicator without startup jitter.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onOpenDrawer: (() -> Unit)? = null,
    onNewChat: (() -> Unit)? = null,
    onSearch: (() -> Unit)? = null,
    onToggleVoiceOutput: (() -> Unit)? = null,
    voiceOutputEnabled: Boolean = false,
    activeProvider: ProviderEntity? = null,
    savedProviders: List<ProviderEntity> = emptyList(),
    onProviderSelected: ((Long) -> Unit)? = null,
) {
    val barColor = MaterialTheme.colorScheme.background
    val hairline = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)

    TopAppBar(
        modifier = modifier.drawBehind {
            drawLine(
                color = hairline,
                strokeWidth = 0.5.dp.toPx(),
                start = Offset(0f, size.height),
                end = Offset(size.width, size.height),
            )
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = barColor,
            scrolledContainerColor = barColor,
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
                onOpenDrawer != null -> IconButton(onClick = onOpenDrawer) {
                    Icon(
                        painter = painterResource(AppIcons.Menu),
                        contentDescription = stringResource(R.string.cd_open_drawer),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
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
                IconButton(onClick = onSearch) {
                    Icon(
                        painter = painterResource(AppIcons.Search),
                        contentDescription = stringResource(R.string.drawer_search),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (onToggleVoiceOutput != null) {
                IconButton(onClick = onToggleVoiceOutput) {
                    Icon(
                        painter = painterResource(
                            if (voiceOutputEnabled) AppIcons.SpeakerOn else AppIcons.SpeakerOff,
                        ),
                        contentDescription = stringResource(R.string.cd_toggle_speaker),
                        tint = if (voiceOutputEnabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            if (onNewChat != null) {
                IconButton(onClick = onNewChat) {
                    Icon(
                        painter = painterResource(AppIcons.Add),
                        contentDescription = stringResource(R.string.cd_new_chat),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        },
    )
}
