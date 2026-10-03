package com.assistant.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DrawerState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.assistant.app.R
import com.assistant.app.ui.theme.AppCodeFontFamily
import com.assistant.app.ui.theme.AppShape
import com.assistant.app.ui.theme.AppSpacing

/** Narrower than the Material default (360 dp) so the drawer stays compact. */
private val DRAWER_WIDTH = 300.dp

/** One row of the drawer's model switcher. */
data class ChatModelOption(val id: Long, val model: String, val providerName: String)

private enum class DrawerDestination(val icon: Int, val labelRes: Int) {
    NewChat(AppIcons.Add, R.string.drawer_new_chat),
    History(AppIcons.History, R.string.drawer_history),
    Settings(AppIcons.Settings, R.string.drawer_settings),
}

/**
 * Inlet Chat App Drawer: Brand presence, the active model with its switcher,
 * and primary destinations.
 */
@Composable
fun AppDrawer(
    drawerState: DrawerState,
    activeModel: String?,
    isNewChat: Boolean,
    onNewChat: () -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
    savedModels: List<ChatModelOption> = emptyList(),
    activeModelId: Long? = null,
    onModelSelected: ((Long) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    var switcherOpen by remember { mutableStateOf(false) }
    val canSwitch = onModelSelected != null && savedModels.isNotEmpty()
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surface,
                drawerShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
                modifier = Modifier.width(DRAWER_WIDTH),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(horizontal = AppSpacing.lg, vertical = AppSpacing.xl),
                ) {
                    // Inlet Brand Header
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = AppSpacing.xs),
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
                        Column {
                            Text(
                                text = stringResource(R.string.app_name),
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "Radiant intelligence, pure thought",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        modifier = Modifier.padding(vertical = AppSpacing.lg),
                    )

                    DrawerDestination.entries.forEach { destination ->
                        val selected = destination == DrawerDestination.NewChat && isNewChat
                        NavigationDrawerItem(
                            icon = {
                                Icon(
                                    painter = painterResource(destination.icon),
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                            },
                            label = {
                                Text(
                                    text = stringResource(destination.labelRes),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                            },
                            selected = selected,
                            onClick = {
                                when (destination) {
                                    DrawerDestination.NewChat -> onNewChat()
                                    DrawerDestination.History -> onHistory()
                                    DrawerDestination.Settings -> onSettings()
                                }
                            },
                            shape = AppShape.pill,
                            colors = NavigationDrawerItemDefaults.colors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                            modifier = Modifier.padding(vertical = AppSpacing.xxs),
                        )
                    }

                    Spacer(Modifier.weight(1f))

                    if (!activeModel.isNullOrBlank()) {
                        Surface(
                            shape = AppShape.medium,
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                    AppShape.medium,
                                )
                                .clickable(
                                    enabled = canSwitch,
                                    onClickLabel = stringResource(R.string.cd_switch_model),
                                    role = Role.Button,
                                ) {
                                    switcherOpen = true
                                },
                        ) {
                            Row(
                                modifier = Modifier.padding(AppSpacing.md),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary),
                                )
                                Spacer(Modifier.width(AppSpacing.sm))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.drawer_active_model),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        text = activeModel,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = AppCodeFontFamily,
                                            fontWeight = FontWeight.Medium,
                                        ),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (canSwitch) {
                                    Icon(
                                        imageVector = Icons.Filled.KeyboardArrowDown,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }

                    if (switcherOpen && savedModels.isNotEmpty()) {
                        InletActionSheet(
                            actions = savedModels.map { model ->
                                InletAction(
                                    label = model.model,
                                    trailing = model.providerName,
                                    selected = model.id == activeModelId,
                                    onClick = {
                                        if (model.id != activeModelId) {
                                            onModelSelected?.invoke(model.id)
                                        }
                                    },
                                )
                            },
                            onDismiss = { switcherOpen = false },
                        )
                    }
                }
            }
        },
        content = content,
    )
}
