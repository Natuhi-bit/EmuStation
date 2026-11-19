package com.emustation.emusync.domain.periodic

import com.emustation.emusync.data.SyncOutcome
import com.emustation.emusync.data.SyncRepository
import com.emustation.emusync.data.api.ApiException
import com.emustation.emusync.data.db.ConflictDao
import com.emustation.emusync.data.db.PlaySessionDao
import com.emustation.emusync.data.db.PlaySessionEntity
import com.emustation.emusync.data.db.TargetDao
import com.emustation.emusync.domain.sync.SyncMode
import com.emustation.emusync.ui.common.UiText
import kotlinx.coroutines.flow.first

/** 同じ通知を何度も出さないための記録（DataStore に保存する） */
data class PeriodicNoticeState(
    /** 「送信し忘れ」を知らせたプレイセッション（`ターゲット@開始時刻`） */
    val remindedSessions: Set<String> = emptySet(),
    /** 最後に知らせたエラーの文言。同じエラーは繰り返し知らせない */
    val lastError: String? = null,
)

interface PeriodicNoticeStore {
    suspend fun loadNotices(): PeriodicNoticeState
    suspend fun saveNotices(state: PeriodicNoticeState)
}

/** 定期送信の1回分の結果。通知を出すのは呼び出し側（PeriodicPushWorker） */
data class PeriodicReport(
    /** 送信したファイルの数（ターゲットごと） */
    val pushed: Map<String, Int>,
    /** 今回新しく見つかった競合の数（ターゲットごと） */
    val newConflicts: Map<String, Int>,
    /** プレイ中なので送信しなかったターゲット */
    val skippedPlaying: List<String>,
    /** サーバーにつながらなかった（知らせない） */
    val unreachable: Boolean,
    /** 知らせるべきエラー（前回と同じなら null） */
    val error: UiText?,
    /** 「送信を忘れていませんか」と知らせるプレイセッション */
    val reminders: List<PlaySessionEntity>,
)

/**
 * 定期ジョブ（設計書 §5.3）。1時間ごとに、送信（PUSH）だけを行う。
 *
 * - この端末でプレイ中のターゲットは対象外（エミュが動いている間に外から触らない）
 * - 取得（pull）は「遊ぶ」と手動の同期だけで行う
 * - プレイセッションが 12 時間以上残っていたら、送信し忘れを知らせる（1つのセッションにつき1回）
 */
class PeriodicPushRunner(
    private val targets: TargetDao,
    private val sessions: PlaySessionDao,
    private val conflicts: ConflictDao,
    private val sync: SyncRepository,
    private val notices: PeriodicNoticeStore,
    private val resolve: (UiText) -> String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun run(): PeriodicReport {
        val playing = sessions.observeAll().first()
        val playingNames = playing.map { it.target }.toSet()
        val pushed = mutableMapOf<String, Int>()
        val newConflicts = mutableMapOf<String, Int>()
        val skipped = mutableListOf<String>()
        var unreachable = false
        var error: UiText? = null

        for (t in targets.getAll().filter { it.enabled }) {
            if (t.name in playingNames) {
                skipped += t.name
                continue
            }
            val before = conflicts.getAll(t.name).map { it.rel }.toSet()
            when (val o = sync.run(t, SyncMode.PUSH, quiet = true)) {
                is SyncOutcome.Done -> {
                    if (o.result.uploaded.isNotEmpty()) pushed[t.name] = o.result.uploaded.size
                    val fresh = o.result.conflicts.count { it.rel !in before }
                    if (fresh > 0) newConflicts[t.name] = fresh
                }
                is SyncOutcome.Failed -> {
                    if (o.cause is ApiException.Offline || o.cause is ApiException.Network) {
                        // つながらない（VPN が切れている など）。ほかのターゲットも同じなので、ここでやめる
                        unreachable = true
                        break
                    }
                    if (error == null) error = o.message
                }
            }
        }

        // 同じ通知を繰り返さない
        val state = notices.loadNotices()
        val now = clock()
        val keys = playing.associateBy { "${it.target}@${it.startedAt}" }
        val reminders = keys.filter { (k, s) -> now - s.startedAt >= REMIND_AFTER_MS && k !in state.remindedSessions }
        val errorText = error?.let(resolve)
        val notifyError = errorText != null && errorText != state.lastError
        notices.saveNotices(
            PeriodicNoticeState(
                // 終わったセッションの記録は消す
                remindedSessions = (state.remindedSessions + reminders.keys).filter { it in keys }.toSet(),
                // つながらなかった回は判断できないので、前回の記録を残す
                lastError = if (unreachable) state.lastError else errorText,
            ),
        )
        return PeriodicReport(
            pushed, newConflicts, skipped, unreachable,
            if (notifyError) error else null,
            reminders.values.toList(),
        )
    }

    companion object {
        /** 設計書 §5.3：12 時間以上残っていたら知らせる */
        const val REMIND_AFTER_MS = 12 * 60 * 60 * 1000L
    }
}
