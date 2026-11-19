package com.emustation.emusync.data

import com.emustation.emusync.R
import com.emustation.emusync.data.db.AppDatabase
import com.emustation.emusync.data.db.ConflictEntity
import com.emustation.emusync.data.db.RoomBaseStateStore
import com.emustation.emusync.data.db.RoomHashCache
import com.emustation.emusync.data.db.SyncLogEntity
import com.emustation.emusync.data.db.TargetEntity
import com.emustation.emusync.data.prefs.AppSettings
import com.emustation.emusync.data.storage.JavaLocalFs
import com.emustation.emusync.domain.sync.Prefer
import com.emustation.emusync.domain.sync.RemoteApi
import com.emustation.emusync.domain.sync.SyncEngine
import com.emustation.emusync.domain.sync.SyncMode
import com.emustation.emusync.domain.sync.SyncResult
import com.emustation.emusync.domain.sync.SyncTarget
import com.emustation.emusync.domain.sync.TargetMutexes
import com.emustation.emusync.ui.common.UiText
import com.emustation.emusync.ui.common.connectionErrorText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

sealed interface SyncOutcome {
    data class Done(val result: SyncResult) : SyncOutcome

    /** 同期全体が失敗した（つながらない、権限がない など）。[cause] は通信などの例外 */
    data class Failed(val message: UiText, val cause: Throwable? = null) : SyncOutcome
}

/** 手元の状態（ホーム画面の表示用）。通信はしない */
sealed interface LocalState {
    data object NoPermission : LocalState
    data object DataDirMissing : LocalState

    /** [unsent]：前回の同期から変わった、または一度も送っていないファイルの数 */
    data class Scanned(val unsent: Int, val fileCount: Int) : LocalState
    data class Failed(val message: String) : LocalState
}

/** ターゲットの設定から、同期エンジンに渡す形にする */
fun TargetEntity.toSyncTarget(): SyncTarget = SyncTarget(
    name,
    if (relSubdir.isBlank()) dataDir.trimEnd('/') else SyncEngine.joinPath(dataDir, relSubdir.trim('/')),
    excludes,
)

/**
 * 同期を実行し、結果を Room（競合・同期ログ）に記録する。
 * WorkManager のジョブと、画面からの dry-run の両方から使う。
 */
class SyncRepository(
    private val db: AppDatabase,
    private val settings: suspend () -> AppSettings,
    private val apiFactory: (AppSettings) -> RemoteApi,
    private val appDir: () -> String,
    private val hasStorageAccess: () -> Boolean,
    private val resolve: (UiText) -> String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val mutexes: TargetMutexes = TargetMutexes.process,
) {
    /**
     * @param log false にすると同期ログに残さない（保存前の確認など）
     */
    suspend fun run(
        target: TargetEntity,
        mode: SyncMode,
        prefer: Prefer? = null,
        dryRun: Boolean = false,
        log: Boolean = true,
        only: Set<String>? = null,
    ): SyncOutcome {
        val startedAt = clock()
        val outcome = try {
            preflight(target, mode, dryRun)?.let { SyncOutcome.Failed(it) } ?: run {
                val s = settings()
                val engine = SyncEngine(
                    apiFactory(s),
                    JavaLocalFs(RoomHashCache(db.hashCache())),
                    RoomBaseStateStore(db.baseState()),
                    appDir(),
                    mutexes = mutexes,
                )
                val r = engine.sync(target.toSyncTarget(), mode, prefer, dryRun, only)
                if (!dryRun) recordConflicts(target.name, r, only)
                SyncOutcome.Done(r)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SyncOutcome.Failed(connectionErrorText(e), e)
        }
        if (log) {
            val r = (outcome as? SyncOutcome.Done)?.result
            db.syncLog().insertAndTrim(
                SyncLogEntity(
                    target = target.name,
                    mode = mode.name,
                    prefer = prefer?.name,
                    dryRun = dryRun,
                    startedAt = startedAt,
                    finishedAt = clock(),
                    uploaded = r?.uploaded?.size ?: 0,
                    downloaded = r?.downloaded?.size ?: 0,
                    conflicts = r?.conflicts?.size ?: 0,
                    errors = r?.errors?.map { "${it.rel}: ${it.message}" } ?: emptyList(),
                    fatal = (outcome as? SyncOutcome.Failed)?.message?.let(resolve),
                ),
            )
        }
        return outcome
    }

    /**
     * 競合の記録を更新する。[only] を指定した同期では、そのファイルの分だけを置き換える。
     * 解消した競合のサーバー版のコピー（conflicts/ の *.server-v<版>）は消す（サーバーの履歴に残っている）
     */
    private suspend fun recordConflicts(target: String, r: SyncResult, only: Set<String>?) {
        val before = db.conflicts().getAll(target).filter { only == null || it.rel in only }
        val rows = r.conflicts.map {
            ConflictEntity(target, it.rel, it.kind.name, it.serverVersion, it.serverSha256, it.savedCopyPath, clock())
        }
        if (only == null) db.conflicts().replaceTarget(target, rows) else db.conflicts().replaceRels(target, only, rows)
        val kept = rows.mapNotNull { it.savedCopyPath }.toSet()
        withContext(Dispatchers.IO) {
            before.mapNotNull { it.savedCopyPath }.filter { it !in kept }.forEach { File(it).delete() }
        }
    }

    /** 同期を始める前の確認。問題があれば文言を返す */
    private suspend fun preflight(t: TargetEntity, mode: SyncMode, dryRun: Boolean): UiText? {
        val s = settings()
        return when {
            // この端末でプレイ中は取得しない。エミュが開いているファイルを外から書き換えると、
            // 上書きし返されたり壊れたりするおそれがある（設計書 §5.3）
            mode.pull && !dryRun && db.playSessions().get(t.name) != null ->
                UiText(R.string.err_playing_no_pull)
            s.serverUrl.isBlank() || s.token.isBlank() -> UiText(R.string.err_server_not_configured)
            !hasStorageAccess() -> UiText(R.string.err_no_storage_access)
            // データフォルダがない（SD カードが抜けている、設定が違う など）ときは、
            // 取得したファイルを間違った場所に作らないように止める
            !withContext(Dispatchers.IO) { File(t.dataDir).isDirectory } ->
                UiText(R.string.err_data_dir_missing, t.dataDir)
            else -> null
        }
    }

    /** 手元の状態を調べる（通信しない）。同じターゲットの同期とは同時に走らせない */
    suspend fun localState(target: TargetEntity): LocalState {
        if (!hasStorageAccess()) return LocalState.NoPermission
        return withContext(Dispatchers.IO) {
            if (!File(target.dataDir).isDirectory) return@withContext LocalState.DataDirMissing
            try {
                mutexes.of(target.name).withLock {
                    val local = JavaLocalFs(RoomHashCache(db.hashCache())).scan(target.toSyncTarget())
                    val base = RoomBaseStateStore(db.baseState()).load(target.name)
                    LocalState.Scanned(local.count { (rel, sha) -> base[rel]?.sha256 != sha }, local.size)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LocalState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** ターゲットを消す。サーバー上と手元のファイルは消さない */
    suspend fun deleteTarget(name: String) {
        db.targets().delete(name)
        db.baseState().deleteTarget(name)
        withContext(Dispatchers.IO) { db.hashCache().deleteTarget(name) }
        db.conflicts().deleteTarget(name)
        db.playSessions().delete(name)
    }
}
