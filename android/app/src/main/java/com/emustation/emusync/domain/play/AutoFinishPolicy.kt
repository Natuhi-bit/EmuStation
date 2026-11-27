package com.emustation.emusync.domain.play

/**
 * エミュが終わったら自動で送信するための判定（Steam のクラウドセーブのような動き）。
 *
 * Android では、ほかのアプリが終了したことを直接は知れない。そこで次の2つがそろったら「終わった」とみなす。
 * - エミュが前面から消えて、[awayMs] のあいだ戻ってこない（画面が消えている・ロック中の時間は数えない）
 * - セーブのフォルダが [quietMs] のあいだ書き換わっていない（書き込み中に送らない）
 *
 * Android には依存しない。[step] に「今の時刻」「エミュが前面にいるか」「画面が点いているか」
 * 「セーブのフォルダの状態」を渡すと、送るべきかを返す。
 */
class AutoFinishPolicy(
    private val awayMs: Long,
    private val quietMs: Long = QUIET_MS,
) {
    /** エミュから離れた時刻。前面にいる（または画面が消えている）間は null */
    var awaySince: Long? = null
        private set
    private var lastSignature: FolderSignature? = null
    private var stableSince: Long? = null

    /** 「送る」まで、あと何ミリ秒か（離れていなければ null） */
    fun remainingMs(now: Long): Long? {
        val away = awaySince ?: return null
        val quiet = stableSince ?: now
        return maxOf(away + awayMs - now, quiet + quietMs - now, 0)
    }

    /**
     * @return true なら、エミュは終わったとみなして送ってよい
     */
    fun step(now: Long, emulatorInForeground: Boolean, screenOn: Boolean, signature: FolderSignature): Boolean {
        if (emulatorInForeground || !screenOn) {
            // 遊んでいる、または画面が消えているだけ（スリープ中に遊びを中断しても送らない）
            awaySince = null
            stableSince = null
            lastSignature = signature
            return false
        }
        val away = awaySince ?: now.also {
            // 離れ始め。ここからフォルダが静かになるのを数える
            awaySince = it
            stableSince = it
        }
        if (signature != lastSignature) {
            stableSince = now // 書き込みがあった。そこから数え直す
            lastSignature = signature
        }
        return now - away >= awayMs && now - stableSince!! >= quietMs
    }

    companion object {
        /** セーブのフォルダが書き換わらなくなってから待つ時間 */
        const val QUIET_MS = 30_000L

        /** 自動で送ったあと、エミュに戻ってきたら「プレイ中」に戻す猶予 */
        const val RESUME_GRACE_MS = 10 * 60_000L
    }
}

/** セーブのフォルダの状態（ファイルの数・合計サイズ・最新の更新時刻）。変わったら書き込みがあった */
data class FolderSignature(val files: Int, val bytes: Long, val lastModified: Long) {
    companion object {
        val EMPTY = FolderSignature(0, 0, 0)
    }
}
