package com.emustation.emusync.ui.conflict

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.emustation.emusync.R
import com.emustation.emusync.domain.sync.Prefer
import com.emustation.emusync.domain.sync.SyncConflict
import com.emustation.emusync.ui.common.formatDateTime
import com.emustation.emusync.ui.common.formatSize

interface ConflictActions {
    fun back()
    fun resolve(rels: List<String>, prefer: Prefer)
    fun confirm()
    fun cancel()
}

/** 競合の画面（設計書 §6.4） */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ConflictContent(
    state: ConflictUiState,
    actions: ConflictActions,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.conflict_title, state.machine)) },
                navigationIcon = {
                    IconButton(onClick = actions::back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (state.loaded && state.items.isEmpty()) {
                item { Text(stringResource(R.string.conflict_none), style = MaterialTheme.typography.bodyLarge) }
                return@LazyColumn
            }
            item {
                Text(stringResource(R.string.conflict_help), style = MaterialTheme.typography.bodyMedium)
            }
            if (state.playing) {
                item {
                    Text(
                        stringResource(R.string.conflict_playing),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            (state.server as? ServerView.Failed)?.let { f ->
                item { Text(f.text.resolve(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            if (state.items.size > 1) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.conflict_bulk, state.items.size), style = MaterialTheme.typography.titleSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val all = state.items.map { it.rel }
                            OutlinedButton(onClick = { actions.resolve(all, Prefer.LOCAL) }, enabled = !state.busy) {
                                Text(stringResource(R.string.action_keep_local_all))
                            }
                            OutlinedButton(
                                onClick = { actions.resolve(all, Prefer.REMOTE) },
                                enabled = !state.busy && !state.playing,
                            ) { Text(stringResource(R.string.action_use_remote_all)) }
                        }
                    }
                }
            }
            items(state.items, key = { it.rel }) { ConflictCard(it, state, actions) }
        }
    }

    state.confirm?.let { req ->
        AlertDialog(
            onDismissRequest = actions::cancel,
            title = {
                Text(
                    stringResource(
                        if (req.prefer == Prefer.LOCAL) R.string.confirm_keep_local_title else R.string.confirm_use_remote_title,
                        req.rels.size,
                    ),
                )
            },
            text = {
                Text(
                    stringResource(
                        if (req.prefer == Prefer.LOCAL) R.string.confirm_keep_local_text else R.string.confirm_use_remote_text,
                    ),
                )
            },
            confirmButton = { TextButton(onClick = actions::confirm) { Text(stringResource(R.string.action_ok)) } },
            dismissButton = { TextButton(onClick = actions::cancel) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun ConflictCard(item: ConflictItem, state: ConflictUiState, actions: ConflictActions) {
    Card(Modifier.fillMaxWidth().testTag("conflict-${item.rel}")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                item.rel,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.entity.kind == SyncConflict.Kind.UPLOAD_REJECTED.name) {
                Text(
                    stringResource(R.string.conflict_kind_rejected),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Side(Modifier.weight(1f), stringResource(R.string.conflict_local)) {
                    val l = item.local
                    if (l == null) {
                        Line(stringResource(R.string.conflict_local_missing))
                    } else {
                        Line(formatSize(l.size))
                        Line(formatDateTime(l.lastModified))
                    }
                }
                Side(Modifier.weight(1f), stringResource(R.string.conflict_server)) {
                    val s = item.server
                    if (s == null) {
                        Line(stringResource(R.string.conflict_server_version, item.entity.serverVersion))
                        if (state.server is ServerView.Loading) Line(stringResource(R.string.loading))
                    } else {
                        Line(stringResource(R.string.conflict_server_version, s.version))
                        Line(s.device)
                        Line(formatDateTime((s.updatedAt * 1000).toLong()))
                        Line(formatSize(s.size))
                    }
                }
            }
            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { actions.resolve(listOf(item.rel), Prefer.LOCAL) },
                    enabled = !state.busy && item.local != null,
                    modifier = Modifier.testTag("keep-local-${item.rel}"),
                ) { Text(stringResource(R.string.action_keep_local)) }
                OutlinedButton(
                    onClick = { actions.resolve(listOf(item.rel), Prefer.REMOTE) },
                    enabled = !state.busy && !state.playing,
                    modifier = Modifier.testTag("use-remote-${item.rel}"),
                ) { Text(stringResource(R.string.action_use_remote)) }
            }
        }
    }
}

@Composable
private fun Side(modifier: Modifier, title: String, content: @Composable () -> Unit) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(2.dp))
        content()
    }
}

@Composable
private fun Line(text: String) = Text(text, style = MaterialTheme.typography.bodySmall)

