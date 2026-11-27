package com.emustation.emusync.ui

import android.Manifest
import android.os.Build
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.emustation.emusync.EmusyncApp
import com.emustation.emusync.data.api.HistoryVersion
import com.emustation.emusync.data.apps.LauncherApp
import com.emustation.emusync.domain.sync.Prefer
import com.emustation.emusync.ui.conflict.ConflictActions
import com.emustation.emusync.ui.conflict.ConflictContent
import com.emustation.emusync.ui.conflict.ConflictViewModel
import com.emustation.emusync.ui.history.FileHistoryActions
import com.emustation.emusync.ui.history.FileHistoryContent
import com.emustation.emusync.ui.history.FileHistoryViewModel
import com.emustation.emusync.ui.history.HistoryFilesContent
import com.emustation.emusync.ui.history.HistoryFilesViewModel
import com.emustation.emusync.domain.sync.SyncMode
import com.emustation.emusync.ui.home.HomeActions
import com.emustation.emusync.ui.home.HomeContent
import com.emustation.emusync.ui.home.HomeViewModel
import com.emustation.emusync.ui.log.SyncLogContent
import com.emustation.emusync.ui.settings.Permissions
import com.emustation.emusync.ui.settings.SettingsScreen
import com.emustation.emusync.ui.settings.SettingsViewModel
import com.emustation.emusync.ui.target.TargetEditActions
import com.emustation.emusync.ui.target.TargetEditContent
import com.emustation.emusync.ui.target.TargetEditViewModel
import com.emustation.emusync.ui.target.TargetForm
import kotlinx.serialization.Serializable

@Serializable
object HomeRoute

@Serializable
object SettingsRoute

@Serializable
object SyncLogRoute

@Serializable
data class ConflictRoute(val target: String)

@Serializable
data class HistoryRoute(val target: String)

@Serializable
data class FileHistoryRoute(val target: String, val rel: String)

/** [name] が null なら新規追加 */
@Serializable
data class TargetEditRoute(val name: String? = null)

@Composable
fun AppNavHost(app: EmusyncApp) {
    val nav = rememberNavController()
    NavHost(nav, startDestination = HomeRoute) {
        composable<HomeRoute> { HomeRouteScreen(app, nav) }
        composable<SettingsRoute> {
            val vm: SettingsViewModel = viewModel(
                factory = viewModelFactory { initializer { SettingsViewModel(app, app.container) } },
            )
            SettingsScreen(vm, onBack = { nav.popBackStack() }, onOpenLog = { nav.navigate(SyncLogRoute) })
        }
        composable<SyncLogRoute> {
            val entries by remember { app.container.db.syncLog().observeRecent() }
                .collectAsStateWithLifecycle(emptyList())
            SyncLogContent(entries, onBack = { nav.popBackStack() })
        }
        composable<TargetEditRoute> { entry ->
            TargetEditRouteScreen(app, nav, entry.toRoute<TargetEditRoute>().name)
        }
        composable<ConflictRoute> { entry -> ConflictRouteScreen(app, nav, entry.toRoute<ConflictRoute>().target) }
        composable<HistoryRoute> { entry ->
            val target = entry.toRoute<HistoryRoute>().target
            val vm: HistoryFilesViewModel = viewModel(
                factory = viewModelFactory { initializer { HistoryFilesViewModel(app.container, target) } },
            )
            val state by vm.state.collectAsStateWithLifecycle()
            HistoryFilesContent(
                state,
                onBack = { nav.popBackStack() },
                onQuery = vm::setQuery,
                onOpen = { rel -> nav.navigate(FileHistoryRoute(target, rel)) },
                onRetry = vm::load,
            )
        }
        composable<FileHistoryRoute> { entry ->
            val r = entry.toRoute<FileHistoryRoute>()
            FileHistoryRouteScreen(app, nav, r.target, r.rel)
        }
    }
}

@Composable
private fun ConflictRouteScreen(app: EmusyncApp, nav: NavController, target: String) {
    val vm: ConflictViewModel = viewModel(
        factory = viewModelFactory { initializer { ConflictViewModel(app, app.container, target) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it.resolve(context)) } }
    val actions = remember(vm) {
        object : ConflictActions {
            override fun back() {
                nav.popBackStack()
            }
            override fun resolve(rels: List<String>, prefer: Prefer) = vm.requestResolve(rels, prefer)
            override fun confirm() = vm.resolve()
            override fun cancel() = vm.cancelResolve()
        }
    }
    ConflictContent(state, actions, snackbar)
}

@Composable
private fun FileHistoryRouteScreen(app: EmusyncApp, nav: NavController, target: String, rel: String) {
    val vm: FileHistoryViewModel = viewModel(
        factory = viewModelFactory { initializer { FileHistoryViewModel(app, app.container, target, rel) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it.resolve(context)) } }
    val actions = remember(vm) {
        object : FileHistoryActions {
            override fun back() {
                nav.popBackStack()
            }
            override fun retry() = vm.load()
            override fun requestRestore(v: HistoryVersion) = vm.requestRestore(v)
            override fun restore() = vm.restore()
            override fun pull() = vm.pull()
            override fun cancel() = vm.cancel()
        }
    }
    FileHistoryContent(state, actions, snackbar)
}

@Composable
private fun HomeRouteScreen(app: EmusyncApp, nav: NavController) {
    val vm: HomeViewModel = viewModel(factory = viewModelFactory { initializer { HomeViewModel(app, app.container) } })
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LifecycleResumeEffect(vm) {
        vm.refresh()
        onPauseOrDispose { }
    }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Permissions.openNotificationSettings(context)
        vm.refresh()
    }

    val actions = remember(vm) {
        object : HomeActions {
            override fun sync(target: String, mode: SyncMode) = vm.sync(target, mode)
            override fun preview(target: String) = vm.preview(target)
            override fun edit(target: String) = nav.navigate(TargetEditRoute(target))
            override fun add() = nav.navigate(TargetEditRoute())
            override fun syncAll() = vm.syncAll()
            override fun openSettings() = nav.navigate(SettingsRoute)
            override fun requestAllFilesAccess() = Permissions.openAllFilesAccessSettings(context)
            override fun requestNotificationPermission() {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    Permissions.openNotificationSettings(context)
                }
            }
            override fun dismissPreview() = vm.dismissPreview()
            override fun requestUsageAccess() = Permissions.openUsageAccessSettings(context)
            override fun play(target: String) = vm.play(target)
            override fun playForce(target: String) = vm.playForce(target)
            override fun playOffline(target: String) = vm.playOffline(target)
            override fun playIgnoringConflicts(target: String) = vm.playIgnoringConflicts(target)
            override fun abortPlay(target: String) = vm.abortPlay(target)
            override fun dismissPlayDialog() = vm.dismissPlayDialog()
            override fun finishPlay(target: String) = vm.finishPlay(target)
            override fun keepPlaying() = vm.keepPlaying()
            override fun openConflicts(target: String) = nav.navigate(ConflictRoute(target))
            override fun openHistory(target: String) = nav.navigate(HistoryRoute(target))
        }
    }
    HomeContent(state, actions, snackbar)
}

@Composable
private fun TargetEditRouteScreen(app: EmusyncApp, nav: NavController, name: String?) {
    val vm: TargetEditViewModel = viewModel(
        factory = viewModelFactory { initializer { TargetEditViewModel(app, app.container, name) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it.resolve(context)) } }
    LaunchedEffect(state.finished) { if (state.finished) nav.popBackStack() }

    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            @Suppress("DEPRECATION")
            vm.onTreePicked(uri.toString(), Environment.getExternalStorageDirectory().path)
        }
    }

    val actions = remember(vm) {
        object : TargetEditActions {
            override fun back() {
                nav.popBackStack()
            }
            override fun selectPreset(name: String?) = vm.selectPreset(name)
            override fun update(f: (TargetForm) -> TargetForm) = vm.update(f)
            override fun pickFolder() = treePicker.launch(null)
            override fun openAppPicker() = vm.loadApps()
            override fun selectApp(app: LauncherApp?) = vm.selectApp(app)
            override fun requestSave() = vm.requestSave()
            override fun cancelSave() = vm.cancelSave()
            override fun save() = vm.save()
            override fun delete() = vm.delete()
        }
    }
    TargetEditContent(state, actions, snackbar)
}
