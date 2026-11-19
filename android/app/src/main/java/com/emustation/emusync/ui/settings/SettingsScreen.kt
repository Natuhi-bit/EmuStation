package com.emustation.emusync.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emustation.emusync.R
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit, onOpenLog: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbar.showSnackbar(it.resolve(context)) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ServerSection(state, viewModel)
            CaSection(state, viewModel)
            PermissionSection()
            Section(stringResource(R.string.section_periodic)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.label_periodic_push), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.hint_periodic_push), style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = state.periodicPush,
                        onCheckedChange = viewModel::setPeriodicPush,
                        enabled = state.loaded,
                    )
                }
            }
            Section(stringResource(R.string.sync_log_title)) {
                Text(stringResource(R.string.sync_log_hint), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onOpenLog) { Text(stringResource(R.string.action_open_log)) }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun ServerSection(state: SettingsUiState, vm: SettingsViewModel) {
    var showToken by rememberSaveable { mutableStateOf(false) }
    Section(stringResource(R.string.section_server)) {
        OutlinedTextField(
            value = state.serverUrl,
            onValueChange = vm::onServerUrlChange,
            label = { Text(stringResource(R.string.label_server_url)) },
            placeholder = { Text(stringResource(R.string.hint_server_url)) },
            singleLine = true,
            enabled = state.loaded,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.token,
            onValueChange = vm::onTokenChange,
            label = { Text(stringResource(R.string.label_token)) },
            singleLine = true,
            enabled = state.loaded,
            visualTransformation =
                if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { showToken = !showToken }) {
                    Icon(
                        if (showToken) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = stringResource(
                            if (showToken) R.string.cd_hide_token else R.string.cd_show_token,
                        ),
                    )
                }
            },
            supportingText = if (state.tokenUnreadable) {
                { Text(stringResource(R.string.warn_token_unreadable)) }
            } else {
                null
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::save, enabled = state.loaded && state.dirty) {
                Text(stringResource(R.string.action_save))
            }
            OutlinedButton(
                onClick = vm::testConnection,
                enabled = state.loaded && state.test !is TestState.Running,
            ) {
                Text(stringResource(R.string.action_test_connection))
            }
        }
        TestResult(state.test)
    }
}

@Composable
private fun TestResult(test: TestState) {
    when (test) {
        TestState.Idle -> Unit
        TestState.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.testing))
        }
        is TestState.Success -> StatusLine(true, test.text.resolve())
        is TestState.Failure -> StatusLine(false, test.text.resolve())
    }
}

@Composable
private fun StatusLine(ok: Boolean, text: String) {
    val color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (ok) Icons.Filled.CheckCircle else Icons.Filled.Error,
            contentDescription = null,
            tint = color,
        )
        Spacer(Modifier.width(8.dp))
        Text(text, color = color, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CaSection(state: SettingsUiState, vm: SettingsViewModel) {
    var confirmDelete by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importCa(uri)
    }
    val pick = { picker.launch(arrayOf("*/*")) }

    Section(stringResource(R.string.section_ca)) {
        val ca = state.ca
        if (ca == null) {
            Text(
                stringResource(
                    if (state.caFileName != null) R.string.ca_unreadable else R.string.ca_none,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(stringResource(R.string.ca_help), style = MaterialTheme.typography.bodySmall)
            Button(onClick = pick, enabled = state.loaded) {
                Text(stringResource(R.string.action_import_ca))
            }
            if (state.caFileName != null) {
                OutlinedButton(onClick = { confirmDelete = true }) {
                    Text(stringResource(R.string.action_delete))
                }
            }
        } else {
            val df = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
            LabeledValue(stringResource(R.string.ca_subject), ca.subject)
            LabeledValue(stringResource(R.string.ca_issuer), ca.issuer)
            LabeledValue(
                stringResource(R.string.ca_validity),
                stringResource(
                    R.string.ca_validity_value,
                    df.format(Date(ca.notBefore)),
                    df.format(Date(ca.notAfter)),
                ),
            )
            if (ca.notAfter < System.currentTimeMillis()) {
                Text(
                    stringResource(R.string.ca_expired),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            LabeledValue(stringResource(R.string.ca_fingerprint), ca.fingerprint, mono = true)
            if (ca.extraCount > 0) {
                Text(
                    stringResource(R.string.ca_extra, ca.extraCount),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = pick) { Text(stringResource(R.string.action_reimport_ca)) }
                OutlinedButton(onClick = { confirmDelete = true }) {
                    Text(stringResource(R.string.action_delete))
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.ca_delete_title)) },
            text = { Text(stringResource(R.string.ca_delete_message)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.deleteCa() }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun LabeledValue(label: String, value: String, mono: Boolean = false) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        SelectionContainer {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = if (mono) FontFamily.Monospace else null,
            )
        }
    }
}

@Composable
private fun PermissionSection() {
    val context = LocalContext.current
    var allFiles by remember { mutableStateOf(Permissions.hasAllFilesAccess()) }
    var notifications by remember { mutableStateOf(Permissions.hasNotificationPermission(context)) }

    // 設定画面から戻ってきたときに状態を読み直す
    LifecycleResumeEffect(Unit) {
        allFiles = Permissions.hasAllFilesAccess()
        notifications = Permissions.hasNotificationPermission(context)
        onPauseOrDispose { }
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notifications = granted
        // 「今後表示しない」などで出せないときは、通知の設定画面を開く
        if (!granted) Permissions.openNotificationSettings(context)
    }

    Section(stringResource(R.string.section_permissions)) {
        PermissionRow(
            label = stringResource(R.string.perm_all_files),
            granted = allFiles,
            onRequest = { Permissions.openAllFilesAccessSettings(context) },
        )
        PermissionRow(
            label = stringResource(R.string.perm_notifications),
            granted = notifications,
            onRequest = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    Permissions.openNotificationSettings(context)
                }
            },
        )
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, onRequest: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(if (granted) R.string.perm_granted else R.string.perm_denied),
                style = MaterialTheme.typography.bodySmall,
                color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }
        if (!granted) {
            Button(onClick = onRequest) { Text(stringResource(R.string.action_grant)) }
        }
    }
}
