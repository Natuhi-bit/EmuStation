package com.emustation.emusync.desktop.domain

import com.emustation.emusync.data.api.ApiException
import com.emustation.emusync.data.api.PlayLocks
import com.emustation.emusync.desktop.data.AppConfig
import com.emustation.emusync.desktop.data.Game
import com.emustation.emusync.desktop.data.PlaySession
import com.emustation.emusync.desktop.platform.Launcher
import com.emustation.emusync.domain.sync.SyncMode
import com.emustation.emusync.domain.sync.SyncResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.cancellation.CancellationException

/** 「プレイ」の進み具合（Steam のように、画面の上に重ねて出す） */
sealed interface PlayPhase {
    val game: Game?

    data class Working(override val game: Game, val text: String) : PlayPhase
    data class LockedByOther(override val game: Game, val device: String, val expiresAt: Double) : PlayPhase
    data class Unreachable(override val game: Game, val message: String) : PlayPhase
    data class Conflicts(override val game: Game, val result: SyncResult) : PlayPhase
    data class Playing(override val game: Game, val since: Long, val online: Boolean) : PlayPhase
    data class Finished(override val game: Game, val text: String, val conflicts: Int) : PlayPhase
    data class Failed(override val game: Game, val message: String) : PlayPhase
}

/**
 * Steam のように、アプリを通してエミュを起動する。
 * ロック → 取得と送信 → 起動 → 終了を待つ → 送信 → ロック解除。
 * つながらないときは同期せずに起動し、あとで送る（設計書 §5 と同じ考え方）。
 */
class PlayController(
    private val config: () -> AppConfig,
    private val updateGame: (Game) -> Unit,
    private val sync: SyncService,
    private val runAndWait: suspend (List<String>, () -> Unit) -> Long = Launcher::runAndWait,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _phase = MutableStateFlow<PlayPhase?>(null)
    val phase: StateFlow<PlayPhase?> = _phase.asStateFlow()

    val busy: Boolean get() = _phase.value.let { it is PlayPhase.Working || it is PlayPhase.Playing }

    fun dismiss() {
        if (!busy) _phase.value = null
    }

    private fun locks(): PlayLocks? = sync.api() as? PlayLocks

    private fun command(game: Game): List<String>? {
        val s = config().system(game.target) ?: return null
        if (s.emulatorExe.isBlank()) return null
        return Launcher.buildCommand(s.emulatorExe, s.args, game.rom)
    }

    /**
     * 始めてよいか確かめ、同時に始まらないように「準備中」にする（確かめるのと印を付けるのを 1 回で）。
     * Enter の長押し（キーリピート）やダブルクリックで何回も押されても、エミュは 1 回だけ起動する
     */
    private fun claim(game: Game, text: String): Boolean {
        while (true) {
            val cur = _phase.value
            if (cur is PlayPhase.Working || cur is PlayPhase.Playing) return false
            if (_phase.compareAndSet(cur, PlayPhase.Working(game, text))) return true
        }
    }

    /** プレイ。[force] は他の端末のロックを奪う */
    suspend fun play(game: Game, force: Boolean = false) {
        if (!claim(game, "準備しています…")) return
        val cmd = command(game) ?: return fail(game, noEmulator(game))
        if (!java.io.File(cmd.first()).isFile) return fail(game, "エミュが見つかりません: ${cmd.first()}")

        // 1. 前回のプレイの送信が済んでいなければ、先に送る
        if (sync.records.sessions().any { it.target == game.target }) {
            _phase.value = PlayPhase.Working(game, "前回のプレイのセーブを送信しています…")
            val f = finish(game.target)
            if (f is SyncOutcome.Failed && !Messages.isUnreachable(f.cause)) return fail(game, "前回のプレイのセーブを送信できませんでした。${f.message}")
        }

        // 2. ロック
        _phase.value = PlayPhase.Working(game, "クラウドに接続しています…")
        val api = locks() ?: return run { _phase.value = PlayPhase.Unreachable(game, "サーバーが設定されていません") }
        try {
            api.lock(game.target, PLAY_LOCK_TTL, force)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException.Locked423) {
            _phase.value = PlayPhase.LockedByOther(game, e.device, e.expiresAt)
            return
        } catch (e: Exception) {
            _phase.value = if (Messages.isUnreachable(e)) PlayPhase.Unreachable(game, Messages.connection(e))
            else PlayPhase.Failed(game, Messages.connection(e))
            return
        }

        // 3. 取得と送信
        _phase.value = PlayPhase.Working(game, "クラウドと同期しています…")
        when (val o = sync.run(game.target, SyncMode.SYNC, trigger = "play")) {
            is SyncOutcome.Failed -> {
                runCatching { api.unlock(game.target) }
                return fail(game, o.message)
            }
            is SyncOutcome.Done -> if (o.result.conflicts.isNotEmpty()) {
                _phase.value = PlayPhase.Conflicts(game, o.result) // ロックは持ったまま
                return
            }
        }
        launch(game, cmd, online = true)
    }

    /** つながらないまま起動する */
    suspend fun playOffline(game: Game) {
        if (!claim(game, "起動しています…")) return
        val cmd = command(game) ?: return fail(game, noEmulator(game))
        launch(game, cmd, online = false)
    }

    /** 競合を無視して起動する（ロックは取得済み） */
    suspend fun playIgnoringConflicts(game: Game) {
        if (!claim(game, "起動しています…")) return
        val cmd = command(game) ?: return fail(game, noEmulator(game))
        launch(game, cmd, online = true)
    }

    private fun noEmulator(game: Game) =
        "${Presets.machineOf(game.target)} のエミュが設定されていません。設定 → エミュレーターで選んでください"

    /** 競合のダイアログで [やめる]。ロックを解除する */
    suspend fun abort(game: Game) {
        runCatching { locks()?.unlock(game.target) }
        _phase.value = null
    }

    /** 4. 起動して終了を待ち、5. 送信してロックを解除する */
    private suspend fun launch(game: Game, cmd: List<String>, online: Boolean) {
        val prev = sync.records.sessions().firstOrNull { it.target == game.target }
        sync.records.putSession(PlaySession(game.target, game.id, prev?.startedAt ?: clock(), online))
        _phase.value = PlayPhase.Working(game, "${game.title} を起動しています…")
        val seconds = try {
            runAndWait(cmd) { _phase.value = PlayPhase.Playing(game, clock(), online) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            sync.records.removeSession(game.target)
            return fail(game, "起動できませんでした: ${e.message}")
        }
        val c = config()
        val g = c.games.firstOrNull { it.id == game.id } ?: game
        updateGame(g.copy(lastPlayedAt = clock(), playSeconds = g.playSeconds + seconds))

        _phase.value = PlayPhase.Working(game, "クラウドに保存しています…")
        when (val f = finish(game.target)) {
            is SyncOutcome.Done -> _phase.value = PlayPhase.Finished(
                game,
                if (f.result.conflicts.isEmpty()) "クラウドに保存しました（${Messages.summary(f.result.uploaded.size, 0, 0, 0)}）"
                else "競合が ${f.result.conflicts.size} 件あります。セーブデータから確認してください",
                f.result.conflicts.size,
            )
            is SyncOutcome.Failed -> fail(
                game,
                if (Messages.isUnreachable(f.cause)) "サーバーにつながらないため、まだ送信していません。つながったときに自動で送ります"
                else "送信できませんでした。${f.message}",
            )
        }
    }

    /**
     * 送信して終わる（PUSH → ロック解除 → プレイセッション削除）。
     * オフラインのプレイセッションなら、まずロックを試みる（失敗してもよい）
     */
    suspend fun finish(target: String): SyncOutcome {
        val s = sync.records.sessions().firstOrNull { it.target == target } ?: return SyncOutcome.Done(empty(target))
        val api = locks()
        if (!s.online && api != null) runCatching { api.lock(target, PLAY_LOCK_TTL, false) }
        val o = sync.run(target, SyncMode.PUSH, trigger = "finish")
        if (o is SyncOutcome.Done && o.result.errors.isEmpty()) {
            runCatching { api?.unlock(target) }
            sync.records.removeSession(target)
        }
        return if (o is SyncOutcome.Done && o.result.errors.isNotEmpty()) {
            SyncOutcome.Failed("${o.result.errors.size} 件のファイルを送信できませんでした")
        } else {
            o
        }
    }

    private fun fail(game: Game, message: String) {
        _phase.value = PlayPhase.Failed(game, message)
    }

    private fun empty(target: String) = SyncResult(target, SyncMode.PUSH, null, false, emptyList(), emptyList(), emptyList(), emptyList())

    companion object {
        const val PLAY_LOCK_TTL = 43200
    }
}
