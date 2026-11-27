package com.emustation.emusync.testing

import com.emustation.emusync.data.api.ApiException
import com.emustation.emusync.data.api.HistoryApi
import com.emustation.emusync.data.api.HistoryVersion
import com.emustation.emusync.data.api.LockInfo
import com.emustation.emusync.data.api.PlayLocks
import com.emustation.emusync.data.storage.toHex
import com.emustation.emusync.domain.sync.BaseEntry
import com.emustation.emusync.domain.sync.BaseStateStore
import com.emustation.emusync.domain.sync.Downloaded
import com.emustation.emusync.domain.sync.RemoteApi
import com.emustation.emusync.domain.sync.RemoteFile
import com.emustation.emusync.domain.sync.UploadContent
import com.emustation.emusync.domain.sync.UploadOutcome
import java.io.OutputStream
import java.security.MessageDigest

fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).toHex()
fun sha256(s: String): String = sha256(s.toByteArray())

/**
 * server/app.py と同じ規則で動く、メモリ上の偽サーバー。
 * 端末ごとに [client] を作る（トークンの代わりに端末名を渡す）。
 */
class FakeServer {
    class Entry(val data: ByteArray, val version: Int, val device: String, val updatedAt: Double) {
        val sha256 = sha256(data)
    }

    private val files = sortedMapOf<String, Entry>()

    /** 版の履歴（server/app.py の versions テーブル） */
    private val versions = mutableMapOf<String, MutableList<Entry>>()
    private var clock = 1_763_560_000.0

    /** 送信の記録（path, If-Match） */
    val uploadLog = mutableListOf<Pair<String, Int>>()

    /** ここに入れたパスは、取得したときに本文が壊れて届く */
    val corruptOnDownload = mutableSetOf<String>()

    /** manifest を返したあとに呼ぶ（他の端末が割り込んだ状態を作る） */
    var afterManifest: (suspend () -> Unit)? = null

    @Synchronized
    fun get(path: String): Entry? = files[path]

    @Synchronized
    fun seed(path: String, data: ByteArray, version: Int, device: String = "seed") {
        val e = Entry(data, version, device, clock++)
        files[path] = e
        versions.getOrPut(path) { mutableListOf() } += e
    }

    fun history(device: String): HistoryApi = object : HistoryApi {
        override suspend fun history(path: String): List<HistoryVersion> = synchronized(this@FakeServer) {
            versions[path].orEmpty().sortedByDescending { it.version }
                .map { HistoryVersion(it.version, it.sha256, it.data.size.toLong(), it.updatedAt, it.device) }
        }

        override suspend fun restore(path: String, version: Int): Int = synchronized(this@FakeServer) {
            val old = versions[path]?.firstOrNull { it.version == version }
            val cur = files[path]
            if (old == null || cur == null) throw ApiException.Http(404, "その版はありません")
            val e = Entry(old.data, cur.version + 1, "$device(restore v$version)", clock++)
            files[path] = e
            versions.getValue(path) += e
            e.version
        }
    }

    // ---------------------------------------------------------------- ロック（server/app.py と同じ規則）
    private val lockTable = mutableMapOf<String, Pair<String, Double>>()
    var now: () -> Double = { System.currentTimeMillis() / 1000.0 }

    /** ロックの操作の記録（device, 操作） */
    val lockLog = mutableListOf<String>()

    @Synchronized
    fun lockHolder(target: String): String? = lockTable[target]?.takeIf { it.second > now() }?.first

    fun locks(device: String): PlayLocks = object : PlayLocks {
        override suspend fun lock(target: String, ttlSeconds: Int, force: Boolean): LockInfo =
            synchronized(this@FakeServer) {
                lockLog += "$device lock $target ttl=$ttlSeconds force=$force"
                val cur = lockTable[target]
                if (cur != null && cur.first != device && cur.second > now() && !force) {
                    throw ApiException.Locked423("${cur.first} がプレイ中です", cur.first, cur.second)
                }
                val exp = now() + ttlSeconds
                lockTable[target] = device to exp
                LockInfo(target, device, exp)
            }

        override suspend fun unlock(target: String) = synchronized(this@FakeServer) {
            lockLog += "$device unlock $target"
            if (lockTable[target]?.first == device) lockTable.remove(target)
            Unit
        }
    }

    fun client(device: String): RemoteApi = object : RemoteApi {
        override suspend fun manifest(prefix: String): Map<String, RemoteFile> {
            val m = synchronized(this@FakeServer) {
                files.filterKeys { it.startsWith(prefix) }.map { (p, e) ->
                    val rel = p.removePrefix(prefix)
                    rel to RemoteFile(rel, e.version, e.sha256, e.data.size.toLong(), e.updatedAt, e.device)
                }.toMap()
            }
            afterManifest?.invoke()
            return m
        }

        override suspend fun download(path: String, out: OutputStream, version: Int?): Downloaded {
            val e = get(path) ?: throw ApiException.Http(404, "ありません")
            if (path in corruptOnDownload) {
                out.write(e.data + 0x00)
                throw ApiException.Protocol("ダウンロード内容が壊れています: $path")
            }
            out.write(e.data)
            return Downloaded(e.version, e.sha256)
        }

        override suspend fun upload(path: String, content: UploadContent, baseVersion: Int): UploadOutcome {
            val data = content.open().use { it.readBytes() }
            val sha = sha256(data)
            if (sha != content.sha256) throw ApiException.Http(400, "ハッシュ不一致")
            synchronized(this@FakeServer) {
                uploadLog += path to baseVersion
                val cur = files[path]
                val current = cur?.version ?: 0
                if (current != baseVersion) return UploadOutcome.Rejected(current, cur?.sha256)
                if (cur != null && cur.sha256 == sha) return UploadOutcome.Ok(current, sha, unchanged = true)
                val e = Entry(data, current + 1, device, clock++)
                files[path] = e
                versions.getOrPut(path) { mutableListOf() } += e
                return UploadOutcome.Ok(current + 1, sha, unchanged = false)
            }
        }
    }
}

/** サーバーにつながらない状態の偽物 */
object OfflineRemote : RemoteApi, PlayLocks {
    private fun down(): Nothing = throw ApiException.Offline("connect failed", java.net.ConnectException("refused"))
    override suspend fun manifest(prefix: String): Map<String, RemoteFile> = down()
    override suspend fun download(path: String, out: OutputStream, version: Int?): Downloaded = down()
    override suspend fun upload(path: String, content: UploadContent, baseVersion: Int): UploadOutcome = down()
    override suspend fun lock(target: String, ttlSeconds: Int, force: Boolean): LockInfo = down()
    override suspend fun unlock(target: String): Unit = down()
}

class InMemoryBaseStateStore : BaseStateStore {
    val data = mutableMapOf<String, Map<String, BaseEntry>>()
    var saveCount = 0

    override suspend fun load(target: String): Map<String, BaseEntry> = data[target].orEmpty()

    override suspend fun save(target: String, entries: Map<String, BaseEntry>) {
        saveCount++
        data[target] = entries
    }
}
