package com.emustation.emusync.ui.target

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.emustation.emusync.R
import com.emustation.emusync.data.apps.LauncherApp
import com.emustation.emusync.data.storage.TreeUriPaths
import com.emustation.emusync.domain.Presets
import com.emustation.emusync.ui.common.AppIcon
import com.emustation.emusync.ui.home.SyncResultDetails
import java.io.File

interface TargetEditActions {
    fun back()
    fun selectPreset(name: String?)
    fun update(f: (TargetForm) -> TargetForm)
    fun pickFolder()
    fun openAppPicker()
    fun selectApp(app: LauncherApp?)
    fun requestSave()
    fun cancelSave()
    fun save()
    fun delete()
}

/** フォルダの存在（画面の表示用）。テストでは差し替える */
fun interface DirProbe {
    fun isDirectory(path: String): Boolean

    companion object {
        val Real = DirProbe { File(it).isDirectory }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TargetEditContent(
    state: TargetEditState,
    actions: TargetEditActions,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    dirs: DirProbe = DirProbe.Real,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    var appPicker by remember { mutableStateOf(false) }
    val form = state.form
    val errors = if (state.showErrors) state.errors else FormErrors()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (state.isNew) R.string.target_add_title else R.string.target_edit_title)) },
                navigationIcon = {
                    IconButton(onClick = actions::back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    if (!state.isNew) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Filled.Delete, stringResource(R.string.action_delete))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (!state.loaded) return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ---- 機種
            Label(stringResource(R.string.label_preset))
            if (state.isNew) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Presets.all.forEach { p ->
                        FilterChip(
                            selected = form.preset == p.name,
                            onClick = { actions.selectPreset(p.name) },
                            enabled = p.name !in state.existingNames,
                            label = { Text(p.machine) },
                        )
                    }
                    FilterChip(
                        selected = form.preset == null,
                        onClick = { actions.selectPreset(null) },
                        label = { Text(stringResource(R.string.preset_custom)) },
                    )
                }
            }
            Presets.byName(form.preset ?: "")?.let {
                Text(
                    stringResource(R.string.preset_hint, it.machine, it.emulator, it.exampleDataDir),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // ---- ターゲット名
            OutlinedTextField(
                value = form.name,
                onValueChange = { v -> actions.update { it.copy(name = v) } },
                label = { Text(stringResource(R.string.label_target_name)) },
                enabled = state.isNew && form.preset == null,
                singleLine = true,
                isError = errors.name != null,
                supportingText = {
                    Text(errors.name?.resolve() ?: stringResource(R.string.hint_target_name))
                },
                modifier = Modifier.fillMaxWidth().testTag("name"),
            )

            // ---- エミュ
            Label(stringResource(R.string.label_emulator))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(form.emuPackage)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        form.emuLabel ?: form.emuPackage ?: stringResource(R.string.emu_not_set),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (form.emuPackage != null) {
                        Text(form.emuPackage, style = MaterialTheme.typography.bodySmall)
                    }
                }
                OutlinedButton(onClick = { appPicker = true; actions.openAppPicker() }) {
                    Text(stringResource(R.string.action_choose))
                }
            }

            // ---- データフォルダ
            OutlinedTextField(
                value = form.dataDir,
                onValueChange = { v -> actions.update { it.copy(dataDir = v) } },
                label = { Text(stringResource(R.string.label_data_dir)) },
                placeholder = { Text(Presets.byName(form.preset ?: "")?.exampleDataDir ?: "/storage/emulated/0/…") },
                singleLine = true,
                isError = errors.dataDir != null,
                supportingText = errors.dataDir?.let { e -> { Text(e.resolve()) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth().testTag("dataDir"),
            )
            OutlinedButton(onClick = actions::pickFolder) { Text(stringResource(R.string.action_pick_folder)) }

            // ---- 相対パス
            OutlinedTextField(
                value = form.relSubdir,
                onValueChange = { v -> actions.update { it.copy(relSubdir = v) } },
                label = { Text(stringResource(R.string.label_rel_subdir)) },
                singleLine = true,
                isError = errors.relSubdir != null,
                supportingText = {
                    Text(errors.relSubdir?.resolve() ?: stringResource(R.string.hint_rel_subdir))
                },
                modifier = Modifier.fillMaxWidth(),
            )

            // ---- 同期するフォルダ（確認用）
            if (form.dataDir.isNotBlank()) SyncDirInfo(form, dirs)

            // ---- 除外
            OutlinedTextField(
                value = form.excludesText,
                onValueChange = { v -> actions.update { it.copy(excludesText = v) } },
                label = { Text(stringResource(R.string.label_excludes)) },
                supportingText = { Text(stringResource(R.string.hint_excludes)) },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.label_enabled), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.hint_enabled), style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = form.enabled, onCheckedChange = { v -> actions.update { it.copy(enabled = v) } })
            }

            Button(onClick = actions::requestSave, modifier = Modifier.fillMaxWidth().testTag("save")) {
                Text(stringResource(R.string.action_check_and_save))
            }
        }
    }

    state.check?.let { SaveCheckDialog(it, actions) }

    if (appPicker) {
        AppPickerDialog(
            apps = state.apps,
            onPick = { appPicker = false; actions.selectApp(it) },
            onDismiss = { appPicker = false },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.target_delete_title, form.name)) },
            text = { Text(stringResource(R.string.target_delete_message)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; actions.delete() }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun Label(text: String) = Text(text, style = MaterialTheme.typography.titleSmall)

@Composable
private fun SyncDirInfo(form: TargetForm, dirs: DirProbe) {
    val syncDir = form.syncDir
    val dataDirExists = remember(form.dataDir) { dirs.isDirectory(form.dataDir.trim()) }
    val syncDirExists = remember(syncDir) { dirs.isDirectory(syncDir) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Label(stringResource(R.string.label_sync_dir))
        Text(syncDir, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
        when {
            TreeUriPaths.isInsideAndroidData(form.dataDir) -> Warning(stringResource(R.string.warn_android_data), error = true)
            !dataDirExists -> Warning(stringResource(R.string.warn_data_dir_missing), error = true)
            !syncDirExists -> Warning(stringResource(R.string.warn_sync_dir_missing), error = false)
        }
    }
}

@Composable
private fun Warning(text: String, error: Boolean) {
    val color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Filled.Warning, null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = color, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SaveCheckDialog(check: SaveCheck, actions: TargetEditActions) {
    AlertDialog(
        onDismissRequest = actions::cancelSave,
        title = { Text(stringResource(R.string.save_check_title)) },
        text = {
            when (check) {
                SaveCheck.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.preview_running))
                }
                is SaveCheck.Done -> SyncResultDetails(check.result, header = stringResource(R.string.save_check_header))
                is SaveCheck.Failed -> Column {
                    Text(check.text.resolve(), color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.save_check_failed_note), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = actions::save, enabled = check !is SaveCheck.Running) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = actions::cancelSave) { Text(stringResource(R.string.action_back)) } },
    )
}

@Composable
private fun AppPickerDialog(apps: List<LauncherApp>?, onPick: (LauncherApp?) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.app_picker_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.app_picker_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(8.dp))
                if (apps == null) {
                    CircularProgressIndicator()
                } else {
                    val shown = apps.filter {
                        query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true)
                    }
                    LazyColumn(Modifier.heightIn(max = 400.dp)) {
                        items(shown, key = { it.packageName }) { a ->
                            Row(
                                Modifier.fillMaxWidth().clickable { onPick(a) }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AppIcon(a.packageName, 36.dp)
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(a.label, style = MaterialTheme.typography.bodyLarge)
                                    Text(a.packageName, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(null) }) { Text(stringResource(R.string.action_unset)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
