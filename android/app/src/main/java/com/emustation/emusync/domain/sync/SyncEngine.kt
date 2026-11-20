package com.emustation.emusync.domain.sync

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/** 同じターゲットの同期が同時に走らないようにする（設計書 §8.4）。プロセスで1つ */
class TargetMutexes {
    private val map = ConcurrentHashMap<String, Mutex>()

    fun of(target: String): Mutex = map.getOrPut(target) { Mutex() }

    companion object {
        val process = TargetMutexes()
    }
}

/**
 * 同期エンジン（設計書 §4）。Python 版クライアント（client/emusync.py）の `sync_target` と同じ動作にする。
 *
 * Android の API には依存しない。ファイル操作は [LocalFs]、通信は [RemoteApi]、
 * 前回同期の状態は [BaseStateStore] を通して行う。
 *
 * @param appDir アプリ専用外部領域（`getExternalFilesDir(null)`）。conflicts/ と backups/ を置く
 */
class SyncEngine(
    private val remote: RemoteApi,
    private val fs: LocalFs,
    private val baseStore: BaseStateStore,
    private val appDir: String,
    private val now: () -> LocalDateTime = LocalDateTime::now,
    private val mutexes: TargetMutexes = TargetMutexes.process,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * 1つのターゲットを同期する。
     *
     * 走査と manifest の取得に失敗したときは例外を投げる（Python 版と同じ）。
     * ファイルごとの失敗は [SyncResult.errors] に入れて、ほかのファイルの処理を続ける。
     *
     * @param only 指定すると、その相対パスだけを処理する（競合の画面でファイルごとに解決するため。
     *   Android 版で追加）。1ファイルごとの判定は変わらない。ほかのファイルの base はそのまま残す
     */
    suspend fun sync(
        target: SyncTarget,
        mode: SyncMode = SyncMode.SYNC,
        prefer: Prefer? = null,
        dryRun: Boolean = false,
        only: Set<String>? = null,
    ): SyncResult = mutexes.of(target.name).withLock {
        withContext(io) { Run(target, mode, prefer, dryRun, only).execute() }
    }

    private inner class Run(
        val t: SyncTarget,
        val mode: SyncMode,
        val prefer: Prefer?,
        val dryRun: Boolean,
        val only: Set<String>?,
    ) {
        val prefix = t.name + "/"
        val stamp: String = now().format(STAMP)
        val base = mutableMapOf<String, BaseEntry>()

        val uploaded = mutableListOf<String>()
        val downloaded = mutableListOf<String>()
        val conflicts = mutableListOf<SyncConflict>()
        val errors = mutableListOf<SyncError>()

        fun localPath(rel: String) = joinPath(t.root, rel)

        suspend fun execute(): SyncResult {
            val local = fs.scan(t)
            val remoteFiles = remote.manifest(prefix)
            base += baseStore.load(t.name)

            val rels = (local.keys + remoteFiles.keys).filter { only == null || it in only }
            for (rel in rels.sortedWith(CODE_POINT_ORDER)) {
                try {
                    decide(rel, local[rel], remoteFiles[rel])
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 1ファイルの失敗で全体を止めない
                    errors += SyncError(rel, e.message ?: e.javaClass.simpleName, e)
                }
            }

            if (!dryRun) baseStore.save(t.name, base.toMap())
            return SyncResult(
                t.name, mode, prefer, dryRun,
                uploaded.toList(), downloaded.toList(), conflicts.toList(), errors.toList(),
            )
        }

        /** 設計書 §4.2 の判定 */
        suspend fun decide(rel: String, l: String?, r: RemoteFile?) {
            val b = base[rel]
            when {
                l != null && r != null && l == r.sha256 ->
                    base[rel] = BaseEntry(r.sha256, r.version) // 一致。記録だけ更新する
                r == null -> if (mode.push) upload(rel, 0) // サーバーにない
                l == null -> if (mode.pull) download(rel) // 手元にない（削除は伝播しない）
                else -> {
                    val localChanged = l != b?.sha256
                    val remoteChanged = b == null || r.sha256 != b.sha256
                    if (localChanged && !remoteChanged) {
                        if (mode.push) upload(rel, r.version)
                    } else if (remoteChanged && !localChanged) {
                        if (mode.pull) download(rel)
                    } else {
                        conflict(rel, r) // 両方変化、または初回で内容が違う
                    }
                }
            }
        }

        suspend fun download(rel: String) {
            if (dryRun) {
                downloaded += rel
                return
            }
            val d = writeAtomic(localPath(rel)) { out -> remote.download(prefix + rel, out) }
            base[rel] = BaseEntry(d.sha256, d.version)
            downloaded += rel
        }

        suspend fun upload(rel: String, baseVersion: Int) {
            if (dryRun) {
                uploaded += rel
                return
            }
            val content = fs.prepareUpload(localPath(rel))
            when (val o = remote.upload(prefix + rel, content, baseVersion)) {
                // 送信中に他の端末が更新した。base は更新しない
                is UploadOutcome.Rejected -> conflicts += SyncConflict(
                    rel, SyncConflict.Kind.UPLOAD_REJECTED, o.serverVersion, o.serverSha256, null,
                )
                is UploadOutcome.Ok -> {
                    base[rel] = BaseEntry(o.sha256, o.version)
                    uploaded += rel
                }
            }
        }

        /** 設計書 §4.4 */
        suspend fun conflict(rel: String, r: RemoteFile) {
            if (prefer == Prefer.LOCAL && mode.push) {
                upload(rel, r.version) // サーバーの旧版は履歴に残るので失われない
            } else if (prefer == Prefer.REMOTE && mode.pull) {
                if (!dryRun) { // 手元の版は backups/ に退避する
                    fs.copy(localPath(rel), joinPath(appDir, "backups/${t.name}/$stamp/$rel"))
                }
                download(rel)
            } else {
                if (dryRun) {
                    conflicts += SyncConflict(rel, SyncConflict.Kind.BOTH_CHANGED, r.version, r.sha256, null)
                    return
                }
                // 何も上書きしない。サーバー版を conflicts/ に保存する。
                // 名前に入れる版番号は受信してみるまで決まらないので、いったん隣の一時ファイルに受ける
                val staging = joinPath(appDir, "conflicts/${t.name}/$rel.server-incoming")
                val d = writeAtomic(staging) { out -> remote.download(prefix + rel, out) }
                val dst = joinPath(appDir, "conflicts/${t.name}/$rel.server-v${d.version}")
                fs.move(staging, dst)
                conflicts += SyncConflict(rel, SyncConflict.Kind.BOTH_CHANGED, d.version, d.sha256, dst)
            }
        }

        /** 書き込みに失敗したら一時ファイルを消し、本来のファイルには触れない */
        suspend fun <T> writeAtomic(path: String, block: suspend (OutputStream) -> T): T {
            val w = fs.beginAtomicWrite(path)
            try {
                val v = block(w.output)
                w.commit()
                return v
            } finally {
                w.abort()
            }
        }
    }

    companion object {
        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

        /** Python の文字列比較（コードポイント順）と同じ並び */
        val CODE_POINT_ORDER = Comparator<String> { a, b ->
            val ia = a.codePoints().iterator()
            val ib = b.codePoints().iterator()
            while (ia.hasNext() && ib.hasNext()) {
                val c = ia.nextInt().compareTo(ib.nextInt())
                if (c != 0) return@Comparator c
            }
            ia.hasNext().compareTo(ib.hasNext())
        }

        fun joinPath(root: String, rel: String): String = root.trimEnd('/') + "/" + rel
    }
}
