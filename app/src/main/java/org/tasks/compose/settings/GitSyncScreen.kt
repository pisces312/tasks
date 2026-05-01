package org.tasks.compose.settings

import android.app.AlertDialog
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.tasks.R
import org.tasks.backup.GitSyncLogCollector
import org.tasks.backup.GitSyncResult
import org.tasks.backup.GitSyncStep

@Composable
fun GitSyncScreen(
    isEnabled: Boolean,
    repoUrl: String,
    branch: String,
    authorName: String,
    authorEmail: String,
    hasSshKey: Boolean,
    sshKeyInfo: String,
    lastSyncSummary: String,
    syncStep: GitSyncStep,
    syncResult: GitSyncResult?,
    logEntries: List<GitSyncLogCollector.LogEntry>,
    isLogExpanded: Boolean,
    onEnabledChanged: (Boolean) -> Unit,
    onRepoUrlChanged: (String) -> Unit,
    onBranchChanged: (String) -> Unit,
    onAuthorNameChanged: (String) -> Unit,
    onAuthorEmailChanged: (String) -> Unit,
    onSelectSshKey: () -> Unit,
    onDeleteSshKey: () -> Unit,
    onSyncNow: () -> Unit,
    onViewJson: () -> Unit,
    onResetRepo: () -> Unit,
    onClearResult: () -> Unit,
    onToggleLogExpanded: () -> Unit,
    onClearLogs: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(modifier = Modifier.height(SettingsContentPadding))

        // Enable toggle
        SettingsItemCard(modifier = Modifier.padding(horizontal = SettingsContentPadding)) {
            SwitchPreferenceRow(
                title = stringResource(R.string.git_sync_enabled),
                checked = isEnabled,
                onCheckedChange = onEnabledChanged,
            )
        }

        if (isEnabled) {
            Spacer(modifier = Modifier.height(SettingsContentPadding))

            // Repository configuration
            SectionHeader(
                title = stringResource(R.string.git_sync_repo_url),
                modifier = Modifier.padding(horizontal = SettingsContentPadding),
            )
            Column(
                modifier = Modifier.padding(horizontal = SettingsContentPadding),
                verticalArrangement = Arrangement.spacedBy(SettingsCardGap),
            ) {
                SettingsItemCard(position = CardPosition.First) {
                    TextPreferenceRow(
                        title = stringResource(R.string.git_sync_repo_url),
                        value = repoUrl,
                        hint = stringResource(R.string.git_sync_repo_url_hint),
                        onValueChange = onRepoUrlChanged,
                    )
                }
                SettingsItemCard(position = CardPosition.Middle) {
                    TextPreferenceRow(
                        title = stringResource(R.string.git_sync_branch),
                        value = branch,
                        hint = "main",
                        onValueChange = onBranchChanged,
                    )
                }
                SettingsItemCard(position = CardPosition.Middle) {
                    TextPreferenceRow(
                        title = stringResource(R.string.git_sync_author_name),
                        value = authorName,
                        onValueChange = onAuthorNameChanged,
                    )
                }
                SettingsItemCard(position = CardPosition.Last) {
                    TextPreferenceRow(
                        title = stringResource(R.string.git_sync_author_email),
                        value = authorEmail,
                        onValueChange = onAuthorEmailChanged,
                    )
                }
            }

            Spacer(modifier = Modifier.height(SettingsContentPadding))

            // SSH Key section
            SectionHeader(
                title = stringResource(R.string.git_sync_ssh_key),
                modifier = Modifier.padding(horizontal = SettingsContentPadding),
            )
            Column(
                modifier = Modifier.padding(horizontal = SettingsContentPadding),
                verticalArrangement = Arrangement.spacedBy(SettingsCardGap),
            ) {
                SettingsItemCard(position = CardPosition.First) {
                    PreferenceRow(
                        title = stringResource(R.string.git_sync_ssh_key_select),
                        onClick = onSelectSshKey,
                    )
                }
                if (hasSshKey) {
                    SettingsItemCard(position = CardPosition.Last) {
                        PreferenceRow(
                            title = sshKeyInfo,
                            summary = stringResource(R.string.git_sync_ssh_key_delete),
                            icon = Icons.Outlined.Delete,
                            onClick = onDeleteSshKey,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(SettingsContentPadding))

            // Sync actions
            SectionHeader(
                title = stringResource(R.string.git_sync_now),
                modifier = Modifier.padding(horizontal = SettingsContentPadding),
            )
            Column(
                modifier = Modifier.padding(horizontal = SettingsContentPadding),
                verticalArrangement = Arrangement.spacedBy(SettingsCardGap),
            ) {
                SettingsItemCard(position = CardPosition.First) {
                    PreferenceRow(
                        title = if (syncStep != GitSyncStep.IDLE) stringResource(syncStep.toLabelRes()) else stringResource(R.string.git_sync_now),
                        summary = lastSyncSummary,
                        enabled = syncStep == GitSyncStep.IDLE,
                        onClick = onSyncNow,
                    )
                }
                SettingsItemCard(position = CardPosition.Middle) {
                    PreferenceRow(
                        title = stringResource(R.string.git_sync_view_json),
                        icon = Icons.Outlined.Visibility,
                        enabled = syncStep == GitSyncStep.IDLE,
                        onClick = onViewJson,
                    )
                }
                SettingsItemCard(position = CardPosition.Last) {
                    PreferenceRow(
                        title = stringResource(R.string.git_sync_reset),
                        enabled = syncStep == GitSyncStep.IDLE,
                        onClick = onResetRepo,
                    )
                }
            }

            // Sync result
            syncResult?.let { result ->
                Spacer(modifier = Modifier.height(SettingsContentPadding))
                val message = when (result) {
                    is GitSyncResult.Success -> stringResource(R.string.git_sync_success, result.taskCount)
                    is GitSyncResult.NoChanges -> stringResource(R.string.git_sync_no_changes)
                    is GitSyncResult.Error -> stringResource(R.string.git_sync_error, result.message)
                }
                val color = when (result) {
                    is GitSyncResult.Success -> MaterialTheme.colorScheme.primary
                    is GitSyncResult.NoChanges -> MaterialTheme.colorScheme.primary
                    is GitSyncResult.Error -> MaterialTheme.colorScheme.error
                }
                Text(
                    text = message,
                    color = color,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = SettingsContentPadding),
                )
            }

            // Expandable Log Panel
            Spacer(modifier = Modifier.height(SettingsContentPadding))
            LogPanel(
                logEntries = logEntries,
                isExpanded = isLogExpanded,
                onToggleExpanded = onToggleLogExpanded,
                onClearLogs = onClearLogs,
            )
        }

        Spacer(modifier = Modifier.height(SettingsContentPadding))
        Spacer(modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

@Composable
private fun LogPanel(
    logEntries: List<GitSyncLogCollector.LogEntry>,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    onClearLogs: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = SettingsContentPadding),
    ) {
        // Header row (always visible)
        SettingsItemCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SettingsRowPadding, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onToggleExpanded) {
                    val arrow = if (isExpanded) "▼" else "▶"
                    Text(
                        text = "$arrow ${stringResource(R.string.git_sync_logs)} (${logEntries.size})",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                if (logEntries.isNotEmpty()) {
                    IconButton(onClick = onClearLogs, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = stringResource(R.string.git_sync_logs_clear),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }

        // Expandable log content
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            val listState = rememberLazyListState()

            // Auto-scroll to bottom when new logs arrive
            LaunchedEffect(logEntries.size) {
                if (logEntries.isNotEmpty()) {
                    listState.animateScrollToItem(logEntries.size - 1)
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = MaterialTheme.shapes.small,
                    )
                    .height(240.dp)
            ) {
                if (logEntries.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.git_sync_logs_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        items(logEntries) { entry ->
                            val textColor = when {
                                entry.isError -> MaterialTheme.colorScheme.error
                                entry.isWarning -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Text(
                                text = entry.format(),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                ),
                                color = textColor,
                                modifier = Modifier.padding(vertical = 1.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * A simple text input preference row for settings.
 */
@Composable
fun TextPreferenceRow(
    title: String,
    value: String,
    hint: String = "",
    onValueChange: (String) -> Unit,
) {
    androidx.compose.foundation.layout.Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = SettingsRowPadding, vertical = 12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
        )
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(4.dp))
        androidx.compose.material3.OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = if (hint.isNotBlank()) {
                { Text(hint, style = MaterialTheme.typography.bodyMedium) }
            } else null,
            singleLine = true,
            modifier = Modifier.fillMaxSize(),
            textStyle = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun GitSyncStep.toLabelRes(): Int = when (this) {
    GitSyncStep.IDLE -> R.string.git_sync_now
    GitSyncStep.CLONING -> R.string.git_sync_step_cloning
    GitSyncStep.PULLING -> R.string.git_sync_step_pulling
    GitSyncStep.EXPORTING -> R.string.git_sync_step_exporting
    GitSyncStep.COMMITTING -> R.string.git_sync_step_committing
    GitSyncStep.PUSHING -> R.string.git_sync_step_pushing
}
