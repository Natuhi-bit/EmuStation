package com.emustation.emusync.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** ターゲット（設計書 §7）。同期するフォルダ＝ [dataDir] ＋ [relSubdir] */
@Entity(tableName = "targets")
data class TargetEntity(
    @PrimaryKey val name: String,
    /** エミュのパッケージ名。未設定なら null */
    val emuPackage: String?,
    /** エミュのデータフォルダの実パス（例 /storage/emulated/0/PSP） */
    val dataDir: String,
    /** データフォルダからの相対パス（例 SAVEDATA）。空ならデータフォルダそのもの */
    val relSubdir: String,
    val excludes: List<String>,
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
)

/** 前回同期の状態（設計書 §4.1 の base） */
@Entity(tableName = "base_state", primaryKeys = ["target", "rel"])
data class BaseStateEntity(
    val target: String,
    val rel: String,
    val sha256: String,
    val version: Int,
)

/** ハッシュのキャッシュ（設計書 §4.6） */
@Entity(tableName = "hash_cache", primaryKeys = ["target", "rel"])
data class HashCacheEntity(
    val target: String,
    val rel: String,
    val size: Long,
    val lastModified: Long,
    val sha256: String,
)

/** プレイセッション（設計書 §5。段階 4 で使う） */
@Entity(tableName = "play_sessions")
data class PlaySessionEntity(
    @PrimaryKey val target: String,
    val startedAt: Long,
    val online: Boolean,
)

/** 競合（設計書 §4.4）。[kind] は SyncConflict.Kind の名前 */
@Entity(tableName = "conflicts", primaryKeys = ["target", "rel"])
data class ConflictEntity(
    val target: String,
    val rel: String,
    val kind: String,
    val serverVersion: Int,
    val serverSha256: String?,
    val savedCopyPath: String?,
    val detectedAt: Long,
)

/**
 * 同期ログ（設計書 §7）。[errors] はファイルごとの失敗（`相対パス: 内容`）。
 * [fatal] は同期全体の失敗（つながらない、権限がない など）。成功なら null
 */
@Entity(tableName = "sync_log")
data class SyncLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val target: String,
    val mode: String,
    val prefer: String?,
    val dryRun: Boolean,
    val startedAt: Long,
    val finishedAt: Long,
    val uploaded: Int,
    val downloaded: Int,
    val conflicts: Int,
    val errors: List<String>,
    val fatal: String?,
)
