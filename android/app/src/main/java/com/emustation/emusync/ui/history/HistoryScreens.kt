package com.emustation.emusync.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.emustation.emusync.R
import com.emustation.emusync.data.api.HistoryVersion
import com.emustation.emusync.domain.sync.RemoteFile
import com.emustation.emusync.ui.common.formatDateTime
import com.emustation.emusync.ui.common.formatSize

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Screen(
    title: String,
    onBack: () -> Unit,
    snackbar: SnackbarHostState,
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding -> content(Modifier.fillMaxSize().padding(padding)) }
}

@Composable
private fun <T> LoadableBox(l: Loadable<T>, onRetry: () -> Unit, content: @Composable (T) -> Unit) {
    when (l) {
        Loadable.Loading -> Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator()
        }
        is Loadable.Failed -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(l.text.resolve(), color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
        }
        is Loadable.Loaded -> content(l.value)
    }
}

/** 履歴：ファイルを選ぶ（設計書 §6.5） */
@Composable
fun HistoryFilesContent(
    state: HistoryFilesState,
    onBack: () -> Unit,
    onQuery: (String) -> Unit,
    onOpen: (String) -> Unit,
    onRetry: () -> Unit,
) {
    Screen(stringResource(R.string.history_title, state.machine), onBack, remember { SnackbarHostState() }) { m ->
        Column(m) {
            LoadableBox(state.files, onRetry) { files ->
                val shown = files.filter { state.query.isBlank() || it.rel.contains(state.query, ignoreCase = true) }
                LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    item {
                        Column(Modifier.padding(16.dp)) {
                            Text(stringResource(R.string.history_files_help), style = MaterialTheme.typography.bodyMedium)
                            if (files.size > 8) {
                                OutlinedTextField(
                                    value = state.query,
                                    onValueChange = onQuery,
                                    label = { Text(stringResource(R.string.app_picker_search)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            if (files.isEmpty()) Text(stringResource(R.string.history_no_files))
                        }
                    }
                    items(shown, key = { it.rel }) { f -> FileRow(f) { onOpen(f.rel) } }
                }
            }
        }
    }
}

@Composable
private fun FileRow(f: RemoteFile, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(f.rel, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(R.string.history_file_line, f.version, formatSize(f.size), f.device, formatDateTime((f.updatedAt * 1000).toLong())),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider()
}

interface FileHistoryActions {
    fun back()
    fun retry()
    fun requestRestore(v: HistoryVersion)
    fun restore()
    fun pull()
    fun cancel()
}

/** 1つのファイルの版の一覧。[この版に戻す] → 復元 → 確認のうえで取得 */
@Composable
fun FileHistoryContent(
    state: FileHistoryState,
    actions: FileHistoryActions,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    Screen(stringResource(R.string.file_history_title), actions::back, snackbar) { m ->
        Column(m) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                state.rel,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
            LoadableBox(state.versions, actions::retry) { versions ->
                LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(versions, key = { it.version }) { v ->
                        VersionRow(v, isCurrent = v.version == state.current, isLocal = v.sha256 == state.localSha, busy = state.busy) {
                            actions.requestRestore(v)
                        }
                    }
                }
            }
        }
    }

    state.confirmRestore?.let { v ->
        AlertDialog(
            onDismissRequest = actions::cancel,
            title = { Text(stringResource(R.string.restore_title, v.version)) },
            text = { Text(stringResource(R.string.restore_text, v.version, (state.current ?: 0) + 1)) },
            confirmButton = { TextButton(onClick = actions::restore) { Text(stringResource(R.string.action_restore)) } },
            dismissButton = { TextButton(onClick = actions::cancel) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    state.confirmPull?.let { newVersion ->
        AlertDialog(
            onDismissRequest = actions::cancel,
            title = { Text(stringResource(R.string.pull_after_restore_title, newVersion)) },
            text = { Text(stringResource(R.string.pull_after_restore_text)) },
            confirmButton = { TextButton(onClick = actions::pull) { Text(stringResource(R.string.action_pull_now)) } },
            dismissButton = { TextButton(onClick = actions::cancel) { Text(stringResource(R.string.action_later)) } },
        )
    }
}

@Composable
private fun VersionRow(v: HistoryVersion, isCurrent: Boolean, isLocal: Boolean, busy: Boolean, onRestore: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("version-${v.version}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("v${v.version}", style = MaterialTheme.typography.titleMedium)
                if (isCurrent) Badge(stringResource(R.string.badge_latest))
                if (isLocal) Badge(stringResource(R.string.badge_same_as_local))
            }
            Text(
                "${formatDateTime((v.createdAt * 1000).toLong())}  ${formatSize(v.size)}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "${v.device}  ${v.sha256.take(12)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!isCurrent) {
            OutlinedButton(onClick = onRestore, enabled = !busy) { Text(stringResource(R.string.action_restore_this)) }
        }
    }
    HorizontalDivider()
}

@Composable
private fun Badge(text: String) {
    Spacer(Modifier.width(8.dp))
    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}
