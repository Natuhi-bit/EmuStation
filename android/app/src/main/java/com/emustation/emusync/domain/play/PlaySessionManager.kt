package com.emustation.emusync.domain.play

import com.emustation.emusync.R
import com.emustation.emusync.data.SyncOutcome
import com.emustation.emusync.data.SyncRepository
import com.emustation.emusync.data.api.ApiException
import com.emustation.emusync.data.api.PlayLocks
import com.emustation.emusync.data.db.PlaySessionDao
import com.emustation.emusync.data.db.PlaySessionEntity
import com.emustation.emusync.data.db.TargetDao
import com.emustation.emusync.domain.sync.SyncMode
import com.emustation.emusync.domain.sync.SyncResult
import com.emustation.emusync.ui.common.UiText
import com.emustation.emusync.ui.common.connectionErrorText
import kotlin.coroutines.cancellation.CancellationException

/** 「遊ぶ」の途中経過 */
enum class PlayStep { FINISHING_PREVIOUS, LOCKING, SYNCING }

/** 「遊ぶ」の結果（設計書 §5.1） */
sealed interface StartResult {
    /** 準備ができた。プレイセッションを記録したので、エミュを起動してよい */
    data class Ready(val session: PlaySessionEntity, val sync: SyncResult?) : StartResult

    /** 423：他の端末がプレイ中。[やめる] / [奪って起動] */
    data class LockedByOther(val device: String, val expiresAt: Double) : StartResult

    /** サーバーにつながらない。[同期せずに起動] / [やめる] */
    data class Unreachable(val message: UiText) : StartResult

    /** 同期で競合が出た。ロックは持ったまま。[無視して起動] / [やめる] */
    data class Conflicts(val result: SyncResult) : StartResult

    /** 起動をやめる */
    data class Failed(val message: UiText) : StartResult
}

/** プレイ後の送信の結果（設計書 §5.2） */
sealed interface FinishResult {
    data object NoSession : FinishResult

    /** 送信してロックを解除し、プレイセッションを消した。競合が出ていれば [SyncResult.conflicts] にある */
    data class Done(val result: SyncResult) : FinishResult

    /** 送信できなかった。プレイセッションは残す（あとで再試行） */
    data class Failed(val message: UiText, val unreachable: Boolean) : FinishResult
}

/**
 * プレイの流れ（設計書 §5）。通知とエミュの起動は呼び出し側（画面・ジョブ）が行う。
 *
 * @param locks サーバーのロック API。サーバーが未設定なら null を返す
 */
class PlaySessionManager(
    private val targets: TargetDao,
    private val sessions: PlaySessionDao,
    private val sync: SyncRepository,
    private val locks: suspend () -> PlayLocks?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun session(target: String): PlaySessionEntity? = sessions.get(target)

    /**
     * 「遊ぶ」の 1〜5（ロック → SYNC → 競合の確認 → プレイセッションの記録）。
     * @param force 他の端末のロックを奪う
     */
    suspend fun start(target: String, force: Boolean = false, onStep: (PlayStep) -> Unit = {}): StartResult {
        val t = targets.get(target) ?: return StartResult.Failed(UiText(R.string.err_target_missing, target))

        // 1. 前回のプレイセッションが残っていれば、先にその送信を終える
        if (sessions.get(target) != null) {
            onStep(PlayStep.FINISHING_PREVIOUS)
            val f = finish(target)
            // つながらないときは、このあとのロック取得でも同じになるので、そこで「同期せずに起動」を選べるようにする
            if (f is FinishResult.Failed && !f.unreachable) {
                return StartResult.Failed(UiText(R.string.err_previous_finish_failed, f.message))
            }
        }

        // 2. ロック
        onStep(PlayStep.LOCKING)
        val api = locks() ?: return StartResult.Unreachable(UiText(R.string.err_server_not_configured))
        try {
            api.lock(target, PLAY_LOCK_TTL, force)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException.Locked423) {
            return StartResult.LockedByOther(e.device, e.expiresAt)
        } catch (e: Exception) {
            return if (isUnreachable(e)) {
                StartResult.Unreachable(connectionErrorText(e))
            } else {
                StartResult.Failed(connectionErrorText(e))
            }
        }

        // 3. 同期（取得と送信）
        onStep(PlayStep.SYNCING)
        val r = when (val o = sync.run(t, SyncMode.SYNC)) {
            is SyncOutcome.Failed -> {
                releaseQuietly(api, target)
                return StartResult.Failed(o.message)
            }
            is SyncOutcome.Done -> o.result
        }

        // 4. 競合が出たら起動を止める（ロックは持ったまま。無視して起動するか、やめるかを選ぶ）
        if (r.conflicts.isNotEmpty()) return StartResult.Conflicts(r)

        // 5. プレイセッションを記録する
        return StartResult.Ready(begin(target, online = true), r)
    }

    /** サーバーにつながらないまま起動する（オフラインのプレイセッション） */
    suspend fun startOffline(target: String): StartResult.Ready {
        // 前回のプレイセッションが残っていれば、その開始時刻を引き継ぐ（まだ送っていないので）
        val prev = sessions.get(target)
        val s = PlaySessionEntity(target, prev?.startedAt ?: clock(), online = false)
        sessions.upsert(s)
        return StartResult.Ready(s, null)
    }

    /** 競合を無視して起動する（ロックは [start] で取得済み） */
    suspend fun startIgnoringConflicts(target: String): StartResult.Ready =
        StartResult.Ready(begin(target, online = true), null)

    /** 競合が出たあとで起動をやめる。ロックを解除する */
    suspend fun abort(target: String) {
        locks()?.let { releaseQuietly(it, target) }
    }

    private suspend fun begin(target: String, online: Boolean): PlaySessionEntity {
        val s = PlaySessionEntity(target, clock(), online)
        sessions.upsert(s)
        return s
    }

    /**
     * プレイ後の送信（設計書 §5.2）。PUSH → ロック解除 → プレイセッションの削除。
     * オフラインのプレイセッションなら、まずロックの取得を試みる（失敗してもよい）。
     * 取得（pull）はしない。
     */
    suspend fun finish(target: String): FinishResult {
        val s = sessions.get(target) ?: return FinishResult.NoSession
        val t = targets.get(target)
        if (t == null) {
            sessions.delete(target)
            return FinishResult.NoSession
        }
        val api = locks()
        if (!s.online && api != null) {
            try {
                api.lock(target, PLAY_LOCK_TTL, force = false)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 失敗してもよい（他の端末がプレイ中でも、手元の変更は送る）
            }
        }
        val r = when (val o = sync.run(t, SyncMode.PUSH)) {
            is SyncOutcome.Failed -> return FinishResult.Failed(o.message, o.cause?.let(::isUnreachable) == true)
            is SyncOutcome.Done -> o.result
        }
        if (r.errors.isNotEmpty()) {
            return FinishResult.Failed(UiText(R.string.err_some_files_failed, r.errors.size), unreachable = false)
        }
        if (api != null) releaseQuietly(api, target)
        sessions.delete(target)
        return FinishResult.Done(r)
    }

    private suspend fun releaseQuietly(api: PlayLocks, target: String) {
        try {
            api.unlock(target)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 解除できなくても、期限切れで自動的に解除される
        }
    }

    companion object {
        /** 設計書 §5.1：プレイ中ロックは 12 時間 */
        const val PLAY_LOCK_TTL = 43200

        /** サーバーまで届かなかったエラーか（VPN を確認） */
        fun isUnreachable(e: Throwable) = e is ApiException.Offline || e is ApiException.Network
    }
}
