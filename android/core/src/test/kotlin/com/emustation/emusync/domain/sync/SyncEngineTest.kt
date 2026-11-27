package com.emustation.emusync.domain.sync

import com.emustation.emusync.data.api.ApiException
import com.emustation.emusync.data.storage.JavaLocalFs
import com.emustation.emusync.testing.FakeServer
import com.emustation.emusync.testing.InMemoryBaseStateStore
import com.emustation.emusync.testing.sha256
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** シナリオ以外の細かい動作 */
class SyncEngineTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val server = FakeServer()
    private val root by lazy { tmp.newFolder("root") }
    private val appDir by lazy { File(tmp.root, "app") }
    private val baseStore = InMemoryBaseStateStore()
    private val target by lazy { SyncTarget("psp", root.path) }

    private fun engine(remote: RemoteApi = server.client("me")) =
        SyncEngine(remote, JavaLocalFs(), baseStore, appDir.path, mutexes = TargetMutexes())

    private fun sync(mode: SyncMode = SyncMode.SYNC, prefer: Prefer? = null, dryRun: Boolean = false) =
        runBlocking { engine().sync(target, mode, prefer, dryRun) }

    private fun local(rel: String, text: String) = File(root, rel).apply {
        parentFile!!.mkdirs()
        writeText(text)
    }

    private fun remote(rel: String, text: String, version: Int) = server.seed("psp/$rel", text.toByteArray(), version)

    @Test
    fun staleHashCache_isRepairedAfterUpload_soUnsentClears() {
        // ハッシュの記録が古い中身のまま（大きさ・更新日時は今のファイルと同じ）になっていた端末。
        // 画面では「未送信」と出続け、同期しても消えなかった
        val f = local("Mcd001.ps2", "new!")
        val mtime = 1_700_000_000_000L
        f.setLastModified(mtime)
        remote("Mcd001.ps2", "new!", 1)
        runBlocking { baseStore.save("psp", mapOf("Mcd001.ps2" to BaseEntry(sha256("new!"), 1))) }
        val cache = com.emustation.emusync.data.storage.InMemoryHashCache()
        cache.put("psp", "Mcd001.ps2", com.emustation.emusync.data.storage.CachedHash(4, mtime, sha256("old!")))
        val fs = JavaLocalFs(cache)
        fun unsent() = runBlocking { baseStore.load("psp") }.let { base -> fs.scan(target).count { (rel, sha) -> base[rel]?.sha256 != sha } }
        assertEquals(1, unsent())

        // 同期すると本当の中身を送り、記録を消す → 次に調べると本当の中身になって「未送信」が消える
        runBlocking { SyncEngine(server.client("me"), fs, baseStore, appDir.path, mutexes = TargetMutexes()).sync(target, SyncMode.SYNC) }
        assertEquals(null, cache.get("psp", "Mcd001.ps2"))
        assertEquals(0, unsent())
        // もう一度同期しても、何も送らない
        val again = runBlocking { SyncEngine(server.client("me"), fs, baseStore, appDir.path, mutexes = TargetMutexes()).sync(target, SyncMode.SYNC) }
        assertEquals(emptyList<String>(), again.uploaded)
    }

    @Test
    fun download_forgetsHashOfReplacedFile() {
        val f = local("s.bin", "AAAA")
        val mtime = 1_700_000_000_000L
        f.setLastModified(mtime)
        val cache = com.emustation.emusync.data.storage.InMemoryHashCache()
        val fs = JavaLocalFs(cache)
        runBlocking { baseStore.save("psp", mapOf("s.bin" to BaseEntry(sha256("AAAA"), 1))) }
        remote("s.bin", "BBBB", 2)
        fs.scan(target)
        assertEquals(sha256("AAAA"), cache.get("psp", "s.bin")?.sha256)
        val r = runBlocking { SyncEngine(server.client("me"), fs, baseStore, appDir.path, mutexes = TargetMutexes()).sync(target, SyncMode.SYNC) }
        assertEquals(listOf("s.bin"), r.downloaded)
        assertEquals(null, cache.get("psp", "s.bin"))
        assertEquals(sha256("BBBB"), fs.scan(target)["s.bin"])
    }

    @Test
    fun dryRun_changesNothing() {
        local("up.bin", "u")
        remote("down.bin", "d", 1)
        local("both.bin", "L")
        remote("both.bin", "R", 1)

        val r = sync(dryRun = true)
        assertEquals(listOf("up.bin"), r.uploaded)
        assertEquals(listOf("down.bin"), r.downloaded)
        assertEquals(listOf("both.bin"), r.conflicts.map { it.rel })
        assertEquals(null, r.conflicts.single().savedCopyPath)
        assertTrue(r.dryRun)
        // サーバー・手元・base・conflicts/ のどれも変わらない
        assertEquals(emptyList<Pair<String, Int>>(), server.uploadLog)
        assertFalse(File(root, "down.bin").exists())
        assertEquals(0, baseStore.saveCount)
        assertFalse(appDir.exists())
    }

    @Test
    fun pullMode_doesNotUpload_pushMode_doesNotDownload() {
        local("up.bin", "u")
        remote("down.bin", "d", 1)

        val pull = sync(SyncMode.PULL)
        assertEquals(emptyList<String>(), pull.uploaded)
        assertEquals(listOf("down.bin"), pull.downloaded)

        File(root, "down.bin").delete()
        val push = sync(SyncMode.PUSH)
        assertEquals(listOf("up.bin"), push.uploaded)
        assertEquals(emptyList<String>(), push.downloaded)
        assertFalse(File(root, "down.bin").exists())
    }

    @Test
    fun deletion_isNotPropagated() {
        local("f.bin", "x")
        sync()
        File(root, "f.bin").delete()
        val r = sync()
        assertEquals(listOf("f.bin"), r.downloaded) // サーバーから戻ってくる
        assertEquals("x", File(root, "f.bin").readText())
        assertEquals(1, server.get("psp/f.bin")!!.version)
    }

    @Test
    fun sameContentWithoutBase_onlyRecordsBase() {
        local("f.bin", "same")
        remote("f.bin", "same", 4)
        val r = sync()
        assertEquals(emptyList<String>(), r.uploaded + r.downloaded)
        assertTrue(r.isClean)
        assertEquals(BaseEntry(sha256("same"), 4), baseStore.data["psp"]!!["f.bin"])
    }

    @Test
    fun preferIgnoredWhenDirectionDisabled() {
        local("f.bin", "L")
        remote("f.bin", "R", 1)
        // 手元優先でも、PULL では送信しないので競合のまま
        assertEquals(1, sync(SyncMode.PULL, Prefer.LOCAL).conflicts.size)
        // サーバー優先でも、PUSH では取得しないので競合のまま
        assertEquals(1, sync(SyncMode.PUSH, Prefer.REMOTE).conflicts.size)
        assertEquals("L", File(root, "f.bin").readText())
        assertEquals(emptyList<Pair<String, Int>>(), server.uploadLog)
    }

    @Test
    fun oneFileFailure_doesNotStopOthers() {
        remote("a.bin", "a", 1)
        remote("bad.bin", "b", 1)
        remote("c.bin", "c", 1)
        server.corruptOnDownload += "psp/bad.bin"

        val r = sync()
        assertEquals(listOf("a.bin", "c.bin"), r.downloaded)
        assertEquals(listOf("bad.bin"), r.errors.map { it.rel })
        assertTrue(r.errors.single().message.contains("壊れています"))
        // 壊れたファイルは書かれず、一時ファイルも残らない。base にも載らない
        assertEquals(listOf("a.bin", "c.bin"), root.list()!!.sorted())
        assertEquals(setOf("a.bin", "c.bin"), baseStore.data["psp"]!!.keys)
    }

    @Test
    fun manifestFailure_throws_andBaseIsNotSaved() {
        local("f.bin", "x")
        val failing = object : RemoteApi by server.client("me") {
            override suspend fun manifest(prefix: String): Map<String, RemoteFile> =
                throw ApiException.Offline("down")
        }
        try {
            runBlocking { engine(failing).sync(target) }
            fail()
        } catch (_: ApiException.Offline) {
        }
        assertEquals(0, baseStore.saveCount)
    }

    @Test
    fun filesAreProcessedInCodePointOrder_likePython() {
        // Python の sorted() はコードポイント順。UTF-16 の順とは 'ｚ'(U+FF5A) と '🎮'(U+1F3AE) で逆になる
        for (n in listOf("🎮", "ｚ", "a", "B")) local(n, n)
        assertEquals(listOf("B", "a", "ｚ", "🎮"), sync().uploaded)
    }

    @Test
    fun sameTarget_isNotSyncedConcurrently() = runBlocking {
        local("f.bin", "x")
        var inside = 0
        var maxInside = 0
        val slow = object : RemoteApi by server.client("me") {
            override suspend fun manifest(prefix: String): Map<String, RemoteFile> {
                maxInside = maxOf(maxInside, ++inside)
                kotlinx.coroutines.delay(50)
                inside--
                return server.client("me").manifest(prefix)
            }
        }
        val mutexes = TargetMutexes()
        val e1 = SyncEngine(slow, JavaLocalFs(), baseStore, appDir.path, mutexes = mutexes)
        val e2 = SyncEngine(slow, JavaLocalFs(), baseStore, appDir.path, mutexes = mutexes)
        coroutineScope {
            listOf(async { e1.sync(target) }, async { e2.sync(target) }).forEach { it.await() }
        }
        assertEquals(1, maxInside)
    }

    @Test
    fun conflictCopy_usesVersionActuallyDownloaded() {
        local("f.bin", "L")
        remote("f.bin", "R", 1)
        // manifest のあとで他の端末が版 2 にした
        server.afterManifest = { server.seed("psp/f.bin", "W".toByteArray(), 2) }
        val c = sync().conflicts.single()
        assertEquals(2, c.serverVersion)
        assertEquals("W", File(c.savedCopyPath!!).readText())
        assertTrue(c.savedCopyPath!!.endsWith("conflicts/psp/f.bin.server-v2"))
    }

    @Test
    fun only_processesListedFiles_andKeepsOtherBase() {
        local("a.bin", "a")
        local("b.bin", "b")
        sync()
        local("a.bin", "a2")
        local("b.bin", "b2")
        remote("c.bin", "c", 1)

        val r = runBlocking { engine().sync(target, only = setOf("a.bin", "nope.bin")) }
        assertEquals(listOf("a.bin"), r.uploaded)
        assertEquals(emptyList<String>(), r.downloaded)
        assertFalse(File(root, "c.bin").exists())
        // ほかのファイルの base は残る（b は前回の版のまま）
        val base = baseStore.data["psp"]!!
        assertEquals(BaseEntry(sha256("a2"), 2), base["a.bin"])
        assertEquals(BaseEntry(sha256("b"), 1), base["b.bin"])
        // 残りは次の同期で処理される
        val rest = sync()
        assertEquals(listOf("b.bin"), rest.uploaded)
        assertEquals(listOf("c.bin"), rest.downloaded)
    }
}
