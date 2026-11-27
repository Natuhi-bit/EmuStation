package com.emustation.emusync.domain.play

import com.emustation.emusync.data.db.PlaySessionEntity

/** 見張っているターゲットの、いまの様子（通知に出す） */
sealed interface WatchItem {
    val target: String

    /** 遊んでいる。[remainingMs] はエミュから離れているときの「送るまでの残り」 */
    data class Playing(override val target: String, val remainingMs: Long?) : WatchItem

    /** 自動で送っている */
    data class Finishing(override val target: String) : WatchItem

    /** 送り終わった。[untilMs] までにエミュに戻れば、プレイ中に戻す */
    data class Grace(override val target: String, val untilMs: Long) : WatchItem

    /** エミュに戻ってきたので、プレイ中に戻した。[lockedBy] は、ロックを持っていた他の端末 */
    data class Resumed(override val target: String, val lockedBy: String?) : WatchItem
}

/**
 * プレイ中のターゲットを見張り、エミュが終わったら自動で送る（[AutoFinishPolicy]）。
 * 送ったあと少しの間にエミュに戻ってきたら、プレイ中に戻す。
 *
 * Android に依存しない。前面サービス（PlayWatchService）が数秒おきに [tick] を呼ぶ。
 */
class PlayWatcher(
    private val awayMs: () -> Long,
    private val sessions: suspend () -> List<PlaySessionEntity>,
    /** ターゲットのエミュのパッケージ名 */
    private val packageOf: suspend (String) -> String?,
    /** ターゲットのセーブのフォルダの状態 */
    private val signatureOf: suspend (String) -> FolderSignature,
    /** 送信のジョブが動いているか */
    private val finishRunning: suspend (String) -> Boolean,
    /** 送信のジョブを投入する（送信 → ロック解除 → プレイセッション削除） */
    private val startFinish: (String) -> Unit,
    private val resume: suspend (String) -> ResumeResult,
    private val clock: () -> Long = System::currentTimeMillis,
    private val graceMs: Long = AutoFinishPolicy.RESUME_GRACE_MS,
) {
    private sealed interface State {
        class Watching(val policy: AutoFinishPolicy) : State
        data object Finishing : State
        class Grace(val until: Long) : State
    }

    private val states = mutableMapOf<String, State>()

    /** 見張るものが残っているか（なければ前面サービスを止めてよい） */
    val isIdle: Boolean get() = states.isEmpty()

    /**
     * @param foreground いま前面にいるアプリのパッケージ名
     * @param screenOn 画面が点いていてロックも解除されているか
     */
    suspend fun tick(foreground: String?, screenOn: Boolean): List<WatchItem> {
        val now = clock()
        val items = mutableListOf<WatchItem>()
        val active = sessions().associateBy { it.target }

        // プレイセッションがあるもの
        for (target in active.keys) {
            val pkg = packageOf(target) ?: continue
            when (val st = states[target]) {
                null, is State.Grace -> states[target] = State.Watching(AutoFinishPolicy(awayMs()))
                State.Finishing -> if (!finishRunning(target)) {
                    // 送信に失敗して、プレイセッションが残った（つながらない など）。見張り直す
                    states[target] = State.Watching(AutoFinishPolicy(awayMs()))
                }
                is State.Watching -> Unit
            }
            val st = states.getValue(target)
            if (st is State.Watching) {
                if (st.policy.step(now, foreground == pkg, screenOn, signatureOf(target))) {
                    startFinish(target)
                    states[target] = State.Finishing
                    items += WatchItem.Finishing(target)
                } else {
                    items += WatchItem.Playing(target, st.policy.remainingMs(now))
                }
            } else {
                items += WatchItem.Finishing(target)
            }
        }

        // プレイセッションがなくなったもの
        for (target in states.keys.toList() - active.keys) {
            when (val st = states.getValue(target)) {
                // 手で「送信して終了」した。見張りをやめる
                is State.Watching -> states.remove(target)
                // 自動で送り終わった。しばらく、エミュに戻ってくるのを待つ
                State.Finishing -> if (!finishRunning(target)) {
                    states[target] = State.Grace(now + graceMs)
                    items += WatchItem.Grace(target, now + graceMs)
                } else {
                    items += WatchItem.Finishing(target)
                }
                is State.Grace -> when {
                    now >= st.until -> states.remove(target)
                    screenOn && foreground != null && foreground == packageOf(target) -> {
                        val r = resume(target)
                        states[target] = State.Watching(AutoFinishPolicy(awayMs()))
                        items += WatchItem.Resumed(target, r.lockedBy)
                    }
                    else -> items += WatchItem.Grace(target, st.until)
                }
            }
        }
        return items
    }
}
