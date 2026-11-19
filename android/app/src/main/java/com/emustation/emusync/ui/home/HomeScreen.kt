package com.emustation.emusync.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.emustation.emusync.R
import com.emustation.emusync.domain.sync.SyncMode
import com.emustation.emusync.domain.sync.SyncResult
import com.emustation.emusync.ui.common.AppIcon
import com.emustation.emusync.ui.common.formatDateTime
import com.emustation.emusync.ui.common.syncSummary

/** ホーム画面の操作 */
interface HomeActions {
    fun sync(target: String, mode: SyncMode)
    fun preview(target: String)
    fun edit(target: String)
    fun add()
    fun syncAll()
    fun openSettings()
    fun requestAllFilesAccess()
    fun requestNotificationPermission()
    fun dismissPreview()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeContent(
    state: HomeUiState,
    actions: HomeActions,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    var menu by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = actions::syncAll) {
                        Icon(Icons.Filled.Sync, stringResource(R.string.action_sync_all))
                    }
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Filled.MoreVert, stringResource(R.string.cd_menu))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_sync_all)) },
                                onClick = { menu = false; actions.syncAll() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.settings_title)) },
                                leadingIcon = { Icon(Icons.Filled.Settings, null) },
                                onClick = { menu = false; actions.openSettings() },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = actions::add) {
                Icon(Icons.Filled.Add, stringResource(R.string.action_add_target))
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { ServerCard(state.server, actions) }
            if (!state.hasAllFilesAccess) {
                item {
                    Banner(
                        stringResource(R.string.banner_all_files),
                        stringResource(R.string.action_grant),
                        actions::requestAllFilesAccess,
                        error = true,
                    )
                }
            }
            if (!state.hasNotificationPermission) {
                item {
                    Banner(
                        stringResource(R.string.banner_notifications),
                        stringResource(R.string.action_grant),
                        actions::requestNotificationPermission,
                        error = false,
                    )
                }
            }
            if (state.loaded && state.rows.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.home_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(vertical = 32.dp),
                    )
                }
            }
            items(state.rows, key = { it.name }) { TargetCard(it, actions) }
        }
    }

    state.preview?.let { PreviewDialog(it, actions::dismissPreview) }
}

@Composable
private fun ServerCard(server: ServerState, actions: HomeActions) {
    val (icon, text, color) = when (server) {
        ServerState.Checking -> Triple(Icons.Filled.Sync, stringResource(R.string.server_checking), null)
        ServerState.NotConfigured -> Triple(Icons.Filled.CloudOff, stringResource(R.string.server_not_configured), MaterialTheme.colorScheme.error)
        is ServerState.Connected -> Triple(
            Icons.Filled.CloudDone,
            stringResource(R.string.server_connected, server.device),
            MaterialTheme.colorScheme.primary,
        )
        is ServerState.Failed -> Triple(Icons.Filled.CloudOff, server.text.resolve(), MaterialTheme.colorScheme.error)
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().testTag("server"),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (server is ServerState.Checking) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(icon, null, tint = color ?: MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = color ?: MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (server is ServerState.NotConfigured || (server is ServerState.Failed && !server.offline)) {
                TextButton(onClick = actions::openSettings) { Text(stringResource(R.string.settings_title)) }
            }
        }
    }
}

@Composable
private fun Banner(text: String, action: String, onClick: () -> Unit, error: Boolean) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Button(onClick = onClick) { Text(action) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TargetCard(row: TargetRow, actions: HomeActions) {
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().testTag("target-${row.name}")) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(row.emuPackage)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(row.machine, style = MaterialTheme.typography.titleMedium)
                        if (row.machine != row.name) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                row.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        when {
                            row.emuPackage == null -> stringResource(R.string.emu_not_set)
                            row.emuMissing -> stringResource(R.string.emu_missing)
                            else -> row.emuLabel ?: row.emuPackage
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (row.emuMissing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        row.lastSyncAt?.let { stringResource(R.string.last_sync, formatDateTime(it)) }
                            ?: stringResource(R.string.last_sync_never),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.testTag("menu-${row.name}")) {
                        Icon(Icons.Filled.MoreVert, stringResource(R.string.cd_menu))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        val item = @Composable { label: Int, onClick: () -> Unit ->
                            DropdownMenuItem(
                                text = { Text(stringResource(label)) },
                                enabled = !row.running || label == R.string.action_edit,
                                onClick = { menu = false; onClick() },
                            )
                        }
                        item(R.string.action_sync_now) { actions.sync(row.name, SyncMode.SYNC) }
                        item(R.string.action_pull_only) { actions.sync(row.name, SyncMode.PULL) }
                        item(R.string.action_push_only) { actions.sync(row.name, SyncMode.PUSH) }
                        item(R.string.action_preview) { actions.preview(row.name) }
                        HorizontalDivider()
                        item(R.string.action_edit) { actions.edit(row.name) }
                    }
                }
            }
            if (row.statuses.isNotEmpty()) {
                Spacer(Modifier.size(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.statuses.forEach { StatusChip(it) }
                }
            }
            if (row.running) {
                Spacer(Modifier.size(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp)) {
                    Text(stringResource(R.string.status_syncing), style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.width(8.dp))
                    LinearProgressIndicator(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun StatusChip(status: TargetStatus) {
    val c = MaterialTheme.colorScheme
    val (text, bg, fg) = when (status) {
        TargetStatus.Synced -> Triple(stringResource(R.string.status_synced), c.primaryContainer, c.onPrimaryContainer)
        is TargetStatus.Unsent -> Triple(stringResource(R.string.status_unsent, status.count), c.tertiaryContainer, c.onTertiaryContainer)
        TargetStatus.NeverSynced -> Triple(stringResource(R.string.status_never_synced), c.surfaceVariant, c.onSurfaceVariant)
        is TargetStatus.Conflicts -> Triple(stringResource(R.string.status_conflicts, status.count), c.errorContainer, c.onErrorContainer)
        is TargetStatus.PlayingElsewhere -> Triple(stringResource(R.string.status_playing_elsewhere, status.device), c.errorContainer, c.onErrorContainer)
        TargetStatus.PlayingHere -> Triple(stringResource(R.string.status_playing_here), c.secondaryContainer, c.onSecondaryContainer)
        TargetStatus.DataDirMissing -> Triple(stringResource(R.string.status_data_dir_missing), c.errorContainer, c.onErrorContainer)
        TargetStatus.NoPermission -> Triple(stringResource(R.string.status_no_permission), c.errorContainer, c.onErrorContainer)
        is TargetStatus.ScanFailed -> Triple(stringResource(R.string.status_scan_failed), c.errorContainer, c.onErrorContainer)
        TargetStatus.Disabled -> Triple(stringResource(R.string.status_disabled), c.surfaceVariant, c.onSurfaceVariant)
    }
    Surface(shape = RoundedCornerShape(8.dp), color = bg, contentColor = fg) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

@Composable
private fun PreviewDialog(preview: PreviewState, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.preview_title, preview.machine)) },
        text = {
            when (preview) {
                is PreviewState.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.preview_running))
                }
                is PreviewState.Failed -> Text(preview.text.resolve(), color = MaterialTheme.colorScheme.error)
                is PreviewState.Done -> SyncResultDetails(preview.result)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

/** 予定される操作（または結果）の一覧 */
@Composable
fun SyncResultDetails(r: SyncResult, header: String? = null) {
    val context = LocalContext.current
    Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
        if (header != null) {
            Text(header, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.size(8.dp))
        }
        Text(
            syncSummary(context, r.uploaded.size, r.downloaded.size, r.conflicts.size, r.errors.size),
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(Modifier.size(8.dp))
        val mono = MaterialTheme.typography.bodySmall
        r.uploaded.forEach { Text("↑ $it", style = mono) }
        r.downloaded.forEach { Text("↓ $it", style = mono) }
        r.conflicts.forEach { Text("! ${it.rel}", style = mono, color = MaterialTheme.colorScheme.error) }
        r.errors.forEach { Text("× ${it.rel}: ${it.message}", style = mono, color = MaterialTheme.colorScheme.error) }
        if (r.conflicts.isNotEmpty()) {
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.preview_conflict_note), style = MaterialTheme.typography.bodySmall)
        }
    }
}
