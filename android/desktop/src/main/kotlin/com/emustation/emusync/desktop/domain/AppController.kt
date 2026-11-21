package com.emustation.emusync.desktop.domain

import com.emustation.emusync.data.api.CaCertificates
import com.emustation.emusync.data.api.CaStore
import com.emustation.emusync.data.api.EmusyncApi
import com.emustation.emusync.desktop.data.AppConfig
import com.emustation.emusync.desktop.data.ConfigStore
import com.emustation.emusync.desktop.data.Game
import com.emustation.emusync.desktop.data.RecordStore
import com.emustation.emusync.desktop.data.SystemConfig
import com.emustation.emusync.desktop.platform.AppPaths
import com.emustation.emusync.desktop.platform.Autostart
import com.emustation.emusync.desktop.platform.Launcher
import com.emustation.emusync.domain.sync.Prefer
import com.emustation.emusync.domain.sync.SyncMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

sealed interface ServerStatus {
    data object Checking : ServerStatus
    data object NotConfigured : ServerStatus
    data class Online(val device: String) : ServerStatus
    data class Offline(val message: String) : ServerStatus
}

/** 機種ごとの、セーブの同期の様子 */
data class SystemStatus(val unsent: Int?, val conflicts: Int, val lastSync: Long?, val syncing: Boolean)

/** トレイの通知・画面の下に出す知らせ */
data class Notice(val title: String, val text: String, val error: Boolean = false)

/** アプリ全体の状態と操作（画面とトレイから使う） */
class AppController(
    private val store: ConfigStore = ConfigStore(),
    val root: File = AppPaths.root,
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    syncFactory: ((() -> AppConfig), RecordStore) -> SyncService = { c, r -> SyncService(c, root, r) },
    /** エミュを起動して終了を待つ（テストでは差し替える） */
    runAndWait: suspend (List<String>, () -> Unit) -> Long = Launcher::runAndWait,
) {
    private val _config = MutableStateFlow(store.load())
    val config: StateFlow<AppConfig> = _config.asStateFlow()

    val records = RecordStore(File(root, "records.json"))
    val sync = syncFactory({ _config.value }, records)
    val play = PlayController({ _config.value }, ::saveGame, sync, runAndWait)

    private val _server = MutableStateFlow<ServerStatus>(ServerStatus.Checking)
    val server: StateFlow<ServerStatus> = _server.asStateFlow()

    private val _systems = MutableStateFlow<Map<String, SystemStatus>>(emptyMap())
    val systems: StateFlow<Map<String, SystemStatus>> = _systems.asStateFlow()

    private val _notices = MutableSharedFlow<Notice>(extraBufferCapacity = 8)
    val notices = _notices.asSharedFlow()

    /** 設定・ログ・競合が変わったら増える（画面の読み直し用） */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    fun notify(n: Notice) {
        _notices.tryEmit(n)
    }

    private fun bump() = _revision.update { it + 1 }

    // ---------------------------------------------------------------- 設定
    fun update(f: (AppConfig) -> AppConfig) {
        val c = f(_config.value)
        _config.value = c
        store.save(c)
        bump()
    }

    fun saveGame(g: Game) = update { it.withGame(g) }

    fun removeGame(id: String) = update { c -> c.copy(games = c.games.filter { it.id != id }) }

    fun saveSystem(s: SystemConfig) = update { it.withSystem(s) }

    fun saveServer(url: String, token: String?) {
        val normalized = EmusyncApi.normalizeBaseUrl(url) ?: url.trim()
        update { c -> (if (token != null) c.withToken(token) else c).copy(serverUrl = normalized) }
        refresh()
    }

    /** CA 証明書を取り込む。返り値は指紋（SHA-256） */
    fun importCa(file: File): String {
        val (name, certs) = CaStore(File(root, "ca")).import(file.readBytes())
        update { it.copy(caFile = name) }
        refresh()
        return CaCertificates.sha256Fingerprint(certs.first())
    }

    fun removeCa() {
        config.value.caFile?.let { CaStore(File(root, "ca")).delete(it) }
        update { it.copy(caFile = null) }
        refresh()
    }

    fun caFingerprint(): String? = config.value.caFile
        ?.let { CaStore(File(root, "ca")).load(it) }?.firstOrNull()?.let(CaCertificates::sha256Fingerprint)

    fun setAutostart(enabled: Boolean): Boolean = Autostart.set(enabled).also { bump() }

    // ---------------------------------------------------------------- サーバー・機種の状態
    fun refresh() {
        scope.launch {
            _server.value = ServerStatus.Checking
            val api = sync.api() as? EmusyncApi
            _server.value = if (api == null) {
                ServerStatus.NotConfigured
            } else {
                try {
                    val pong = api.ping()
                    if (pong.device != _config.value.deviceName) update { it.copy(deviceName = pong.device) }
                    ServerStatus.Online(pong.device)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ServerStatus.Offline(Messages.connection(e))
                }
            }
            refreshSystems()
        }
    }

    suspend fun refreshSystems() {
        val log = records.log()
        val m = _config.value.systems.associate { s ->
            s.target to SystemStatus(
                unsent = sync.unsentCount(s.target),
                conflicts = records.conflicts(s.target).size,
                lastSync = log.firstOrNull { it.target == s.target && !it.dryRun && it.fatal == null }?.startedAt,
                syncing = _systems.value[s.target]?.syncing == true,
            )
        }
        _systems.value = m
        bump()
    }

    /** 手動の同期（今すぐ同期・取得のみ・送信のみ）。競合の解決は [only] と [prefer] を付ける */
    fun syncNow(target: String, mode: SyncMode = SyncMode.SYNC, prefer: Prefer? = null, only: Set<String>? = null) {
        scope.launch {
            _systems.update { it + (target to (it[target] ?: SystemStatus(null, 0, null, false)).copy(syncing = true)) }
            val o = sync.run(target, mode, prefer, only = only, trigger = "manual")
            _systems.update { it + (target to it.getValue(target).copy(syncing = false)) }
            val machine = Presets.machineOf(target)
            when (o) {
                is SyncOutcome.Done -> notify(
                    Notice(machine, Messages.summary(o.result.uploaded.size, o.result.downloaded.size, o.result.conflicts.size, o.result.errors.size),
                        error = o.result.conflicts.isNotEmpty() || o.result.errors.isNotEmpty()),
                )
                is SyncOutcome.Failed -> notify(Notice(machine, o.message, error = true))
            }
            refreshSystems()
        }
    }

    fun syncAll() = _config.value.systems.filter { it.enabled && it.savePath.isNotBlank() }.forEach { syncNow(it.target) }

    fun playGame(g: Game, force: Boolean = false) = scope.launch { play.play(g, force); afterPlay() }
    fun playOffline(g: Game) = scope.launch { play.playOffline(g); afterPlay() }
    fun playIgnoringConflicts(g: Game) = scope.launch { play.playIgnoringConflicts(g); afterPlay() }
    fun abortPlay(g: Game) = scope.launch { play.abort(g) }

    private suspend fun afterPlay() {
        when (val p = play.phase.value) {
            is PlayPhase.Finished -> notify(Notice(p.game!!.title, p.text, error = p.conflicts > 0))
            is PlayPhase.Failed -> notify(Notice(p.game!!.title, p.message, error = true))
            else -> Unit
        }
        refreshSystems()
    }

    // ---------------------------------------------------------------- 定期送信（設計書 §5.3 と同じ考え方）
    /**
     * 1時間ごとに、手元の変更を送信する（取得はしない。プレイ中の機種は送らない）。
     * 送り残したプレイ（つながらなかったとき）も、ここで送る
     */
    fun startPeriodic(intervalMs: Long = 60 * 60_000L, firstDelayMs: Long = 60_000L) {
        scope.launch {
            delay(firstDelayMs)
            while (true) {
                runCatching { periodicOnce() }
                delay(intervalMs)
            }
        }
    }

    suspend fun periodicOnce() {
        if (!_config.value.periodicPush) return
        val playing = play.phase.value.let { if (it is PlayPhase.Playing || it is PlayPhase.Working) it.game?.target else null }
        // 送り残したプレイ
        for (s in records.sessions().filter { it.target != playing }) {
            val o = play.finish(s.target)
            if (o is SyncOutcome.Done) notify(Notice(Presets.machineOf(s.target), "送り残していたセーブを送信しました"))
            else if (Messages.isUnreachable((o as SyncOutcome.Failed).cause)) return
        }
        for (s in _config.value.systems.filter { it.enabled && it.savePath.isNotBlank() && it.target != playing }) {
            val before = records.conflicts(s.target).map { it.rel }.toSet()
            when (val o = sync.run(s.target, SyncMode.PUSH, trigger = "periodic", quiet = true)) {
                is SyncOutcome.Done -> {
                    val fresh = o.result.conflicts.count { it.rel !in before }
                    if (fresh > 0) notify(Notice(Presets.machineOf(s.target), "競合が $fresh 件あります", error = true))
                }
                is SyncOutcome.Failed -> if (Messages.isUnreachable(o.cause)) return
            }
        }
        refreshSystems()
    }

    /** 接続テスト（入力中の値で） */
    suspend fun testConnection(url: String, token: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val c = _config.value.copy(serverUrl = url).withToken(token)
            val api = SyncService.defaultApi(c, root) ?: error("URL かトークンが正しくありません")
            api.ping().device
        }.recoverCatching { e -> throw IllegalStateException(Messages.connection(e)) }
    }
}
