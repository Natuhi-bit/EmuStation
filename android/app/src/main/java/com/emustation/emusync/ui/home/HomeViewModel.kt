package com.emustation.emusync.ui.home

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
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
import com.emustation.emusync.data.db.PlaySessionEntity
import com.emustation.emusync.data.db.TargetEntity
import com.emustation.emusync.domain.play.PlayStep
import com.emustation.emusync.domain.play.StartResult
import com.emustation.emusync.ui.common.UiText
import com.emustation.emusync.work.Notifications
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
import kotlinx.coroutines.flow.first
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
    private val play = MutableStateFlow<PlayDialog?>(null)
    private val resumePrompt = MutableStateFlow<ResumePrompt?>(null)
    private val playManager = container.playSessionManager

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

    private data class Dialogs(val preview: PreviewState?, val play: PlayDialog?, val resume: ResumePrompt?)

    private val dialogs = combine(preview, play, resumePrompt) { a, b, c -> Dialogs(a, b, c) }

    val state: StateFlow<HomeUiState> = combine(inputs, remote, permissions, dialogs) { i, r, perm, dl ->
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
                    playingHere = t.name in i.playing,
                )
            },
            hasAllFilesAccess = perm.first,
            hasNotificationPermission = perm.second,
            preview = dl.preview,
            play = dl.play,
            resumePrompt = dl.resume,
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
            launch { askToFinishPlay() }
            for (t in targets) refreshLocal(t)
        }
    }

    /**
     * プレイセッションが残っていれば「送信しますか？（エミュを終了してから）」と聞く（設計書 §5.2）。
     * 自動では送らない。エミュが裏でまだ動いていて、セーブを書き込み中かもしれないため。
     * 送信のジョブが既に動いているターゲットは聞かない。
     */
    private suspend fun askToFinishPlay() {
        if (play.value != null) return
        val sessions = db.playSessions().observeAll().first()
        val pending = sessions.firstOrNull { s ->
            workManager.getWorkInfosForUniqueWorkFlow(SyncWorker.uniqueName(s.target)).first()
                .none { !it.state.isFinished }
        }
        resumePrompt.value = pending?.let {
            ResumePrompt(it.target, machineOf(it.target), it.startedAt, it.online)
        }
    }

    /** [送信]：送信のジョブを投入する */
    fun finishPlay(target: String) {
        resumePrompt.value = null
        SyncWorker.enqueueFinish(app, target)
    }

    /** [まだ遊ぶ] */
    fun keepPlaying() {
        resumePrompt.value = null
    }

    // ---------------------------------------------------------------- 遊ぶ（設計書 §5.1）

    private fun machineOf(target: String) = Presets.byName(target)?.machine ?: target

    fun play(target: String, force: Boolean = false) {
        if (play.value is PlayDialog.Progress) return
        viewModelScope.launch {
            val machine = machineOf(target)
            val t = db.targets().get(target) ?: return@launch
            // エミュが起動できるかを先に確かめる（ロックを取ってから起動できないと困るので）
            val problem = when {
                t.emuPackage == null -> UiText(R.string.err_emu_not_set)
                withContext(Dispatchers.IO) { container.launcherApps.launchIntent(t.emuPackage) } == null ->
                    UiText(R.string.err_emu_missing)
                else -> null
            }
            if (problem != null) {
                play.value = PlayDialog.Error(target, machine, problem)
                return@launch
            }
            play.value = PlayDialog.Progress(target, machine, PlayStep.LOCKING)
            val r = playManager.start(target, force) { step ->
                play.value = PlayDialog.Progress(target, machine, step)
            }
            play.value = when (r) {
                is StartResult.Ready -> {
                    launchEmulator(t, r.session)
                    null
                }
                is StartResult.LockedByOther -> PlayDialog.LockedByOther(target, machine, r.device, r.expiresAt)
                is StartResult.Unreachable -> PlayDialog.Unreachable(target, machine, r.message)
                is StartResult.Conflicts -> PlayDialog.Conflicts(target, machine, r.result)
                is StartResult.Failed -> PlayDialog.Error(target, machine, r.message)
            }
            db.targets().get(target)?.let { refreshLocal(it) }
        }
    }

    /** 423 のダイアログの [奪って起動] */
    fun playForce(target: String) = play(target, force = true)

    /** つながらないときの [同期せずに起動] */
    fun playOffline(target: String) {
        viewModelScope.launch {
            val t = db.targets().get(target) ?: return@launch
            launchEmulator(t, playManager.startOffline(target).session)
            play.value = null
        }
    }

    /** 競合のダイアログの [無視して起動] */
    fun playIgnoringConflicts(target: String) {
        viewModelScope.launch {
            val t = db.targets().get(target) ?: return@launch
            launchEmulator(t, playManager.startIgnoringConflicts(target).session)
            play.value = null
        }
    }

    /** 競合のダイアログの [やめる]：ロックを解除する */
    fun abortPlay(target: String) {
        play.value = null
        viewModelScope.launch { playManager.abort(target) }
    }

    fun dismissPlayDialog() {
        play.value = null
    }

    /** 6. 「プレイ中」の通知を出し、7. エミュを起動する */
    private suspend fun launchEmulator(t: TargetEntity, session: PlaySessionEntity) {
        Notifications.showPlaying(app, t.name, machineOf(t.name), session.online)
        val intent = t.emuPackage?.let { withContext(Dispatchers.IO) { container.launcherApps.launchIntent(it) } }
        if (intent == null) {
            play.value = PlayDialog.Error(t.name, machineOf(t.name), UiText(R.string.err_emu_missing))
            return
        }
        try {
            app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            play.value = PlayDialog.Error(t.name, machineOf(t.name), UiText(R.string.err_emu_missing))
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
            // この端末でプレイ中のターゲットは取得しないので、対象から外す
            val playing = db.playSessions().observeAll().first().map { it.target }.toSet()
            val targets = db.targets().getAll().filter { it.enabled && it.name !in playing }
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
