package com.emustation.emusync.domain.resolve

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.emustation.emusync.R
import com.emustation.emusync.data.SyncOutcome
import com.emustation.emusync.data.SyncRepository
import com.emustation.emusync.data.api.HistoryApi
import com.emustation.emusync.data.db.AppDatabase
import com.emustation.emusync.data.db.PlaySessionEntity
import com.emustation.emusync.data.db.TargetEntity
import com.emustation.emusync.data.prefs.AppSettings
import com.emustation.emusync.domain.sync.Prefer
import com.emustation.emusync.domain.sync.RemoteApi
import com.emustation.emusync.domain.sync.SyncMode
import com.emustation.emusync.domain.sync.TargetMutexes
import com.emustation.emusync.testing.sha256
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 競合の解決（設計書 §6.4、§11 シナリオ 4 をファイルごとに）と、履歴と復元（§6.5、シナリオ 9）。
 * 画面（ConflictViewModel / FileHistoryViewModel）と同じ呼び出し方をする。サーバーは派生クラスで決める。
 */
abstract class ResolveScenarioContract {
    @get:Rule
    val tmp = TemporaryFolder()

    protected abstract fun remoteFor(device: String): RemoteApi
    protected abstract fun historyFor(device: String): HistoryApi
    protected open val targetName = "psp"

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val devices = mutableListOf<Device>()

    inner class Device(val name: String) {
        val db: AppDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val dataDir: File = tmp.newFolder(name, "PSP")
        val appDir = File(dataDir.parentFile, "app")
        val repo = SyncRepository(
            db = db,
            settings = { AppSettings(serverUrl = "https://ubuntu-server/emusync", token = "t") },
            apiFactory = { remoteFor(name) },
            appDir = { appDir.path },
            hasStorageAccess = { true },
            resolve = { it.resolve(context) },
            mutexes = TargetMutexes(),
        )
        val history = historyFor(name)

        init {
            devices += this
            runBlocking { db.targets().upsert(TargetEntity(targetName, "emu.pkg", dataDir.path, "SAVEDATA", emptyList())) }
        }

        private val t get() = runBlocking { db.targets().get(targetName)!! }

        fun write(rel: String, text: String) {
            val f = File(dataDir, "SAVEDATA/$rel")
            val before = if (f.exists()) f.lastModified() else 0L
            f.parentFile!!.mkdirs()
            f.writeText(text)
            f.setLastModified(maxOf(System.currentTimeMillis(), before + 2000))
        }

        fun read(rel: String) = File(dataDir, "SAVEDATA/$rel").readText()
        fun sync(mode: SyncMode = SyncMode.SYNC) = runBlocking { repo.run(t, mode) } as SyncOutcome.Done
        fun conflicts() = runBlocking { db.conflicts().getAll(targetName) }

        /** 競合の画面と同じ：手元を残す＝PUSH、サーバー版を使う＝PULL を、そのファイルだけ prefer 付きで */
        fun resolve(rels: List<String>, prefer: Prefer) = runBlocking {
            repo.run(t, if (prefer == Prefer.LOCAL) SyncMode.PUSH else SyncMode.PULL, prefer, only = rels.toSet())
        }

        /** 履歴の画面と同じ：このファイルだけを PULL */
        fun pullOne(rel: String) = runBlocking { repo.run(t, SyncMode.PULL, only = setOf(rel)) }
        fun manifest() = runBlocking { remoteFor(name).manifest("$targetName/") }
    }

    @After
    fun closeDbs() = devices.forEach { it.db.close() }

    protected val a by lazy { Device("A") }
    protected val b by lazy { Device("B") }
    private val f1 = "ULJM05800/DATA.BIN"
    private val f2 = "ULJM05801/DATA.BIN"

    /** f1・f2 の両方で、A と B が別々に変更し、B が先に送った状態。A は競合を 2 件持つ */
    private fun twoConflicts() {
        a.write(f1, "1")
        a.write(f2, "2")
        a.sync()
        b.sync()
        a.write(f1, "A1")
        a.write(f2, "A2")
        b.write(f1, "B1")
        b.write(f2, "B2")
        b.sync()
        assertEquals(2, a.sync().result.conflicts.size)
        assertEquals(listOf(f1, f2), a.conflicts().map { it.rel })
    }

    @Test
    fun resolvePerFile_keepLocalForOne_useRemoteForOther() {
        twoConflicts()
        val copies = a.conflicts().map { File(it.savedCopyPath!!) }
        assertTrue(copies.all { it.isFile })

        // f1 は手元を残す。f2 の競合はそのまま
        val r1 = a.resolve(listOf(f1), Prefer.LOCAL) as SyncOutcome.Done
        assertEquals(listOf(f1), r1.result.uploaded)
        assertEquals(listOf(f2), a.conflicts().map { it.rel })
        assertEquals(sha256("A1"), a.manifest().getValue(f1).sha256)
        assertEquals(3, a.manifest().getValue(f1).version)
        assertFalse("解決した競合のコピーは消す", copies[0].exists())
        assertTrue(copies[1].exists())

        // f2 はサーバー版を使う。手元は backups/ に退避される
        val r2 = a.resolve(listOf(f2), Prefer.REMOTE) as SyncOutcome.Done
        assertEquals(listOf(f2), r2.result.downloaded)
        assertEquals("B2", a.read(f2))
        assertEquals(emptyList<Any>(), a.conflicts())
        val backup = File(a.appDir, "backups/$targetName").walkTopDown().single { it.isFile }
        assertEquals("A2", backup.readText())
        assertFalse(copies[1].exists())

        // 落ち着いた
        val again = a.sync().result
        assertEquals(emptyList<String>(), again.uploaded + again.downloaded)
        assertTrue(again.conflicts.isEmpty())
    }

    @Test
    fun resolveAll_useRemote() {
        twoConflicts()
        val r = a.resolve(listOf(f1, f2), Prefer.REMOTE) as SyncOutcome.Done
        assertEquals(listOf(f1, f2), r.result.downloaded)
        assertEquals("B1", a.read(f1))
        assertEquals("B2", a.read(f2))
        assertEquals(emptyList<Any>(), a.conflicts())
    }

    @Test
    fun resolveOnly_doesNotTouchOtherFiles() {
        twoConflicts()
        b.write("other.bin", "new on server")
        b.sync()
        a.resolve(listOf(f1), Prefer.REMOTE)
        assertFalse(File(a.dataDir, "SAVEDATA/other.bin").exists())
        assertEquals(listOf(f2), a.conflicts().map { it.rel })
    }

    @Test
    fun useRemote_whilePlaying_isRefused_keepLocalIsAllowed() {
        twoConflicts()
        runBlocking { a.db.playSessions().upsert(PlaySessionEntity(targetName, 1, true)) }
        val r = a.resolve(listOf(f1), Prefer.REMOTE)
        assertEquals(R.string.err_playing_no_pull, (r as SyncOutcome.Failed).message.id)
        assertEquals("A1", a.read(f1))
        assertTrue(a.resolve(listOf(f1), Prefer.LOCAL) is SyncOutcome.Done)
    }

    // ---------------------------------------------------------------- シナリオ 9

    @Test
    fun scenario9_restoreVersion2_thenPull() {
        a.write(f1, "v1 content")
        a.sync()
        a.write(f1, "v2 content")
        a.sync()
        a.write(f1, "v3 broken")
        a.write(f2, "untouched")
        a.sync()
        b.sync()
        b.write(f2, "B changed f2") // 別のファイルの変更は、このファイルの取得では送らない

        val path = "$targetName/$f1"
        assertEquals(listOf(3, 2, 1), runBlocking { b.history.history(path) }.map { it.version })

        // 版 2 を restore → 新しい版ができる
        val newVersion = runBlocking { b.history.restore(path, 2) }
        assertEquals(4, newVersion)
        assertEquals(listOf(4, 3, 2, 1), runBlocking { b.history.history(path) }.map { it.version })
        assertEquals(sha256("v2 content"), b.manifest().getValue(f1).sha256)

        // PULL で手元が版 2 の内容になる
        val r = b.pullOne(f1) as SyncOutcome.Done
        assertEquals(listOf(f1), r.result.downloaded)
        assertEquals("v2 content", b.read(f1))
        assertEquals(sha256("untouched"), b.manifest().getValue(f2).sha256)

        // A も、普通の同期で版 2 の内容になる
        assertEquals(listOf(f1), a.sync().result.downloaded)
        assertEquals("v2 content", a.read(f1))
    }

    @Test
    fun restore_whenLocalAlsoChanged_becomesConflictNotOverwrite() {
        a.write(f1, "v1")
        a.sync()
        a.write(f1, "v2")
        a.sync()
        a.write(f1, "local edit")
        runBlocking { a.history.restore("$targetName/$f1", 1) }
        val r = a.pullOne(f1) as SyncOutcome.Done
        assertEquals(listOf(f1), r.result.conflicts.map { it.rel })
        assertEquals("local edit", a.read(f1))
    }

    @Test
    fun restore_missingVersion_is404() {
        a.write(f1, "v1")
        a.sync()
        try {
            runBlocking { a.history.restore("$targetName/$f1", 99) }
            throw AssertionError("expected 404")
        } catch (e: com.emustation.emusync.data.api.ApiException.Http) {
            assertEquals(404, e.code)
        }
    }
}
