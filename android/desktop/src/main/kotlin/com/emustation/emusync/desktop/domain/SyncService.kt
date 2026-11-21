package com.emustation.emusync.desktop.domain

import com.emustation.emusync.data.api.CaStore
import com.emustation.emusync.data.api.EmusyncApi
import com.emustation.emusync.data.api.HttpClients
import com.emustation.emusync.data.storage.JavaLocalFs
import com.emustation.emusync.desktop.data.AppConfig
import com.emustation.emusync.desktop.data.JsonBaseStateStore
import com.emustation.emusync.desktop.data.JsonHashCache
import com.emustation.emusync.desktop.data.LogEntry
import com.emustation.emusync.desktop.data.RecordStore
import com.emustation.emusync.domain.sync.Prefer
import com.emustation.emusync.domain.sync.RemoteApi
import com.emustation.emusync.domain.sync.SyncEngine
import com.emustation.emusync.domain.sync.SyncMode
import com.emustation.emusync.domain.sync.SyncResult
import com.emustation.emusync.domain.sync.SyncTarget
import com.emustation.emusync.domain.sync.TargetMutexes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

sealed interface SyncOutcome {
    data class Done(val result: SyncResult) : SyncOutcome
    data class Failed(val message: String, val cause: Throwable? = null) : SyncOutcome
}

/**
 * 同期を実行して、結果を記録する（競合・同期ログ）。判定は :core の SyncEngine（Python 版・Android 版と同じ）。
 *
 * @param apiFactory 設定から API を作る。テストでは偽サーバーに差し替える
 */
class SyncService(
    private val config: () -> AppConfig,
    private val root: File,
    val records: RecordStore,
    private val apiFactory: (AppConfig) -> RemoteApi? = { defaultApi(it, root) },
    private val clock: () -> Long = System::currentTimeMillis,
    private val mutexes: TargetMutexes = TargetMutexes.process,
) {
    fun api(): RemoteApi? = apiFactory(config())

    suspend fun run(
        target: String,
        mode: SyncMode,
        prefer: Prefer? = null,
        dryRun: Boolean = false,
        only: Set<String>? = null,
        trigger: String? = null,
        log: Boolean = true,
        quiet: Boolean = false,
    ): SyncOutcome {
        val started = clock()
        val outcome = try {
            preflight(target, mode, dryRun)?.let { SyncOutcome.Failed(it) } ?: run {
                val api = api() ?: return@run SyncOutcome.Failed("サーバーが設定されていません")
                val engine = SyncEngine(api, JavaLocalFs(JsonHashCache(root)), JsonBaseStateStore(root), root.path, mutexes = mutexes)
                val r = engine.sync(syncTarget(target), mode, prefer, dryRun, only)
                if (!dryRun) records.recordConflicts(target, r.conflicts, only, clock())
                SyncOutcome.Done(r.copy(errors = r.errors.map { e -> e.copy(message = e.cause?.let(Messages::connection) ?: e.message) }))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SyncOutcome.Failed(Messages.connection(e), e)
        }
        val boring = when (outcome) {
            is SyncOutcome.Done -> !outcome.result.hasChanges
            is SyncOutcome.Failed -> Messages.isUnreachable(outcome.cause)
        }
        if (log && !(quiet && boring)) {
            val r = (outcome as? SyncOutcome.Done)?.result
            records.addLog(
                LogEntry(
                    target, mode.name, prefer?.name, dryRun, started,
                    r?.uploaded?.size ?: 0, r?.downloaded?.size ?: 0, r?.conflicts?.size ?: 0,
                    r?.errors?.map { "${it.rel}: ${it.message}" }.orEmpty(),
                    (outcome as? SyncOutcome.Failed)?.message, trigger,
                ),
            )
        }
        return outcome
    }

    fun syncTarget(target: String): SyncTarget {
        val s = config().system(target)
        return SyncTarget(target, s?.savePath.orEmpty(), s?.excludes.orEmpty())
    }

    private suspend fun preflight(target: String, mode: SyncMode, dryRun: Boolean): String? {
        val c = config()
        val s = c.system(target)
        return when {
            c.serverUrl.isBlank() || c.token.isBlank() -> "サーバーが設定されていません。設定で URL とトークンを入れてください"
            s == null || s.savePath.isBlank() -> "セーブのフォルダが設定されていません"
            // 遊んでいる最中は取得しない（エミュが開いているファイルを書き換えないように）
            mode.pull && !dryRun && records.sessions().any { it.target == target } ->
                "プレイ中のため、取得しません。ゲームを終了してから同期してください"
            // セーブのフォルダの親がない（ドライブが外れている、設定が違う など）ときは止める
            !withContext(Dispatchers.IO) { File(s.savePath).absoluteFile.parentFile?.isDirectory == true } ->
                "セーブのフォルダが見つかりません: ${s.savePath}"
            else -> null
        }
    }

    /** 手元で、前回の同期から変わったファイルの数（通信しない） */
    suspend fun unsentCount(target: String): Int? = withContext(Dispatchers.IO) {
        val t = syncTarget(target)
        if (t.root.isBlank() || !File(t.root).isDirectory) return@withContext null
        runCatching {
            mutexes.of(target).lock()
            try {
                val local = JavaLocalFs(JsonHashCache(root)).scan(t)
                val base = JsonBaseStateStore(root).load(target)
                local.count { (rel, sha) -> base[rel]?.sha256 != sha }
            } finally {
                mutexes.of(target).unlock()
            }
        }.getOrNull()
    }

    companion object {
        fun defaultApi(c: AppConfig, root: File): EmusyncApi? {
            if (c.serverUrl.isBlank() || c.token.isBlank()) return null
            val certs = c.caFile?.let { CaStore(File(root, "ca")).load(it) }
            return runCatching { EmusyncApi(c.serverUrl, c.token, HttpClients.withCa(baseClient, certs)) }.getOrNull()
        }

        private val baseClient by lazy { HttpClients.base() }
    }
}
