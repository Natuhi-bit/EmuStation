package com.emustation.emusync.data.storage

import com.emustation.emusync.domain.sync.SyncTarget
import com.emustation.emusync.testing.sha256
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class JavaLocalFsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun write(f: File, text: String) {
        f.parentFile!!.mkdirs()
        f.writeText(text)
    }

    @Test
    fun scan_listsFilesRecursivelyWithSlash_skipsTmpAndExcluded() {
        val root = tmp.newFolder("SAVEDATA")
        write(File(root, "A/DATA.BIN"), "d")
        write(File(root, "A/B/C.BIN"), "c")
        write(File(root, "top.bin"), "t")
        write(File(root, "A/DATA.BIN.emusync-tmp"), "x")
        write(File(root, "A/x.log"), "l")
        File(root, "emptydir").mkdirs()

        val got = JavaLocalFs().scan(SyncTarget("psp", root.path, listOf("*.log")))
        assertEquals(
            mapOf("A/DATA.BIN" to sha256("d"), "A/B/C.BIN" to sha256("c"), "top.bin" to sha256("t")),
            got,
        )
    }

    @Test
    fun scan_missingRoot_isEmpty() {
        assertEquals(emptyMap<String, String>(), JavaLocalFs().scan(SyncTarget("psp", tmp.root.path + "/nope")))
    }

    @Test
    fun scan_doesNotFollowDirectorySymlinks_butIncludesFileSymlinks() {
        val root = tmp.newFolder("root")
        val outside = tmp.newFolder("outside")
        write(File(outside, "o.bin"), "o")
        Files.createSymbolicLink(File(root, "linkdir").toPath(), outside.toPath())
        Files.createSymbolicLink(File(root, "linkfile").toPath(), File(outside, "o.bin").toPath())
        assertEquals(mapOf("linkfile" to sha256("o")), JavaLocalFs().scan(SyncTarget("psp", root.path)))
    }

    @Test
    fun hashCache_skipsRehashWhenSizeAndMtimeSame() {
        val root = tmp.newFolder("root")
        val f = File(root, "m.ps2")
        write(f, "aaaa")
        f.setLastModified(1_700_000_000_000)
        val cache = InMemoryHashCache()
        val fs = JavaLocalFs(cache)
        val t = SyncTarget("ps2", root.path)
        assertEquals(sha256("aaaa"), fs.scan(t)["m.ps2"])

        // 同じサイズ・同じ更新日時に戻すと、キャッシュの値が使われる（＝再計算していない）
        f.writeText("bbbb")
        f.setLastModified(1_700_000_000_000)
        assertEquals(sha256("aaaa"), fs.scan(t)["m.ps2"])

        // 更新日時が変われば計算し直す
        f.setLastModified(1_700_000_001_000)
        assertEquals(sha256("bbbb"), fs.scan(t)["m.ps2"])

        // 消えたファイルの記録は消す
        f.delete()
        fs.scan(t)
        assertEquals(null, cache.get("ps2", "m.ps2"))
    }

    @Test
    fun hashCache_doesNotKeepFilesChangedJustNow() {
        // 更新されたばかりのファイルは記録しない（同じ 1 秒の中でさらに書き換えられると、大きさも日時も同じまま中身が変わる）
        val root = tmp.newFolder("racy")
        val f = File(root, "m.ps2")
        write(f, "aaaa")
        val mtime = 1_700_000_000_000L
        f.setLastModified(mtime)
        val cache = InMemoryHashCache()
        val t = SyncTarget("ps2", root.path)
        var now = mtime + 1_000
        val fs = JavaLocalFs(cache) { now }
        assertEquals(sha256("aaaa"), fs.scan(t)["m.ps2"])
        assertEquals(null, cache.get("ps2", "m.ps2"))

        // エミュがその間に書き終えた（大きさ・更新日時は同じ）。記録していないので、本当の中身を読む
        f.writeText("bbbb")
        f.setLastModified(mtime)
        assertEquals(sha256("bbbb"), fs.scan(t)["m.ps2"])

        // 時間が経てば記録する
        now = mtime + 10_000
        assertEquals(sha256("bbbb"), fs.scan(t)["m.ps2"])
        assertEquals(sha256("bbbb"), cache.get("ps2", "m.ps2")?.sha256)

        // 記録を消せる（送受信したあと）
        fs.forgetHash(t, "m.ps2")
        assertEquals(null, cache.get("ps2", "m.ps2"))
    }

    @Test
    fun atomicWrite_commitReplaces_abortKeepsOriginal() {
        val fs = JavaLocalFs()
        val f = File(tmp.root, "new/dir/f.bin") // 親フォルダは作られる
        fs.beginAtomicWrite(f.path).apply {
            output.write("one".toByteArray())
            commit()
            abort() // commit 後は何もしない
        }
        assertEquals("one", f.readText())

        val w = fs.beginAtomicWrite(f.path)
        w.output.write("tw".toByteArray())
        assertTrue(File(f.path + ".emusync-tmp").exists())
        w.abort()
        assertEquals("one", f.readText())
        assertFalse(File(f.path + ".emusync-tmp").exists())

        fs.beginAtomicWrite(f.path).apply {
            output.write("two".toByteArray())
            commit()
        }
        assertEquals("two", f.readText())
        assertEquals(listOf("f.bin"), f.parentFile!!.list()!!.toList())
    }

    @Test
    fun copy_keepsMtime_andMoveReplaces() {
        val fs = JavaLocalFs()
        val src = File(tmp.root, "s.bin").apply { writeText("s"); setLastModified(1_600_000_000_000) }
        val dst = File(tmp.root, "b/c/d.bin")
        fs.copy(src.path, dst.path)
        assertEquals("s", dst.readText())
        assertEquals(1_600_000_000_000, dst.lastModified())

        val other = File(tmp.root, "o.bin").apply { writeText("o") }
        fs.move(other.path, dst.path)
        assertEquals("o", dst.readText())
        assertFalse(other.exists())
    }

    @Test
    fun prepareUpload_hashesCurrentContent_andCanReopen() {
        val f = File(tmp.root, "u.bin").apply { writeText("payload") }
        val c = JavaLocalFs().prepareUpload(f.path)
        assertEquals(7, c.size)
        assertEquals(sha256("payload"), c.sha256)
        assertEquals("payload", c.open().use { String(it.readBytes()) })
        assertEquals("payload", c.open().use { String(it.readBytes()) })
    }
}
