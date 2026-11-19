package com.emustation.emusync.ui.home

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.emustation.emusync.AppContainer
import com.emustation.emusync.R
import com.emustation.emusync.data.LocalState
import com.emustation.emusync.data.SyncOutcome
import com.emustation.emusync.data.api.ApiException
import com.emustation.emusync.data.api.LockInfo
import com.emustation.emusync.data.db.TargetEntity
import com.emustation.emusync.domain.Presets
import com.emustation.emusync.domain.sync.SyncMode
import com.emustation.emusync.ui.common.connectionErrorText
import com.emustation.emusync.ui.common.syncSummary
import com.emustation.emusync.ui.settings.Permissions
import com.emustation.emusync.work.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

class HomeViewModel(
    private val app: Application,
    private val container: AppContainer,
) : ViewModel() {
    private val db = container.db
    private val workManager = WorkManager.getInstance(app)

    private val server = MutableStateFlow<ServerState>(ServerState.Checking)
    private val locks = MutableStateFlow<Map<String, LockInfo>>(emptyMap())
    private val selfDevice = MutableStateFlow<String?>(null)
    private val localStates = MutableStateFlow<Map<String, LocalState>>(emptyMap())
    private val emuLabels = MutableStateFlow<Map<String, String?>>(emptyMap())
    private val permissions = MutableStateFlow(true to true)
    private val preview = MutableStateFlow<PreviewState?>(null)

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private val workInfos = workManager.getWorkInfosByTagFlow(SyncWorker.TAG)

    private data class Inputs(
        val targets: List<TargetEntity>,
        val conflicts: Map<String, Int>,
        val lastSync: Map<String, Long>,
        val playing: Set<String>,
        val running: Set<String>,
    )

    private val inputs = combine(
        db.targets().observeAll(),
        db.conflicts().observeCounts(),
        db.syncLog().observeLastSuccess(),
        db.playSessions().observeAll(),
        workInfos,
    ) { targets, conflicts, last, playing, infos ->
        Inputs(
            targets,
            conflicts.associate { it.target to it.count },
            last.associate { it.target to it.time },
            playing.map { it.target }.toSet(),
            infos.filter { !it.state.isFinished }.mapNotNull(SyncWorker::targetOf).toSet(),
        )
    }

    private data class Remote(
        val server: ServerState,
        val locks: Map<String, LockInfo>,
        val self: String?,
        val local: Map<String, LocalState>,
        val labels: Map<String, String?>,
    )

    private val remote = combine(server, locks, selfDevice, localStates, emuLabels) { s, l, d, ls, lb ->
        Remote(s, l, d, ls, lb)
    }

    val state: StateFlow<HomeUiState> = combine(inputs, remote, permissions, preview) { i, r, perm, pv ->
        HomeUiState(
            loaded = true,
            server = r.server,
            rows = i.targets.map { t ->
                val preset = Presets.byName(t.name)
                val label = t.emuPackage?.let { r.labels[it] }
                TargetRow(
                    name = t.name,
                    machine = preset?.machine ?: t.name,
                    emuPackage = t.emuPackage,
                    emuLabel = label,
                    emuMissing = t.emuPackage != null && r.labels.containsKey(t.emuPackage) && label == null,
                    lastSyncAt = i.lastSync[t.name],
                    statuses = statusesFor(
                        t.enabled, i.lastSync[t.name], i.conflicts[t.name] ?: 0, r.local[t.name],
                        r.locks[t.name], r.self, t.name in i.playing,
                    ),
                    running = t.name in i.running,
                )
            },
            hasAllFilesAccess = perm.first,
            hasNotificationPermission = perm.second,
            preview = pv,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    private var refreshJob: Job? = null

    init {
        watchFinishedWork()
    }

    /** 画面に戻ってきたときに呼ぶ。サーバーの状態、ロック、手元の状態を読み直す */
    fun refresh() {
        permissions.value = Permissions.hasAllFilesAccess() to Permissions.hasNotificationPermission(app)
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val targets = db.targets().getAll()
            emuLabels.value = withContext(Dispatchers.IO) {
                targets.mapNotNull { it.emuPackage }.distinct()
                    .associateWith { container.launcherApps.label(it) }
            }
            launch { refreshServer() }
            for (t in targets) refreshLocal(t)
        }
    }

    private suspend fun refreshServer() {
        val s = container.settingsStore.current()
        selfDevice.value = s.deviceName
        if (s.serverUrl.isBlank() || s.token.isBlank()) {
            server.value = ServerState.NotConfigured
            locks.value = emptyMap()
            return
        }
        server.update { if (it is ServerState.Connected) it else ServerState.Checking }
        try {
            val api = withContext(Dispatchers.IO) { container.api(s) }
            val pong = api.ping()
            if (pong.device != s.deviceName) container.settingsStore.setDeviceName(pong.device)
            selfDevice.value = pong.device
            server.value = ServerState.Connected(pong.device)
            locks.value = api.locks().associateBy { it.target }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            server.value = ServerState.Failed(connectionErrorText(e), e is ApiException.Offline)
            locks.value = emptyMap()
        }
    }

    private suspend fun refreshLocal(t: TargetEntity) {
        val st = container.syncRepository.localState(t)
        localStates.update { it + (t.name to st) }
    }

    fun sync(target: String, mode: SyncMode) {
        SyncWorker.enqueue(app, target, mode)
    }

    /** 有効なターゲットをすべて同期する */
    fun syncAll() {
        viewModelScope.launch {
            val targets = db.targets().getAll().filter { it.enabled }
            if (targets.isEmpty()) {
                _messages.send(app.getString(R.string.msg_no_enabled_targets))
                return@launch
            }
            targets.forEach { SyncWorker.enqueue(app, it.name, SyncMode.SYNC) }
        }
    }

    /** プレビュー（dry-run）。通信は manifest の取得だけ */
    fun preview(target: String) {
        viewModelScope.launch {
            val t = db.targets().get(target) ?: return@launch
            val machine = Presets.byName(t.name)?.machine ?: t.name
            preview.value = PreviewState.Running(machine)
            preview.value = when (val o = container.syncRepository.run(t, SyncMode.SYNC, dryRun = true)) {
                is SyncOutcome.Done -> PreviewState.Done(machine, o.result)
                is SyncOutcome.Failed -> PreviewState.Failed(machine, o.message)
            }
        }
    }

    fun dismissPreview() {
        preview.value = null
    }

    /** 終わったジョブの結果を知らせ、そのターゲットの状態を読み直す */
    private fun watchFinishedWork() {
        viewModelScope.launch {
            val seenRunning = mutableSetOf<java.util.UUID>()
            val reported = mutableSetOf<java.util.UUID>()
            var first = true
            workInfos.collect { infos ->
                for (info in infos) {
                    if (!info.state.isFinished) {
                        seenRunning += info.id
                    } else if (info.id !in reported && (info.id in seenRunning || !first)) {
                        reported += info.id
                        report(info)
                    } else {
                        reported += info.id
                    }
                }
                first = false
            }
        }
    }

    private suspend fun report(info: WorkInfo) {
        val name = SyncWorker.targetOf(info) ?: return
        val machine = Presets.byName(name)?.machine ?: name
        val d = info.outputData
        val text = when (info.state) {
            WorkInfo.State.SUCCEEDED -> app.getString(
                R.string.msg_sync_finished, machine,
                syncSummary(
                    app,
                    d.getInt(SyncWorker.KEY_UPLOADED, 0),
                    d.getInt(SyncWorker.KEY_DOWNLOADED, 0),
                    d.getInt(SyncWorker.KEY_CONFLICTS, 0),
                    d.getInt(SyncWorker.KEY_ERRORS, 0),
                ),
            )
            WorkInfo.State.FAILED -> app.getString(
                R.string.msg_sync_failed, machine, d.getString(SyncWorker.KEY_FATAL).orEmpty(),
            )
            else -> return
        }
        _messages.send(text)
        db.targets().get(name)?.let { refreshLocal(it) }
    }
}
