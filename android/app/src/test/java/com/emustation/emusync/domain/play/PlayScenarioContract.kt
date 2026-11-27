package com.emustation.emusync.domain.play

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.emustation.emusync.R
import com.emustation.emusync.data.SyncOutcome
import com.emustation.emusync.data.SyncRepository
import com.emustation.emusync.data.api.PlayLocks
import com.emustation.emusync.data.db.AppDatabase
import com.emustation.emusync.data.db.TargetEntity
import com.emustation.emusync.data.prefs.AppSettings
import com.emustation.emusync.domain.sync.RemoteApi
import com.emustation.emusync.domain.sync.SyncMode
import com.emustation.emusync.domain.sync.TargetMutexes
import com.emustation.emusync.testing.OfflineRemote
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * プレイの流れ（設計書 §5）と、§11 のシナリオ 7（ロック）・8（オフライン）。
 * 端末ごとに Room・同期・PlaySessionManager を持つ。サーバーは派生クラスで決める。
 */
abstract class PlayScenarioContract {
    @get:Rule
    val tmp = TemporaryFolder()

    protected abstract fun remoteFor(device: String): RemoteApi
    protected abstract fun locksFor(device: String): PlayLocks

    /** サーバーに登録されている端末名 */
    protected abstract fun deviceName(device: String): String

    /** 今ロックを持っている端末名（なければ null） */
    protected abstract fun lockHolder(target: String): String?

    protected open val targetName = "psp"

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val devices = mutableListOf<Device>()

    inner class Device(val name: String) {
        val db: AppDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val dataDir: File = tmp.newFolder(name, "PSP")
        var down = false
        private var clock = 1_000L
        val repo = SyncRepository(
            db = db,
            settings = { AppSettings(serverUrl = "https://ubuntu-server/emusync", token = "t") },
            apiFactory = { if (down) OfflineRemote else remoteFor(name) },
            appDir = { File(dataDir.parentFile, "app").path },
            hasStorageAccess = { true },
            resolve = { it.resolve(context) },
            mutexes = TargetMutexes(),
        )
        val manager = PlaySessionManager(
            db.targets(), db.playSessions(), repo,
            locks = { if (down) OfflineRemote else locksFor(name) },
            clock = { clock++ },
        )

        init {
            devices += this
            runBlocking { db.targets().upsert(TargetEntity(targetName, "emu.pkg", dataDir.path, "SAVEDATA", emptyList())) }
        }

        fun write(rel: String, text: String) {
            val f = File(dataDir, "SAVEDATA/$rel")
            val before = if (f.exists()) f.lastModified() else 0L
            f.parentFile!!.mkdirs()
            f.writeText(text)
            f.setLastModified(maxOf(System.currentTimeMillis(), before + 2000))
        }

        fun read(rel: String) = File(dataDir, "SAVEDATA/$rel").readText()
        fun start(force: Boolean = false) = runBlocking { manager.start(targetName, force) }
        fun finish() = runBlocking { manager.finish(targetName) }
        fun session() = runBlocking { manager.session(targetName) }
        fun sync(mode: SyncMode = SyncMode.SYNC) = runBlocking {
            repo.run(db.targets().get(targetName)!!, mode)
        }
    }

    @After
    fun closeDbs() = devices.forEach { it.db.close() }

    protected val a by lazy { Device("A") }
    protected val b by lazy { Device("B") }
    private val data = "ULJM05800/DATA.BIN"

    // ---------------------------------------------------------------- 普段の流れ

    @Test
    fun play_locksSyncsAndRecordsSession_thenFinishPushesAndReleases() {
        b.write(data, "from B")
        b.sync()

        val steps = mutableListOf<PlayStep>()
        val r = runBlocking { a.manager.start(targetName) { steps += it } }
        assertTrue("$r", r is StartResult.Ready)
        assertEquals(listOf(PlayStep.LOCKING, PlayStep.SYNCING), steps)
        // 3. 取得して手元に来ている
        assertEquals(listOf(data), (r as StartResult.Ready).sync!!.downloaded)
        assertEquals("from B", a.read(data))
        // 2. ロックを持っている。5. プレイセッションがある
        assertEquals(deviceName("A"), lockHolder(targetName))
        assertEquals(true, a.session()!!.online)

        // 遊ぶ
        a.write(data, "played on A")
        val f = a.finish()
        assertTrue("$f", f is FinishResult.Done)
        assertEquals(listOf(data), (f as FinishResult.Done).result.uploaded)
        assertNull(lockHolder(targetName))
        assertNull(a.session())

        // B は取得できる
        assertEquals(listOf(data), b.sync().let { (it as SyncOutcome.Done).result.downloaded })
        assertEquals("played on A", b.read(data))
    }

    // ---------------------------------------------------------------- シナリオ 7

    @Test
    fun scenario7_lockedByOtherDevice_andForce() {
        assertTrue(a.start() is StartResult.Ready)

        val r = b.start()
        assertTrue("$r", r is StartResult.LockedByOther)
        assertEquals(deviceName("A"), (r as StartResult.LockedByOther).device)
        assertNull(b.session()) // 起動しない
        assertEquals(deviceName("A"), lockHolder(targetName))

        // 奪って起動
        assertTrue(b.start(force = true) is StartResult.Ready)
        assertEquals(deviceName("B"), lockHolder(targetName))

        // A が送信して終了しても、B のロックは消さない
        assertTrue(a.finish() is FinishResult.Done)
        assertEquals(deviceName("B"), lockHolder(targetName))
        assertTrue(b.finish() is FinishResult.Done)
        assertNull(lockHolder(targetName))
    }

    // ---------------------------------------------------------------- シナリオ 8

    @Test
    fun scenario8_offline_launchWithoutSync_thenPushAfterRecovery() {
        a.write(data, "v1")
        a.sync()
        b.sync() // B も前に同期したことがある

        b.down = true
        val r = b.start()
        assertTrue("$r", r is StartResult.Unreachable)
        assertEquals(R.string.conn_offline, (r as StartResult.Unreachable).message.id)

        // 同期せずに起動 → オフラインのプレイセッション
        runBlocking { b.manager.startOffline(targetName) }
        assertEquals(false, b.session()!!.online)
        b.write(data, "offline play")

        // つながらないうちは送信できない。プレイセッションは残る
        val f1 = b.finish()
        assertTrue("$f1", f1 is FinishResult.Failed && f1.unreachable)
        assertNotNull(b.session())

        // 復帰後に送信される（オフラインだったので、まずロックを試みる。そのうえで PUSH）
        b.down = false
        val f2 = b.finish()
        assertTrue("$f2", f2 is FinishResult.Done)
        assertEquals(listOf(data), (f2 as FinishResult.Done).result.uploaded)
        assertNull(b.session())
        assertNull(lockHolder(targetName))

        assertEquals(listOf(data), (a.sync() as SyncOutcome.Done).result.downloaded)
        assertEquals("offline play", a.read(data))
    }

    @Test
    fun offlineSession_pushOnly_reportsConflictInsteadOfPulling() {
        a.write(data, "v1")
        a.sync()
        b.sync()

        b.down = true
        runBlocking { b.manager.startOffline(targetName) }
        b.write(data, "B offline")
        // その間に A が送信
        a.write(data, "A online")
        a.sync()

        b.down = false
        val f = b.finish() as FinishResult.Done
        assertEquals(listOf(data), f.result.conflicts.map { it.rel })
        assertEquals("B offline", b.read(data)) // 取得（上書き）はしない
        assertNull(b.session())
    }

    // ---------------------------------------------------------------- 競合・前回のプレイ

    @Test
    fun conflictsAtStart_keepLock_untilAbortOrIgnore() {
        a.write(data, "v1")
        a.sync()
        b.sync()
        a.write(data, "A edit")
        a.sync()
        b.write(data, "B edit")

        val r = b.start()
        assertTrue("$r", r is StartResult.Conflicts)
        assertEquals(listOf(data), (r as StartResult.Conflicts).result.conflicts.map { it.rel })
        assertNull(b.session())
        assertEquals(deviceName("B"), lockHolder(targetName))

        runBlocking { b.manager.abort(targetName) }
        assertNull(lockHolder(targetName))

        assertTrue(b.start() is StartResult.Conflicts)
        runBlocking { b.manager.startIgnoringConflicts(targetName) }
        assertNotNull(b.session())
        assertEquals("B edit", b.read(data))
    }

    @Test
    fun previousSession_isFinishedBeforeNewPlay() {
        assertTrue(a.start() is StartResult.Ready)
        a.write(data, "first play")
        // 送信せずに、もう一度「遊ぶ」
        val steps = mutableListOf<PlayStep>()
        val r = runBlocking { a.manager.start(targetName) { steps += it } }
        assertTrue("$r", r is StartResult.Ready)
        assertEquals(listOf(PlayStep.FINISHING_PREVIOUS, PlayStep.LOCKING, PlayStep.SYNCING), steps)
        assertEquals(listOf(data), b.sync().let { (it as SyncOutcome.Done).result.downloaded })
        assertEquals("first play", b.read(data))
    }

    @Test
    fun previousOfflineSession_whileStillOffline_offersOfflineLaunchAgain() {
        a.down = true
        runBlocking { a.manager.startOffline(targetName) }
        val startedAt = a.session()!!.startedAt
        val r = a.start()
        assertTrue("$r", r is StartResult.Unreachable)
        runBlocking { a.manager.startOffline(targetName) }
        assertEquals(startedAt, a.session()!!.startedAt) // まだ送っていないので、開始時刻を引き継ぐ
    }

    @Test
    fun whilePlaying_pullIsRefused_pushIsAllowed() {
        assertTrue(a.start() is StartResult.Ready)
        val pull = a.sync(SyncMode.SYNC)
        assertTrue("$pull", pull is SyncOutcome.Failed)
        assertEquals(R.string.err_playing_no_pull, (pull as SyncOutcome.Failed).message.id)
        assertTrue(a.sync(SyncMode.PULL) is SyncOutcome.Failed)
        a.write(data, "x")
        assertEquals(listOf(data), (a.sync(SyncMode.PUSH) as SyncOutcome.Done).result.uploaded)
    }

    @Test
    fun finishWithoutSession_doesNothing() {
        assertEquals(FinishResult.NoSession, a.finish())
    }

    // ---------------------------------------------------------------- 自動送信のあとでエミュに戻った

    @Test
    fun resumeAfterAutoFinish_relocksWithoutPull() {
        assertTrue(a.start() is StartResult.Ready)
        a.write(data, "played")
        assertTrue(a.finish() is FinishResult.Done) // 自動で送った
        assertNull(lockHolder(targetName))

        // 送ったあとで、ほかの端末がサーバーを更新していても、取得はしない（エミュが開いているので）
        b.sync()
        b.write(data, "B edit")
        b.sync()

        val r = runBlocking { a.manager.resumeAfterAutoFinish(targetName) }
        assertNull(r.lockedBy)
        assertEquals(true, r.session.online)
        assertEquals(deviceName("A"), lockHolder(targetName))
        assertEquals("played", a.read(data))
        assertNotNull(a.session())
    }

    @Test
    fun resumeAfterAutoFinish_whenOtherDeviceLocked_continuesOffline() {
        assertTrue(a.start() is StartResult.Ready)
        assertTrue(a.finish() is FinishResult.Done)
        assertTrue(b.start() is StartResult.Ready)

        val r = runBlocking { a.manager.resumeAfterAutoFinish(targetName) }
        assertEquals(deviceName("B"), r.lockedBy)
        assertEquals(false, r.session.online)
        assertEquals(deviceName("B"), lockHolder(targetName)) // 奪わない
    }
}
