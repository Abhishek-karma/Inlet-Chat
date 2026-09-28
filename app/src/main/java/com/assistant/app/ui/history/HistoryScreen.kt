package com.assistant.app.ui.history

import android.content.Intent
import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.assistant.app.R
import com.assistant.app.ui.components.AppIcons
import com.assistant.app.ui.components.AssistantTopBar
import com.assistant.app.ui.components.NaraAction
import com.assistant.app.ui.components.NaraActionSheet
import com.assistant.app.ui.theme.AppDimens
import com.assistant.app.ui.theme.AppShape
import com.assistant.app.ui.theme.AppSpacing
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

data class ConversationSummary(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val pinned: Boolean = false,
)

private const val GROUP_PINNED = 0
private const val GROUP_TODAY = 1
private const val GROUP_YESTERDAY = 2
private const val GROUP_PREVIOUS_7_DAYS = 3
private const val GROUP_OLDER = 4

private val groupLabels = intArrayOf(
    R.string.history_pinned,
    R.string.history_today,
    R.string.history_yesterday,
    R.string.history_previous_7_days,
    R.string.history_older,
)

/**
 * Nara History: Fast scanning, pinned prioritization, and contextual management.
 */
@Composable
fun HistoryScreen(
    conversations: List<ConversationSummary>,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onTogglePin: (String, Boolean) -> Unit,
    onRename: (String, String) -> Unit,
    shareText: suspend (String) -> String?,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onSearch: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingDelete by remember { mutableStateOf<ConversationSummary?>(null) }
    var renaming by remember { mutableStateOf<ConversationSummary?>(null) }

    fun share(id: String) {
        scope.launch {
            val text = shareText(id) ?: return@launch
            val intent = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, text)
            context.startActivity(
                Intent.createChooser(intent, context.getString(R.string.cd_share_conversation)),
            )
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            AssistantTopBar(
                title = stringResource(R.string.history_title),
                onBack = onBack,
                onSearch = onSearch,
            )
        },
    ) { innerPadding ->
        if (conversations.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = AppSpacing.xl),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(AppIcons.History),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                    Spacer(Modifier.height(AppSpacing.lg))
                    Text(
                        text = stringResource(R.string.history_empty),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(AppSpacing.xs))
                    Text(
                        text = stringResource(R.string.history_empty_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            val groups = remember(conversations) { groupConversations(conversations) }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.TopCenter,
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = AppDimens.maxContentWidth),
                    contentPadding = PaddingValues(bottom = AppSpacing.xxl),
                ) {
                    groups.forEach { (group, conversationsInGroup) ->
                        item(key = "header_$group") {
                            SectionHeader(stringResource(groupLabels[group]))
                        }
                        items(conversationsInGroup, key = { it.id }) { conversation ->
                            ConversationRow(
                                conversation = conversation,
                                timeText = timestampText(conversation.updatedAt, group),
                                onOpen = { onOpen(conversation.id) },
                                onTogglePin = onTogglePin,
                                onRequestRename = { renaming = conversation },
                                onRequestShare = { share(conversation.id) },
                                onRequestDelete = { pendingDelete = conversation },
                            )
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { conversation ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.history_delete_title)) },
            text = { Text(stringResource(R.string.history_delete_message, conversation.title)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(conversation.id)
                    pendingDelete = null
                }) {
                    Text(
                        text = stringResource(R.string.history_delete_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.history_delete_cancel))
                }
            },
        )
    }

    renaming?.let { conversation ->
        RenameDialog(
            initialTitle = conversation.title,
            onSave = { title ->
                onRename(conversation.id, title)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }
}

private fun groupConversations(
    conversations: List<ConversationSummary>,
): List<Pair<Int, List<ConversationSummary>>> {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val todayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
    val yesterdayStart = today.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val weekStart = today.minusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
    val buckets = List(groupLabels.size) { mutableListOf<ConversationSummary>() }
    conversations.forEach { conversation ->
        val group = when {
            conversation.pinned -> GROUP_PINNED
            conversation.updatedAt >= todayStart -> GROUP_TODAY
            conversation.updatedAt >= yesterdayStart -> GROUP_YESTERDAY
            conversation.updatedAt >= weekStart -> GROUP_PREVIOUS_7_DAYS
            else -> GROUP_OLDER
        }
        buckets[group].add(conversation)
    }
    return buckets
        .withIndex()
        .filter { it.value.isNotEmpty() }
        .map { it.index to it.value.toList() }
}

private fun timestampText(updatedAt: Long, group: Int): String =
    if (group == GROUP_TODAY) {
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
            .format(Instant.ofEpochMilli(updatedAt).atZone(ZoneId.systemDefault()))
    } else {
        DateUtils.getRelativeTimeSpanString(updatedAt).toString()
    }

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = AppSpacing.lg,
                top = AppSpacing.xl,
                end = AppSpacing.lg,
                bottom = AppSpacing.xs,
            )
            .semantics { },
    )
}

@Composable
private fun RenameDialog(
    initialTitle: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf(initialTitle) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.history_rename_title)) },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                singleLine = true,
                shape = AppShape.small,
                label = { Text(stringResource(R.string.history_rename_field)) },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(title) },
                enabled = title.isNotBlank(),
            ) {
                Text(stringResource(R.string.history_rename_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.history_delete_cancel))
            }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    conversation: ConversationSummary,
    timeText: String,
    onOpen: () -> Unit,
    onTogglePin: (String, Boolean) -> Unit,
    onRequestRename: () -> Unit,
    onRequestShare: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val deleteLabel = stringResource(R.string.cd_delete_conversation)

    Box(modifier = Modifier.padding(horizontal = AppSpacing.md, vertical = 2.dp)) {
        Surface(
            shape = AppShape.small,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = onOpen,
                        onLongClick = { menuOpen = true },
                    )
                    .padding(horizontal = AppSpacing.md, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(
                            if (conversation.pinned) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(if (conversation.pinned) AppIcons.Pin else AppIcons.Chat),
                        contentDescription = null,
                        tint = if (conversation.pinned) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(16.dp),
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = AppSpacing.md),
                ) {
                    Text(
                        text = conversation.title,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Text(
                    text = timeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                )
            }
        }

        if (menuOpen) {
            NaraActionSheet(
                actions = listOf(
                    NaraAction(
                        label = stringResource(
                            if (conversation.pinned) R.string.menu_unpin else R.string.menu_pin,
                        ),
                        icon = AppIcons.Pin,
                        onClick = { onTogglePin(conversation.id, !conversation.pinned) },
                    ),
                    NaraAction(
                        label = stringResource(R.string.menu_rename),
                        icon = AppIcons.Edit,
                        onClick = onRequestRename,
                    ),
                    NaraAction(
                        label = stringResource(R.string.menu_share),
                        icon = AppIcons.Share,
                        onClick = onRequestShare,
                    ),
                    NaraAction(
                        label = stringResource(R.string.menu_delete),
                        icon = AppIcons.Trash,
                        onClick = onRequestDelete,
                        destructive = true,
                        onClickLabel = deleteLabel,
                    ),
                ),
                onDismiss = { menuOpen = false },
            )
        }
    }
}
