package com.emustation.emusync.data.db

import com.emustation.emusync.data.storage.CachedHash
import com.emustation.emusync.data.storage.HashCache
import com.emustation.emusync.domain.sync.BaseEntry
import com.emustation.emusync.domain.sync.BaseStateStore

/** 前回同期の状態を Room に保存する */
class RoomBaseStateStore(private val dao: BaseStateDao) : BaseStateStore {
    override suspend fun load(target: String): Map<String, BaseEntry> =
        dao.getAll(target).associate { it.rel to BaseEntry(it.sha256, it.version) }

    override suspend fun save(target: String, entries: Map<String, BaseEntry>) {
        dao.replaceTarget(target, entries.map { (rel, e) -> BaseStateEntity(target, rel, e.sha256, e.version) })
    }
}

/**
 * ハッシュのキャッシュを Room に保存する。
 * ファイルごとに問い合わせると遅いので、走査の始めにターゲットの分をまとめて読み、
 * 走査の終わり（[retain]）にまとめて書く。走査のたびに作り直すこと。
 * 呼び出しは IO スレッドから（Room の同期 API を使う）。
 */
class RoomHashCache(private val dao: HashCacheDao) : HashCache {
    private val loaded = mutableMapOf<String, MutableMap<String, CachedHash>>()

    private fun of(target: String) = loaded.getOrPut(target) {
        dao.getAll(target).associateTo(mutableMapOf()) { it.rel to CachedHash(it.size, it.lastModified, it.sha256) }
    }

    override fun get(target: String, rel: String): CachedHash? = of(target)[rel]

    override fun put(target: String, rel: String, value: CachedHash) {
        of(target)[rel] = value
    }

    override fun retain(target: String, rels: Set<String>) {
        val m = of(target)
        m.keys.retainAll(rels)
        dao.replaceTarget(target, m.map { (rel, c) -> HashCacheEntity(target, rel, c.size, c.lastModified, c.sha256) })
    }
}
