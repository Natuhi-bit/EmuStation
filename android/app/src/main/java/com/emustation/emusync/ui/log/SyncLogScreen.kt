package com.emustation.emusync.ui.log

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.emustation.emusync.R
import com.emustation.emusync.data.db.SyncLogEntity
import com.emustation.emusync.domain.Presets
import com.emustation.emusync.ui.common.formatDateTime
import com.emustation.emusync.ui.common.modeLabel
import com.emustation.emusync.ui.common.syncSummary

/** 同期ログ（直近 100 回。設計書 §6.2） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncLogContent(entries: List<SyncLogEntity>, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sync_log_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back))
                    }
                },
            )
        },
    ) { padding ->
        if (entries.isEmpty()) {
            Text(
                stringResource(R.string.sync_log_empty),
                modifier = Modifier.padding(padding).padding(16.dp),
            )
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(entries, key = { it.id }) {
                LogRow(it)
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun LogRow(e: SyncLogEntity) {
    val context = LocalContext.current
    var expanded by rememberSaveable(e.id) { mutableStateOf(false) }
    val failed = e.fatal != null || e.errors.isNotEmpty()
    Column(
        Modifier.fillMaxWidth().clickable(enabled = e.errors.isNotEmpty()) { expanded = !expanded }
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row {
            Text(
                Presets.byName(e.target)?.machine ?: e.target,
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                modeLabel(context, e.mode, e.dryRun) + (e.prefer?.let { " / " + preferLabel(context, it) } ?: ""),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                formatDateTime(e.startedAt),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            e.fatal ?: syncSummary(context, e.uploaded, e.downloaded, e.conflicts, e.errors.size),
            style = MaterialTheme.typography.bodyMedium,
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        if (expanded) {
            e.errors.forEach { Text("× $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private fun preferLabel(context: android.content.Context, prefer: String) = context.getString(
    if (prefer == "LOCAL") R.string.prefer_local else R.string.prefer_remote,
)
