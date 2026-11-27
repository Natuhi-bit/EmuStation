package com.emustation.emusync.domain.sync

import com.emustation.emusync.data.storage.InMemoryHashCache
import com.emustation.emusync.data.storage.JavaLocalFs
import com.emustation.emusync.testing.InMemoryBaseStateStore
import com.emustation.emusync.testing.sha256
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDateTime

/**
 * 設計書 §11 のテストシナリオ 1〜6。
 * 端末 A・B（トークンは別々）、ターゲット psp。
 *
 * サーバーは派生クラスで決める（メモリ上の偽サーバー、または本物の server/app.py）。
 */
abstract class SyncScenarioContract {
    @get:Rule
    val tmp = TemporaryFolder()

    /** 端末ごとのサーバーへの接続 */
    protected abstract fun remoteFor(device: String): RemoteApi

    /** サーバーの現在の状態を見る（どちらの端末の接続で見てもよい） */
    private fun serverFile(rel: String): RemoteFile? =
        runBlocking { a.remote.manifest("$targetName/")[rel] }

    protected open val targetName = "psp"

    private val clock = LocalDateTime.of(2026, 11, 19, 12, 0, 0)

    inner class Device(val name: String, val excludes: List<String> = emptyList()) {
        val dir: File = tmp.newFolder(name)
        val root = File(dir, "PSP/SAVEDATA")
        val appDir = File(dir, "app")
        val baseStore = InMemoryBaseStateStore()
        val remote = remoteFor(name)
        private val fs = JavaLocalFs(InMemoryHashCache())

        /** プロセスが別なので、Mutex も端末ごとに別 */
        private val mutexes = TargetMutexes()

        val target get() = SyncTarget(targetName, root.path, excludes)

        fun sync(
            mode: SyncMode = SyncMode.SYNC,
            prefer: Prefer? = null,
            dryRun: Boolean = false,
            via: RemoteApi = remote,
        ): SyncResult = runBlocking {
            SyncEngine(via, fs, baseStore, appDir.path, now = { clock }, mutexes = mutexes)
                .sync(target, mode, prefer, dryRun)
        }

        fun file(rel: String) = File(root, rel)

        /** 書き込む。更新日時も必ず変える（ハッシュのキャッシュが古い値を返さないように） */
        fun write(rel: String, text: String) {
            val f = file(rel)
            val before = if (f.exists()) f.lastModified() else 0L
            f.parentFile!!.mkdirs()
            f.writeText(text)
            f.setLastModified(maxOf(System.currentTimeMillis(), before + 2000))
        }

        fun read(rel: String): String = file(rel).readText()

        fun base(rel: String): BaseEntry? = baseStore.data[targetName]?.get(rel)
    }

    protected val a by lazy { Device("A") }
    protected val b by lazy { Device("B") }

    private val data = "ULJM05800/DATA.BIN"
    private val icon = "ULJM05800/ICON0.PNG"

    private fun SyncResult.assertNothing() {
        assertEquals(emptyList<String>(), uploaded)
        assertEquals(emptyList<String>(), downloaded)
        assertEquals(emptyList<SyncConflict>(), conflicts)
        assertEquals(emptyList<SyncError>(), errors)
    }

    /** シナリオ 1 の結果の状態にする：A・B ともに DATA.BIN（"A1"）と ICON0.PNG を版 1 で持つ */
    private fun initialState() {
        a.write(data, "A1")
        a.write(icon, "icon")
        a.sync()
        b.sync()
    }

    // ---------------------------------------------------------------- 1
    @Test
    fun scenario1_initialPush() {
        a.write(data, "A1")
        a.write(icon, "icon")

        val ra = a.sync()
        assertEquals(listOf(data, icon), ra.uploaded)
        assertTrue(ra.isClean)
        assertEquals(1, serverFile(data)!!.version)

        val rb = b.sync()
        assertEquals(listOf(data, icon), rb.downloaded)
        assertTrue(rb.isClean)
        assertEquals("A1", b.read(data))
        assertEquals("icon", b.read(icon))
        assertEquals(BaseEntry(sha256("A1"), 1), b.base(data))

        // 2回目は何も起きない
        a.sync().assertNothing()
        b.sync().assertNothing()
    }

    // ---------------------------------------------------------------- 2
    @Test
    fun scenario2_oneSideChange() {
        initialState()
        b.write(data, "B2")

        val rb = b.sync()
        assertEquals(listOf(data), rb.uploaded)
        assertEquals(emptyList<String>(), rb.downloaded)
        assertEquals(2, serverFile(data)!!.version)

        val ra = a.sync()
        assertEquals(listOf(data), ra.downloaded)
        assertEquals(emptyList<String>(), ra.uploaded)
        assertEquals("B2", a.read(data))
        assertEquals(BaseEntry(sha256("B2"), 2), a.base(data))
        a.sync().assertNothing()
    }

    // ---------------------------------------------------------------- 3
    /** A と B の両方で変更し、B が先に同期したあとの状態にする。A は競合を検出済み */
    private fun conflictState(): SyncResult {
        initialState()
        a.write(data, "A-edit")
        b.write(data, "B-edit")
        assertEquals(listOf(data), b.sync().uploaded)
        return a.sync()
    }

    @Test
    fun scenario3_conflict() {
        val ra = conflictState()

        assertEquals(emptyList<String>(), ra.uploaded)
        assertEquals(emptyList<String>(), ra.downloaded)
        assertEquals(1, ra.conflicts.size)
        val c = ra.conflicts.single()
        assertEquals(data, c.rel)
        assertEquals(SyncConflict.Kind.BOTH_CHANGED, c.kind)
        assertEquals(2, c.serverVersion)

        // A の手元は変わらない
        assertEquals("A-edit", a.read(data))
        // conflicts/ にサーバー版 *.server-v<版> ができる
        val copy = File(a.appDir, "conflicts/$targetName/$data.server-v2")
        assertEquals(copy.path, c.savedCopyPath)
        assertEquals("B-edit", copy.readText())
        assertEquals(listOf(copy.name), copy.parentFile!!.list()!!.toList())
        // base は前回の同期のまま。サーバーも B の版のまま
        assertEquals(BaseEntry(sha256("A1"), 1), a.base(data))
        assertEquals(sha256("B-edit"), serverFile(data)!!.sha256)

        // もう一度同期しても上書きしない
        val again = a.sync()
        assertEquals(listOf(data), again.conflicts.map { it.rel })
        assertEquals("A-edit", a.read(data))
    }

    // ---------------------------------------------------------------- 4
    @Test
    fun scenario4_resolveWithRemote() {
        conflictState()

        val r = a.sync(prefer = Prefer.REMOTE)
        assertEquals(listOf(data), r.downloaded)
        assertTrue(r.isClean)
        // 手元は backups/ に退避され、サーバー版になる
        assertEquals("B-edit", a.read(data))
        val backup = File(a.appDir, "backups/$targetName/20261119-120000/$data")
        assertEquals("A-edit", backup.readText())
        assertEquals(BaseEntry(sha256("B-edit"), 2), a.base(data))
        assertEquals(2, serverFile(data)!!.version)

        a.sync().assertNothing()
    }

    @Test
    fun scenario4_resolveWithLocal() {
        conflictState()

        val r = a.sync(prefer = Prefer.LOCAL)
        assertEquals(listOf(data), r.uploaded)
        assertTrue(r.isClean)
        // 送信され、サーバーの版が 1 つ進む
        val s = serverFile(data)!!
        assertEquals(3, s.version)
        assertEquals(sha256("A-edit"), s.sha256)
        assertEquals("A-edit", a.read(data))
        assertFalse(File(a.appDir, "backups").exists())

        // B は手元を変えていないので、取得するだけ
        val rb = b.sync()
        assertEquals(listOf(data), rb.downloaded)
        assertEquals("A-edit", b.read(data))
        a.sync().assertNothing()
    }

    // ---------------------------------------------------------------- 5
    @Test
    fun scenario5_uploadRejected409() {
        initialState()
        a.write(data, "A-edit")

        // A が manifest を取った直後に、B が変更して送信する
        val racing = object : RemoteApi by a.remote {
            var fired = false
            override suspend fun manifest(prefix: String): Map<String, RemoteFile> {
                val m = a.remote.manifest(prefix)
                if (!fired) {
                    fired = true
                    b.write(data, "B-edit")
                    assertEquals(listOf(data), b.sync().uploaded)
                }
                return m
            }
        }

        val r = a.sync(via = racing)
        assertEquals(emptyList<String>(), r.uploaded)
        val c = r.conflicts.single()
        assertEquals(data, c.rel)
        assertEquals(SyncConflict.Kind.UPLOAD_REJECTED, c.kind)
        assertEquals(2, c.serverVersion)
        assertEquals(sha256("B-edit"), c.serverSha256)
        assertNull(c.savedCopyPath)
        // base は更新されない。手元もサーバーもそのまま
        assertEquals(BaseEntry(sha256("A1"), 1), a.base(data))
        assertEquals("A-edit", a.read(data))
        assertEquals(sha256("B-edit"), serverFile(data)!!.sha256)

        // 再同期すると、通常の競合として検出される
        val again = a.sync()
        assertEquals(SyncConflict.Kind.BOTH_CHANGED, again.conflicts.single().kind)
        assertEquals("A-edit", a.read(data))
    }

    // ---------------------------------------------------------------- 6
    @Test
    fun scenario6_excludes() {
        val ax = Device("AX", excludes = listOf("*.log", "ULJM05800/PIC1.PNG"))
        ax.write(data, "A1")
        ax.write("debug.log", "root log")
        ax.write("ULJM05800/trace.log", "nested log") // ファイル名で一致
        ax.write("ULJM05800/PIC1.PNG", "pic") // 相対パス全体で一致
        ax.write("$data.emusync-tmp", "half written") // 一時ファイルは走査しない

        val r = ax.sync()
        assertEquals(listOf(data), r.uploaded)
        assertTrue(r.isClean)
        assertEquals(setOf(data), runBlocking { ax.remote.manifest("$targetName/") }.keys)

        // 除外したファイルは、手元にそのまま残る
        assertEquals("root log", ax.read("debug.log"))
        assertEquals("half written", ax.read("$data.emusync-tmp"))
        ax.sync().assertNothing()
    }
}
