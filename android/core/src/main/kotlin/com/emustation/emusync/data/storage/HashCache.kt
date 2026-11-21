package com.emustation.emusync.data.storage

import java.util.concurrent.ConcurrentHashMap

/**
 * ハッシュのキャッシュ（設計書 §4.6）。`(target, rel, size, lastModified) → sha256`。
 * ハッシュ計算の省略にだけ使い、同期の判定には使わない。
 * 実装は段階 3 で Room（`hash_cache` テーブル）にする。
 */
interface HashCache {
    fun get(target: String, rel: String): CachedHash?

    fun put(target: String, rel: String, value: CachedHash)

    /** 走査で見つからなかったパスの記録を消す */
    fun retain(target: String, rels: Set<String>)

    companion object {
        /** キャッシュしない */
        val NONE = object : HashCache {
            override fun get(target: String, rel: String): CachedHash? = null
            override fun put(target: String, rel: String, value: CachedHash) = Unit
            override fun retain(target: String, rels: Set<String>) = Unit
        }
    }
}

data class CachedHash(val size: Long, val lastModified: Long, val sha256: String)

class InMemoryHashCache : HashCache {
    private val map = ConcurrentHashMap<Pair<String, String>, CachedHash>()

    override fun get(target: String, rel: String) = map[target to rel]

    override fun put(target: String, rel: String, value: CachedHash) {
        map[target to rel] = value
    }

    override fun retain(target: String, rels: Set<String>) {
        map.keys.removeIf { it.first == target && it.second !in rels }
    }
}
