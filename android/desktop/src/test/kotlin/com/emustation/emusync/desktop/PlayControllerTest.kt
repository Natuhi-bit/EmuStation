package com.emustation.emusync.desktop

import com.emustation.emusync.data.api.PlayLocks
import com.emustation.emusync.desktop.data.AppConfig
import com.emustation.emusync.desktop.data.ConfigStore
import com.emustation.emusync.desktop.data.Game
import com.emustation.emusync.desktop.data.SystemConfig
import com.emustation.emusync.desktop.domain.AppController
import com.emustation.emusync.desktop.domain.PlayPhase
import com.emustation.emusync.desktop.domain.SyncOutcome
import com.emustation.emusync.desktop.domain.SyncService
import com.emustation.emusync.domain.sync.RemoteApi
import com.emustation.emusync.domain.sync.SyncMode
import com.emustation.emusync.domain.sync.TargetMutexes
import com.emustation.emusync.testing.FakeServer
import com.emustation.emusync.testing.OfflineRemote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Steam のようなプレイの流れ：ロック → 同期 → 起動 → 終了を待つ → 送信 → ロック解除 */
class PlayControllerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val server = FakeServer()
    private var offline = false
    private var onRun: (File) -> Unit = {}
    private val launched = java.util.Collections.synchronizedList(mutableListOf<List<String>>())
    private lateinit var saves: File
    private lateinit var app: AppController
    private lateinit var game: Game

    private fun remote(device: String): RemoteApi =
        if (offline) OfflineRemote else object : RemoteApi by server.client(device), PlayLocks by server.locks(device) {}

    @Before
    fun setUp() {
        saves = tmp.newFolder("PCSX2", "memcards")
        val exe = File(tmp.root, "PCSX2/pcsx2-qt.exe").apply { writeText("") }
        game = Game(id = "p3", title = "Persona 3", target = "ps2", rom = "D:\\p3.iso")
        val store = ConfigStore(File(tmp.root, "config.json"))
        store.save(
            AppConfig(serverUrl = "https://x/emusync").withToken("t")
                .withSystem(SystemConfig("ps2", exe.path, "-batch -- \"{rom}\"", saves.path, listOf("*.bak")))
                .withGame(game),
        )
        app = AppController(
            store, tmp.newFolder("data"), CoroutineScope(Dispatchers.Default),
            syncFactory = { c, r -> SyncService(c, File(tmp.root, "data"), r, apiFactory = { remote("win") }, mutexes = TargetMutexes()) },
            // 起動の代わり：エミュが遊んでセーブを書いたことにする
            runAndWait = { cmd, started ->
                launched += cmd
                started()
                onRun(saves)
                90L
            },
            metadata = offlineMetadata(File(tmp.root, "metadata")),
        )
    }

    private fun play(force: Boolean = false) = runBlocking { app.play.play(app.config.value.games.single(), force) }

    @Test
    fun play_syncsLaunchesWaitsAndSaves() {
        server.seed("ps2/Mcd001.ps2", "from android".toByteArray(), 1)
        onRun = { dir ->
            assertEquals("from android", File(dir, "Mcd001.ps2").readText()) // 起動前に取得している
            assertEquals("win", server.lockHolder("ps2")) // 遊んでいる間はロックを持つ
            File(dir, "Mcd001.ps2").writeText("played on windows")
            File(dir, "Mcd001.ps2").setLastModified(System.currentTimeMillis() + 5_000)
        }
        play()
        val p = app.play.phase.value
        assertTrue("$p", p is PlayPhase.Finished)
        assertEquals(listOf(listOf(File(tmp.root, "PCSX2/pcsx2-qt.exe").path, "-batch", "--", "D:\\p3.iso")), launched)
        assertEquals("played on windows", String(server.get("ps2/Mcd001.ps2")!!.data))
        assertNull(server.lockHolder("ps2"))
        assertTrue(app.records.sessions().isEmpty())
        val g = app.config.value.games.single()
        assertEquals(90L, g.playSeconds)
        assertTrue(g.lastPlayedAt != null)
        assertEquals(listOf("finish", "play"), app.records.log().map { it.trigger })
    }

    @Test
    fun lockedByOtherDevice_doesNotLaunch_untilForce() {
        runBlocking { server.locks("android").lock("ps2", 3600, false) }
        play()
        val p = app.play.phase.value
        assertTrue("$p", p is PlayPhase.LockedByOther && p.device == "android")
        assertTrue(launched.isEmpty())
        app.play.dismiss()
        play(force = true)
        assertTrue(app.play.phase.value is PlayPhase.Finished)
        assertEquals(1, launched.size)
    }

    @Test
    fun offline_launchesWithoutSync_andSendsLater() {
        offline = true
        play()
        assertTrue(app.play.phase.value is PlayPhase.Unreachable)
        onRun = { File(it, "Mcd001.ps2").writeText("offline play") }
        runBlocking { app.play.playOffline(app.config.value.games.single()) }
        val p = app.play.phase.value
        assertTrue("$p", p is PlayPhase.Failed && p.message.contains("つながったときに自動で送ります"))
        assertEquals(1, app.records.sessions().size)

        // つながったら、定期送信が送り残しを送る
        offline = false
        runBlocking { app.periodicOnce() }
        assertEquals("offline play", String(server.get("ps2/Mcd001.ps2")!!.data))
        assertTrue(app.records.sessions().isEmpty())
    }

    @Test
    fun conflicts_stopLaunch_keepLock_untilAbortOrIgnore() {
        server.seed("ps2/Mcd001.ps2", "server".toByteArray(), 1)
        File(saves, "Mcd001.ps2").writeText("local, never synced")
        play()
        assertTrue(app.play.phase.value is PlayPhase.Conflicts)
        assertTrue(launched.isEmpty())
        assertEquals("win", server.lockHolder("ps2"))
        runBlocking { app.play.abort(app.config.value.games.single()) }
        assertNull(server.lockHolder("ps2"))
        assertEquals(1, app.records.conflicts("ps2").size)
    }

    @Test
    fun whilePlaying_pullIsRefused() = runBlocking {
        app.records.putSession(com.emustation.emusync.desktop.data.PlaySession("ps2", "p3", 0, true))
        val o = app.sync.run("ps2", SyncMode.SYNC)
        assertTrue(o is SyncOutcome.Failed && o.message.contains("プレイ中"))
        assertTrue(app.sync.run("ps2", SyncMode.PUSH) is SyncOutcome.Done)
    }

    @Test
    fun periodic_pushesOnly() = runBlocking {
        server.seed("ps2/Mcd002.ps2", "remote only".toByteArray(), 1)
        File(saves, "Mcd001.ps2").writeText("local")
        app.periodicOnce()
        assertEquals("local", String(server.get("ps2/Mcd001.ps2")!!.data))
        assertEquals(false, File(saves, "Mcd002.ps2").exists())
    }

    @Test
    fun rapidRepeatedPlay_launchesEmulatorOnce() {
        // Enter の長押し（キーリピート）やダブルクリックで、同時に何回もプレイを押された
        val launches = java.util.concurrent.atomic.AtomicInteger()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        onRun = { launches.incrementAndGet(); runBlocking { gate.await() } }
        val g = app.config.value.games.single()
        val jobs = (1..8).map { app.playGame(g) } + (1..4).map { app.playOffline(g) }
        Thread.sleep(1500)
        gate.complete(Unit)
        runBlocking { jobs.forEach { it.join() } }
        assertEquals("エミュは 1 回だけ起動する", 1, launches.get())
        assertEquals(1, launched.size)
    }
}
