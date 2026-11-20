package com.emustation.emusync.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TargetDao {
    @Query("SELECT * FROM targets ORDER BY sortOrder, name")
    fun observeAll(): Flow<List<TargetEntity>>

    @Query("SELECT * FROM targets ORDER BY sortOrder, name")
    suspend fun getAll(): List<TargetEntity>

    @Query("SELECT * FROM targets WHERE name = :name")
    suspend fun get(name: String): TargetEntity?

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM targets")
    suspend fun nextSortOrder(): Int

    @Upsert
    suspend fun upsert(target: TargetEntity)

    @Query("DELETE FROM targets WHERE name = :name")
    suspend fun delete(name: String)
}

@Dao
interface BaseStateDao {
    @Query("SELECT * FROM base_state WHERE target = :target")
    suspend fun getAll(target: String): List<BaseStateEntity>

    @Query("DELETE FROM base_state WHERE target = :target")
    suspend fun deleteTarget(target: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<BaseStateEntity>)

    @Transaction
    suspend fun replaceTarget(target: String, rows: List<BaseStateEntity>) {
        deleteTarget(target)
        insertAll(rows)
    }
}

@Dao
interface HashCacheDao {
    @Query("SELECT * FROM hash_cache WHERE target = :target")
    fun getAll(target: String): List<HashCacheEntity>

    @Query("DELETE FROM hash_cache WHERE target = :target")
    fun deleteTarget(target: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(rows: List<HashCacheEntity>)

    @Transaction
    fun replaceTarget(target: String, rows: List<HashCacheEntity>) {
        deleteTarget(target)
        insertAll(rows)
    }
}

@Dao
interface PlaySessionDao {
    @Query("SELECT * FROM play_sessions")
    fun observeAll(): Flow<List<PlaySessionEntity>>

    @Query("SELECT * FROM play_sessions WHERE target = :target")
    suspend fun get(target: String): PlaySessionEntity?

    @Upsert
    suspend fun upsert(session: PlaySessionEntity)

    @Query("DELETE FROM play_sessions WHERE target = :target")
    suspend fun delete(target: String)
}

data class TargetCount(val target: String, val count: Int)

data class TargetTime(val target: String, val time: Long)

@Dao
interface ConflictDao {
    @Query("SELECT * FROM conflicts WHERE target = :target ORDER BY rel")
    fun observe(target: String): Flow<List<ConflictEntity>>

    @Query("SELECT target, COUNT(*) AS count FROM conflicts GROUP BY target")
    fun observeCounts(): Flow<List<TargetCount>>

    @Query("SELECT * FROM conflicts WHERE target = :target ORDER BY rel")
    suspend fun getAll(target: String): List<ConflictEntity>

    @Query("DELETE FROM conflicts WHERE target = :target")
    suspend fun deleteTarget(target: String)

    @Query("DELETE FROM conflicts WHERE target = :target AND rel IN (:rels)")
    suspend fun deleteRels(target: String, rels: Collection<String>)

    /** [rels] の分だけを置き換える（ファイルごとに解決したとき） */
    @Transaction
    suspend fun replaceRels(target: String, rels: Collection<String>, rows: List<ConflictEntity>) {
        deleteRels(target, rels)
        insertAll(rows)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<ConflictEntity>)

    @Transaction
    suspend fun replaceTarget(target: String, rows: List<ConflictEntity>) {
        deleteTarget(target)
        insertAll(rows)
    }
}

@Dao
interface SyncLogDao {
    @Insert
    suspend fun insert(row: SyncLogEntity): Long

    /** 直近 [keep] 件だけを残す */
    @Query("DELETE FROM sync_log WHERE id NOT IN (SELECT id FROM sync_log ORDER BY id DESC LIMIT :keep)")
    suspend fun trim(keep: Int)

    @Transaction
    suspend fun insertAndTrim(row: SyncLogEntity, keep: Int = KEEP) {
        insert(row)
        trim(keep)
    }

    @Query("SELECT * FROM sync_log ORDER BY id DESC LIMIT :limit")
    fun observeRecent(limit: Int = KEEP): Flow<List<SyncLogEntity>>

    /** ターゲットごとの「最後に同期した日時」（dry-run と、全体が失敗したものは除く） */
    @Query(
        "SELECT target, MAX(finishedAt) AS time FROM sync_log " +
            "WHERE dryRun = 0 AND fatal IS NULL GROUP BY target",
    )
    fun observeLastSuccess(): Flow<List<TargetTime>>

    companion object {
        const val KEEP = 100
    }
}
