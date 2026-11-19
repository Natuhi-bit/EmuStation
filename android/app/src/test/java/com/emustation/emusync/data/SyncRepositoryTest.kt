package com.emustation.emusync.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.emustation.emusync.R
import com.emustation.emusync.data.api.ApiException
import com.emustation.emusync.data.db.AppDatabase
import com.emustation.emusync.data.db.TargetEntity
import com.emustation.emusync.data.prefs.AppSettings
import com.emustation.emusync.domain.sync.RemoteApi
import com.emustation.emusync.domain.sync.RemoteFile
import com.emustation.emusync.domain.sync.SyncMode
import com.emustation.emusync.domain.sync.TargetMutexes
import com.emustation.emusync.testing.FakeServer
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
class SyncRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase
    private val server = FakeServer()
    private var remote: RemoteApi = server.client("phone")
    private var settings = AppSettings(serverUrl = "https://ubuntu-server/emusync", token = "t")
    private var access = true
    private lateinit var dataDir: File
    private lateinit var repo: SyncRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        dataDir = tmp.newFolder("PSP")
        repo = SyncRepository(
            db = db,
            settings = { settings },
            apiFactory = { remote },
            appDir = { File(tmp.root, "app").path },
            hasStorageAccess = { access },
            resolve = { it.resolve(context) },
            mutexes = TargetMutexes(),
        )
    }

    @After
    fun tearDown() = db.close()

    private val target get() = TargetEntity("psp", null, dataDir.path, "SAVEDATA", listOf("*.log"))

    private fun local(rel: String, text: String) = File(dataDir, "SAVEDATA/$rel").apply {
        parentFile!!.mkdirs()
        writeText(text)
    }

    @Test
    fun sync_persistsBaseHashCacheAndLog() = runBlocking {
        local("A/DATA.BIN", "a")
        local("x.log", "excluded")
        val o = repo.run(target, SyncMode.SYNC) as SyncOutcome.Done
        assertEquals(listOf("A/DATA.BIN"), o.result.uploaded)
        assertEquals(listOf("A/DATA.BIN"), db.baseState().getAll("psp").map { it.rel })
        assertEquals(listOf("A/DATA.BIN"), db.hashCache().getAll("psp").map { it.rel })

        val log = db.syncLog().observeRecent().first().single()
        assertEquals("psp", log.target)
        assertEquals(1, log.uploaded)
        assertNull(log.fatal)
        assertFalse(log.dryRun)
    }

    @Test
    fun conflicts_areReplacedAfterEachSync_butNotByDryRun() = runBlocking {
        local("f.bin", "mine")
        server.seed("psp/f.bin", "theirs".toByteArray(), 1)
        repo.run(target, SyncMode.SYNC)
        val c = db.conflicts().observe("psp").first().single()
        assertEquals("BOTH_CHANGED", c.kind)
        assertTrue(File(c.savedCopyPath!!).isFile)

        // dry-run では競合の記録を変えない
        local("f.bin", "theirs")
        repo.run(target, SyncMode.SYNC, dryRun = true)
        assertEquals(1, db.conflicts().observe("psp").first().size)

        // 解消したら消える
        repo.run(target, SyncMode.SYNC)
        assertEquals(0, db.conflicts().observe("psp").first().size)
    }

    @Test
    fun preflight_failures() = runBlocking {
        settings = AppSettings()
        assertFailed(R.string.err_server_not_configured)

        settings = AppSettings(serverUrl = "https://x", token = "t")
        access = false
        assertFailed(R.string.err_no_storage_access)

        access = true
        // データフォルダがなければ、取得したファイルを間違った場所に作らないように止める
        server.seed("psp/f.bin", "x".toByteArray(), 1)
        val missing = target.copy(dataDir = File(tmp.root, "nope").path)
        val o = repo.run(missing, SyncMode.SYNC) as SyncOutcome.Failed
        assertEquals(R.string.err_data_dir_missing, o.message.id)
        assertFalse(File(tmp.root, "nope").exists())

        val logs = db.syncLog().observeRecent().first()
        assertEquals(3, logs.size)
        assertTrue(logs.all { it.fatal != null })
    }

    @Test
    fun offline_isLoggedAsFatal() = runBlocking {
        remote = object : RemoteApi by server.client("phone") {
            override suspend fun manifest(prefix: String): Map<String, RemoteFile> =
                throw ApiException.Offline("down")
        }
        val o = repo.run(target, SyncMode.SYNC) as SyncOutcome.Failed
        assertEquals(R.string.conn_offline, o.message.id)
        assertEquals(context.getString(R.string.conn_offline), db.syncLog().observeRecent().first().single().fatal)
    }

    @Test
    fun dryRunWithoutLog_leavesNoTrace() = runBlocking {
        local("f.bin", "x")
        val o = repo.run(target, SyncMode.SYNC, dryRun = true, log = false) as SyncOutcome.Done
        assertEquals(listOf("f.bin"), o.result.uploaded)
        assertEquals(0, db.syncLog().observeRecent().first().size)
        assertEquals(0, db.baseState().getAll("psp").size)
        assertNull(server.get("psp/f.bin"))
    }

    @Test
    fun localState_countsUnsent() = runBlocking {
        local("a.bin", "a")
        local("b.bin", "b")
        assertEquals(LocalState.Scanned(2, 2), repo.localState(target))
        repo.run(target, SyncMode.SYNC)
        assertEquals(LocalState.Scanned(0, 2), repo.localState(target))
        local("b.bin", "changed")
        File(dataDir, "SAVEDATA/b.bin").setLastModified(System.currentTimeMillis() + 5000)
        assertEquals(LocalState.Scanned(1, 2), repo.localState(target))

        assertEquals(LocalState.DataDirMissing, repo.localState(target.copy(dataDir = "/nope")))
        access = false
        assertEquals(LocalState.NoPermission, repo.localState(target))
    }

    @Test
    fun deleteTarget_removesRecordsButNotFiles() = runBlocking {
        db.targets().upsert(target)
        local("f.bin", "x")
        repo.run(target, SyncMode.SYNC)
        repo.deleteTarget("psp")
        assertNull(db.targets().get("psp"))
        assertEquals(0, db.baseState().getAll("psp").size)
        assertEquals(0, db.hashCache().getAll("psp").size)
        assertTrue(File(dataDir, "SAVEDATA/f.bin").isFile)
        assertEquals(1, server.get("psp/f.bin")!!.version)
    }

    private suspend fun assertFailed(id: Int) {
        val o = repo.run(target, SyncMode.SYNC)
        assertTrue("$o", o is SyncOutcome.Failed)
        assertEquals(id, (o as SyncOutcome.Failed).message.id)
    }
}
