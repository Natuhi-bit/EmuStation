package com.emustation.emusync.desktop.data

import com.emustation.emusync.data.storage.CachedHash
import com.emustation.emusync.data.storage.HashCache
import com.emustation.emusync.domain.sync.BaseEntry
import com.emustation.emusync.domain.sync.BaseStateStore
import com.emustation.emusync.domain.sync.SyncConflict
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

private fun writeAtomic(f: File, text: String) {
    f.parentFile.mkdirs()
    val tmp = File(f.parentFile, f.name + ".tmp")
    tmp.writeText(text)
    Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
}

@Serializable
private data class StateEntry(val sha256: String, val version: Int)

/**
 * 前回同期の状態。Python 版クライアントと同じ形（state/<ターゲット>.json の {相対パス: {sha256, version}}）なので、
 * Python 版の state_dir をそのまま引き継げる
 */
class JsonBaseStateStore(private val dir: File) : BaseStateStore {
    private fun file(target: String) = File(dir, "state/$target.json")

    override suspend fun load(target: String): Map<String, BaseEntry> {
        val f = file(target)
        if (!f.isFile) return emptyMap()
        return json.decodeFromString<Map<String, StateEntry>>(f.readText()).mapValues { BaseEntry(it.value.sha256, it.value.version) }
    }

    override suspend fun save(target: String, entries: Map<String, BaseEntry>) {
        writeAtomic(file(target), json.encodeToString(entries.mapValues { StateEntry(it.value.sha256, it.value.version) }))
    }
}

@Serializable
private data class CacheEntry(val size: Long, val lastModified: Long, val sha256: String)

/** ハッシュのキャッシュ（cache/<ターゲット>.json）。走査のたびに作り直して使う */
class JsonHashCache(private val dir: File) : HashCache {
    private val loaded = mutableMapOf<String, MutableMap<String, CachedHash>>()

    private fun file(target: String) = File(dir, "cache/$target.json")

    private fun of(target: String) = loaded.getOrPut(target) {
        val f = file(target)
        if (!f.isFile) return@getOrPut mutableMapOf()
        runCatching {
            json.decodeFromString<Map<String, CacheEntry>>(f.readText())
                .mapValuesTo(mutableMapOf()) { CachedHash(it.value.size, it.value.lastModified, it.value.sha256) }
        }.getOrElse { mutableMapOf() }
    }

    override fun get(target: String, rel: String) = of(target)[rel]

    override fun put(target: String, rel: String, value: CachedHash) {
        of(target)[rel] = value
    }

    override fun retain(target: String, rels: Set<String>) {
        val m = of(target)
        m.keys.retainAll(rels)
        save(target, m)
    }

    override fun remove(target: String, rel: String) {
        val m = of(target)
        if (m.remove(rel) != null) save(target, m)
    }

    private fun save(target: String, m: Map<String, CachedHash>) =
        writeAtomic(file(target), json.encodeToString(m.mapValues { CacheEntry(it.value.size, it.value.lastModified, it.value.sha256) }))
}

/** 競合の記録 */
@Serializable
data class ConflictRecord(
    val rel: String,
    val kind: String,
    val serverVersion: Int,
    val serverSha256: String?,
    val savedCopyPath: String?,
    val detectedAt: Long,
)

/** 同期ログの1件 */
@Serializable
data class LogEntry(
    val target: String,
    val mode: String,
    val prefer: String? = null,
    val dryRun: Boolean = false,
    val startedAt: Long,
    val uploaded: Int = 0,
    val downloaded: Int = 0,
    val conflicts: Int = 0,
    val errors: List<String> = emptyList(),
    val fatal: String? = null,
    /** 自動（遊び終わり・定期送信）なら、その種類 */
    val trigger: String? = null,
)

/** 遊んでいる途中（送信が済んでいない）ゲーム。アプリが落ちても、次に起動したときに送る */
@Serializable
data class PlaySession(val target: String, val gameId: String?, val startedAt: Long, val online: Boolean)

@Serializable
private data class Records(
    val conflicts: Map<String, List<ConflictRecord>> = emptyMap(),
    val log: List<LogEntry> = emptyList(),
    val sessions: List<PlaySession> = emptyList(),
)

/** 競合・同期ログ・プレイセッションをまとめて records.json に保存する */
class RecordStore(private val file: File) {
    private var r: Records = if (file.isFile) runCatching { json.decodeFromString<Records>(file.readText()) }.getOrElse { Records() } else Records()

    @Synchronized
    fun conflicts(target: String): List<ConflictRecord> = r.conflicts[target].orEmpty()

    @Synchronized
    fun allConflicts(): Map<String, List<ConflictRecord>> = r.conflicts

    /** [only] を指定した同期では、そのファイルの分だけを置き換える。解消した競合のサーバー版のコピーは消す */
    @Synchronized
    fun recordConflicts(target: String, found: List<SyncConflict>, only: Set<String>?, now: Long) {
        val before = r.conflicts[target].orEmpty()
        val kept = if (only == null) emptyList() else before.filter { it.rel !in only }
        val added = found.map { ConflictRecord(it.rel, it.kind.name, it.serverVersion, it.serverSha256, it.savedCopyPath, now) }
        val removed = (before - kept.toSet()).mapNotNull { it.savedCopyPath } - added.mapNotNull { it.savedCopyPath }.toSet()
        removed.forEach { File(it).delete() }
        r = r.copy(conflicts = r.conflicts + (target to (kept + added)))
        save()
    }

    @Synchronized
    fun log(): List<LogEntry> = r.log

    @Synchronized
    fun addLog(e: LogEntry) {
        r = r.copy(log = (listOf(e) + r.log).take(100))
        save()
    }

    @Synchronized
    fun sessions(): List<PlaySession> = r.sessions

    @Synchronized
    fun putSession(s: PlaySession) {
        r = r.copy(sessions = r.sessions.filter { it.target != s.target } + s)
        save()
    }

    @Synchronized
    fun removeSession(target: String) {
        r = r.copy(sessions = r.sessions.filter { it.target != target })
        save()
    }

    private fun save() = writeAtomic(file, json.encodeToString(Records.serializer(), r))
}
