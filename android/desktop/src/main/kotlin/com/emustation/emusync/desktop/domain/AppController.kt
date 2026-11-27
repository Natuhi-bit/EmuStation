package com.emustation.emusync.desktop.domain

import com.emustation.emusync.data.api.CaCertificates
import com.emustation.emusync.data.api.CaStore
import com.emustation.emusync.data.api.EmusyncApi
import com.emustation.emusync.desktop.data.AppConfig
import com.emustation.emusync.desktop.data.ConfigStore
import com.emustation.emusync.desktop.data.Game
import com.emustation.emusync.desktop.data.RecordStore
import com.emustation.emusync.desktop.data.SystemConfig
import com.emustation.emusync.desktop.meta.GameMetadata
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    /** ゲームの情報とカバーをインターネットから調べる（テストでは差し替える） */
    private val metadata: GameMetadata = GameMetadata(File(root, "metadata")),
) {
    private val _config = MutableStateFlow(store.load())
    val config: StateFlow<AppConfig> = _config.asStateFlow()

    val records = RecordStore(File(root, "records.json"))
    val sync = syncFactory({ _config.value }, records)
    val play = PlayController({ _config.value }, ::saveGame, sync, runAndWait)

    private val _server = MutableStateFlow<ServerStatus>(ServerStatus.Checking)
    val server: StateFlow<ServerStatus> = _server.asStateFlow()

    private val _systems = MutableStateFlow<Map<String, SystemStatus>>(emptyMap())

    /** 機種ごとの、実行中の手動の同期の数 */
    private val syncingNow = java.util.concurrent.ConcurrentHashMap<String, Int>()
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
    /**
     * 設定を書き換えて保存する。画面の操作と裏の処理（情報の取得・プレイ時間の記録）から同時に呼ばれても、
     * どちらの変更も失われないように 1 つずつ行う（読んで・変えて・書くまでを、このオブジェクトのロックで）
     */
    @Synchronized
    fun update(f: (AppConfig) -> AppConfig) {
        val c = f(_config.value)
        _config.value = c
        store.save(c)
        bump()
    }

    fun saveGame(g: Game) = update { it.withGame(g) }

    /** ゲームをライブラリに加えて、情報とカバーを調べる */
    fun addGames(games: List<Game>) {
        if (games.isEmpty()) return
        update { c -> games.fold(c) { acc, g -> acc.withGame(g) } }
        fetchInfo(games.map { it.id })
    }

    // ---------------------------------------------------------------- ゲームの情報とカバー
    private val _infoBusy = MutableStateFlow<Set<String>>(emptySet())

    /** 情報とカバーを調べている最中のゲーム */
    val infoBusy: StateFlow<Set<String>> = _infoBusy.asStateFlow()
    private val infoMutex = Mutex()
    private val coversDir get() = File(root, "covers")

    /**
     * ゲームの情報（日本語のタイトル・型番・地域）とカバーを調べる。
     * [ids] が null ならライブラリ全体。[again] でなければ、まだ調べていないゲームだけ。
     * [again] のときは、自分で書き換えたタイトル・カバーも、調べた結果で置き換える
     */
    fun fetchInfo(ids: Collection<String>? = null, again: Boolean = false) = scope.launch {
        val targets = _config.value.games.filter { (ids == null || it.id in ids) && (again || it.infoCheckedAt == null) }.map { it.id }
        if (targets.isEmpty()) return@launch
        _infoBusy.update { it + targets }
        var found = 0
        try {
            infoMutex.withLock {
                for (id in targets) {
                    try {
                        val g = _config.value.games.firstOrNull { it.id == id } ?: continue
                        val s = _config.value.system(g.target)
                        val m = metadata.lookup(g.target, g.rom, s?.savePath, s?.emulatorExe)
                        val coverFile = if (m.cover != null && (again || !g.coverEdited)) saveCover(g, m.cover) else null
                        update { c ->
                            val cur = c.games.firstOrNull { it.id == id } ?: return@update c
                            c.withGame(
                                cur.copy(
                                    title = if (m.title != null && (again || !cur.titleEdited)) m.title else cur.title,
                                    titleEdited = if (again && m.title != null) false else cur.titleEdited,
                                    serial = m.serial ?: cur.serial,
                                    region = m.region ?: cur.region,
                                    coverPath = coverFile?.path ?: cur.coverPath,
                                    coverEdited = if (coverFile != null) false else cur.coverEdited,
                                    infoCheckedAt = System.currentTimeMillis(),
                                ),
                            )
                        }
                        if (m.title != null || m.cover != null) found++
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // 1 本調べられなくても、ほかは続ける
                    } finally {
                        _infoBusy.update { it - id }
                    }
                }
            }
        } finally {
            _infoBusy.update { it - targets.toSet() }
        }
        if (found > 0 && (again || targets.size > 1)) notify(Notice("ゲームの情報", "$found 本の情報とカバーを取得しました"))
    }

    /** カバーを covers/ に置く。名前を毎回変える（画像の読み込みの使い回しで古いものが出ないように）。前のものは消す */
    private fun saveCover(g: Game, bytes: ByteArray): File {
        coversDir.mkdirs()
        val ext = if (bytes[0] == 0x89.toByte()) "png" else "jpg"
        val f = File(coversDir, "${g.id}-${System.currentTimeMillis()}.$ext")
        f.writeBytes(bytes)
        g.coverPath?.let { File(it) }?.takeIf { it.parentFile?.canonicalPath == coversDir.canonicalPath && it != f }?.delete()
        return f
    }

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
                syncing = false,
            )
        }
        // 「同期中」は、書き込むときの数で決める（調べている間に同期が終わっても「同期中」のまま残らないように）
        _systems.update { m.mapValues { (t, st) -> st.copy(syncing = syncingNow.containsKey(t)) } }
        bump()
    }

    /** 手動の同期（今すぐ同期・取得のみ・送信のみ）。競合の解決は [only] と [prefer] を付ける */
    fun syncNow(target: String, mode: SyncMode = SyncMode.SYNC, prefer: Prefer? = null, only: Set<String>? = null) {
        scope.launch {
            syncingNow.merge(target, 1, Int::plus)
            _systems.update { it + (target to (it[target] ?: SystemStatus(null, 0, null, false)).copy(syncing = true)) }
            val o = try {
                sync.run(target, mode, prefer, only = only, trigger = "manual")
            } finally {
                // 同じ機種を続けて同期したときは、全部終わるまで「同期中」
                syncingNow.compute(target) { _, n -> if (n == null || n <= 1) null else n - 1 }
                _systems.update { m -> m[target]?.let { m + (target to it.copy(syncing = syncingNow.containsKey(target))) } ?: m }
            }
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
        runCatching { play.retryReleases() } // 外せなかったロック（送信の設定に関係なく）
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
