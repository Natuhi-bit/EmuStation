package com.emustation.emusync.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.emustation.emusync.data.storage.CachedHash
import com.emustation.emusync.domain.sync.BaseEntry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun targets_roundTripWithExcludes() = runBlocking {
        db.targets().upsert(TargetEntity("ps2", "x.armsx2", "/storage/emulated/0/ARMSX2", "memcards", listOf("*.bak", "a\"b"), true, 1))
        db.targets().upsert(TargetEntity("psp", null, "/d", "SAVEDATA", emptyList(), false, 0))
        assertEquals(listOf("psp", "ps2"), db.targets().getAll().map { it.name })
        assertEquals(listOf("*.bak", "a\"b"), db.targets().get("ps2")!!.excludes)
        assertEquals(2, db.targets().nextSortOrder())
    }

    @Test
    fun baseState_replacesWholeTarget() = runBlocking {
        val store = RoomBaseStateStore(db.baseState())
        store.save("psp", mapOf("a" to BaseEntry("s1", 1), "b" to BaseEntry("s2", 2)))
        store.save("ps2", mapOf("m" to BaseEntry("s3", 3)))
        store.save("psp", mapOf("b" to BaseEntry("s4", 4)))
        assertEquals(mapOf("b" to BaseEntry("s4", 4)), store.load("psp"))
        assertEquals(mapOf("m" to BaseEntry("s3", 3)), store.load("ps2"))
    }

    @Test
    fun hashCache_loadsOnce_andWritesOnRetain() {
        RoomHashCache(db.hashCache()).apply {
            put("psp", "a", CachedHash(1, 10, "x"))
            put("psp", "b", CachedHash(2, 20, "y"))
            retain("psp", setOf("a", "b"))
        }
        val c = RoomHashCache(db.hashCache())
        assertEquals(CachedHash(2, 20, "y"), c.get("psp", "b"))
        c.retain("psp", setOf("a"))
        assertEquals(listOf("a"), db.hashCache().getAll("psp").map { it.rel })
    }

    @Test
    fun syncLog_keepsLatest100_andLastSuccessIgnoresDryRunAndFailures() = runBlocking {
        fun row(i: Int, target: String = "psp", dry: Boolean = false, fatal: String? = null) =
            SyncLogEntity(0, target, "SYNC", null, dry, i.toLong(), i.toLong(), 0, 0, 0, emptyList(), fatal)
        for (i in 1..105) db.syncLog().insertAndTrim(row(i))
        db.syncLog().insertAndTrim(row(200, dry = true))
        db.syncLog().insertAndTrim(row(300, fatal = "offline"))
        db.syncLog().insertAndTrim(row(50, target = "ps2"))

        val recent = db.syncLog().observeRecent().first()
        assertEquals(100, recent.size)
        assertEquals(50L, recent.first().startedAt)
        assertEquals(
            mapOf("psp" to 105L, "ps2" to 50L),
            db.syncLog().observeLastSuccess().first().associate { it.target to it.time },
        )
    }

    @Test
    fun conflicts_countsPerTarget() = runBlocking {
        db.conflicts().replaceTarget("psp", listOf(ConflictEntity("psp", "a", "BOTH_CHANGED", 2, "s", "/p", 1)))
        db.conflicts().replaceTarget("ps2", listOf(
            ConflictEntity("ps2", "a", "BOTH_CHANGED", 2, "s", "/p", 1),
            ConflictEntity("ps2", "b", "UPLOAD_REJECTED", 3, null, null, 1),
        ))
        db.conflicts().replaceTarget("psp", emptyList())
        assertEquals(mapOf("ps2" to 2), db.conflicts().observeCounts().first().associate { it.target to it.count })
    }
}
