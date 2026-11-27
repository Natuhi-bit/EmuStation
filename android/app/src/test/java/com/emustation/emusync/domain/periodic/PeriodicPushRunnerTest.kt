package com.emustation.emusync.domain.periodic

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.emustation.emusync.R
import com.emustation.emusync.data.SyncRepository
import com.emustation.emusync.data.db.AppDatabase
import com.emustation.emusync.data.db.PlaySessionEntity
import com.emustation.emusync.data.db.TargetEntity
import com.emustation.emusync.data.prefs.AppSettings
import com.emustation.emusync.domain.sync.RemoteApi
import com.emustation.emusync.domain.sync.TargetMutexes
import com.emustation.emusync.testing.FakeServer
import com.emustation.emusync.testing.OfflineRemote
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PeriodicPushRunnerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase
    private val server = FakeServer()
    private var remote: RemoteApi = server.client("phone")
    private var now = 100L * 3600 * 1000
    private val notices = object : PeriodicNoticeStore {
        var state = PeriodicNoticeState()
        override suspend fun loadNotices() = state
        override suspend fun saveNotices(state: PeriodicNoticeState) {
            this.state = state
        }
    }
    private lateinit var runner: PeriodicPushRunner

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val repo = SyncRepository(
            db, { AppSettings(serverUrl = "https://x", token = "t") }, { remote },
            { File(tmp.root, "app").path }, { true }, { it.resolve(context) }, mutexes = TargetMutexes(),
        )
        runner = PeriodicPushRunner(db.targets(), db.playSessions(), db.conflicts(), repo, notices, { it.resolve(context) }) { now }
    }

    @After
    fun tearDown() = db.close()

    private fun target(name: String, enabled: Boolean = true): TargetEntity {
        val t = TargetEntity(name, null, tmp.root.path + "/$name", "SAVE", emptyList(), enabled)
        File(t.dataDir).mkdirs()
        runBlocking { db.targets().upsert(t) }
        return t
    }

    private fun write(t: TargetEntity, rel: String, text: String) = File(t.dataDir, "SAVE/$rel").apply {
        parentFile!!.mkdirs()
        writeText(text)
        setLastModified(System.currentTimeMillis() + 10_000)
    }

    private fun run() = runBlocking { runner.run() }
    private fun logs() = runBlocking { db.syncLog().observeRecent().first() }

    @Test
    fun pushesOnly_skipsPlayingAndDisabled() {
        val psp = target("psp")
        val ps2 = target("ps2")
        val off = target("vita", enabled = false)
        write(psp, "a.bin", "a")
        write(ps2, "m.ps2", "m")
        write(off, "v.bin", "v")
        server.seed("psp/remote.bin", "r".toByteArray(), 1)
        runBlocking { db.playSessions().upsert(PlaySessionEntity("ps2", now, true)) }

        val r = run()
        assertEquals(mapOf("psp" to 1), r.pushed)
        assertEquals(listOf("ps2"), r.skippedPlaying)
        assertEquals(1, server.get("psp/a.bin")!!.version)
        assertNull(server.get("ps2/m.ps2")) // プレイ中は送らない
        assertNull(server.get("vita/v.bin")) // 無効なターゲットは送らない
        assertFalse(File(psp.dataDir, "SAVE/remote.bin").exists()) // 取得はしない
    }

    @Test
    fun quietLog_onlyWhenSomethingHappened() {
        val psp = target("psp")
        run()
        assertEquals(0, logs().size)
        write(psp, "a.bin", "a")
        run()
        assertEquals(1, logs().size)
        assertEquals("PUSH", logs().single().mode)
        run()
        assertEquals(1, logs().size)
    }

    @Test
    fun newConflicts_reportedOnce() {
        val psp = target("psp")
        write(psp, "a.bin", "mine")
        server.seed("psp/a.bin", "theirs".toByteArray(), 1)
        assertEquals(mapOf("psp" to 1), run().newConflicts)
        assertEquals(emptyMap<String, Int>(), run().newConflicts)
        assertEquals(1, runBlocking { db.conflicts().getAll("psp") }.size)
    }

    @Test
    fun offline_isSilent_andNotLogged() {
        val psp = target("psp")
        target("ps2")
        write(psp, "a.bin", "a")
        remote = OfflineRemote
        val r = run()
        assertTrue(r.unreachable)
        assertNull(r.error)
        assertEquals(0, logs().size)
    }

    @Test
    fun otherErrors_notifiedOncePerDistinctError() {
        val psp = target("psp")
        File(psp.dataDir).deleteRecursively() // データフォルダが見つからない
        val first = run()
        assertEquals(R.string.err_data_dir_missing, first.error?.id)
        assertNull(run().error) // 同じエラーは繰り返さない
        remote = OfflineRemote
        File(psp.dataDir).mkdirs()
        run() // つながらない回は、前回のエラーの記録を残す
        remote = server.client("phone")
        File(psp.dataDir).deleteRecursively()
        assertNull(run().error)
        File(psp.dataDir).mkdirs()
        assertNull(run().error) // 成功すると記録が消える
        File(psp.dataDir).deleteRecursively()
        assertEquals(R.string.err_data_dir_missing, run().error?.id) // また起きたら知らせる
    }

    @Test
    fun forgottenSession_remindedOnceAfter12Hours() {
        target("psp")
        runBlocking { db.playSessions().upsert(PlaySessionEntity("psp", now - 11 * 3600 * 1000L, true)) }
        assertEquals(emptyList<PlaySessionEntity>(), run().reminders)
        now += 2 * 3600 * 1000L
        assertEquals(listOf("psp"), run().reminders.map { it.target })
        assertEquals(emptyList<PlaySessionEntity>(), run().reminders)

        // セッションが終わったら記録も消え、次のセッションではまた知らせる
        runBlocking { db.playSessions().delete("psp") }
        run()
        assertEquals(emptySet<String>(), notices.state.remindedSessions)
        runBlocking { db.playSessions().upsert(PlaySessionEntity("psp", now - 13 * 3600 * 1000L, false)) }
        assertEquals(listOf("psp"), run().reminders.map { it.target })
    }
}
