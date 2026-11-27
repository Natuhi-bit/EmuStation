package com.emustation.emusync.domain.sync

import java.io.InputStream

/** 設計書 §4.5 のモード */
enum class SyncMode {
    /** 取得と送信の両方 */
    SYNC,

    /** 送信しない */
    PULL,

    /** 取得しない */
    PUSH;

    val pull: Boolean get() = this != PUSH
    val push: Boolean get() = this != PULL
}

/** 設計書 §4.4 の競合の解決方法 */
enum class Prefer { LOCAL, REMOTE }

/**
 * 同期の単位（設計書 §1.1）。
 *
 * @param name ターゲット名＝サーバー上のフォルダ名
 * @param root 同期するフォルダの実パス（データフォルダ＋プリセットの相対パス）
 * @param excludes 除外パターン（fnmatch 形式）
 */
data class SyncTarget(
    val name: String,
    val root: String,
    val excludes: List<String> = emptyList(),
)

/** manifest の1件。[rel] はターゲット名の prefix を取り除いた相対パス */
data class RemoteFile(
    val rel: String,
    val version: Int,
    val sha256: String,
    val size: Long,
    val updatedAt: Double,
    val device: String,
)

/** 前回同期した時点の状態（設計書 §4.1 の base） */
data class BaseEntry(val sha256: String, val version: Int)

data class Downloaded(val version: Int, val sha256: String)

/** 送信する内容。[open] は何度呼んでもよい（OkHttp が再送することがある） */
class UploadContent(
    val size: Long,
    val sha256: String,
    val open: () -> InputStream,
)

sealed interface UploadOutcome {
    /** 新しい版ができた、または内容が同じで版が進まなかった（[unchanged]） */
    data class Ok(val version: Int, val sha256: String, val unchanged: Boolean) : UploadOutcome

    /** 409：サーバーの版が If-Match と違う＝他の端末が先に更新した */
    data class Rejected(val serverVersion: Int, val serverSha256: String?) : UploadOutcome
}

data class SyncConflict(
    val rel: String,
    val kind: Kind,
    /** 競合を検出した時点のサーバーの版 */
    val serverVersion: Int,
    val serverSha256: String?,
    /** conflicts/ に保存したサーバー版のパス。dry-run と 409 のときは null */
    val savedCopyPath: String?,
) {
    enum class Kind {
        /** 両方が変化した、または初回で内容が違う */
        BOTH_CHANGED,

        /** 送信中に他の端末が更新した（409）。再同期が必要 */
        UPLOAD_REJECTED,
    }
}

/** ファイルごとの失敗。[cause] は元の例外（利用者向けの文言に直すときに使う） */
data class SyncError(val rel: String, val message: String, val cause: Throwable? = null)

data class SyncResult(
    val target: String,
    val mode: SyncMode,
    val prefer: Prefer?,
    val dryRun: Boolean,
    val uploaded: List<String>,
    val downloaded: List<String>,
    val conflicts: List<SyncConflict>,
    val errors: List<SyncError>,
) {
    val isClean: Boolean get() = conflicts.isEmpty() && errors.isEmpty()
    val hasChanges: Boolean
        get() = uploaded.isNotEmpty() || downloaded.isNotEmpty() || !isClean
}
