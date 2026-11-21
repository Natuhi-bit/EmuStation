package com.emustation.emusync.domain.sync

import com.emustation.emusync.data.api.EmusyncApi
import com.emustation.emusync.data.api.HttpClients
import com.emustation.emusync.data.storage.JavaLocalFs
import com.emustation.emusync.testing.InMemoryBaseStateStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.Proxy
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Python 版クライアント（Windows 側）と Kotlin 版の同期エンジン（Android 側）を、
 * 同じ本物のサーバーに対して交互に動かし、互いの結果を受け取れることを確かめる（§11 シナリオ 10 の先取り）。
 *
 * 環境変数があるときだけ動く：EMUSYNC_IT_URL, EMUSYNC_IT_TOKEN（Android 側）, EMUSYNC_IT_TOKEN_B（Python 側）,
 * EMUSYNC_IT_PYTHON（requests と PyYAML を入れた python）, EMUSYNC_IT_CLIENT（client/emusync.py）
 */
class PythonInteropTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val url = System.getenv("EMUSYNC_IT_URL")
    private val tokenAndroid = System.getenv("EMUSYNC_IT_TOKEN")
    private val tokenWin = System.getenv("EMUSYNC_IT_TOKEN_B")
    private val python = System.getenv("EMUSYNC_IT_PYTHON")
    private val client = System.getenv("EMUSYNC_IT_CLIENT")

    private val target = "psp-py-" + UUID.randomUUID().toString().take(8)
    private val data = "ULJM05800/DATA.BIN"

    private lateinit var winRoot: File
    private lateinit var winState: File
    private lateinit var config: File
    private lateinit var andRoot: File
    private lateinit var andApp: File
    private val baseStore = InMemoryBaseStateStore()
    private val api by lazy {
        EmusyncApi(url!!, tokenAndroid!!, HttpClients.base().newBuilder().proxy(Proxy.NO_PROXY).build())
    }

    @Before
    fun setUp() {
        assumeTrue(
            listOf(url, tokenAndroid, tokenWin, python, client).all { it != null } &&
                System.getenv("EMUSYNC_IT_DOWN") != "1",
        )
        winRoot = tmp.newFolder("win", "SAVEDATA")
        winState = tmp.newFolder("win", "state")
        andRoot = tmp.newFolder("android", "SAVEDATA")
        andApp = tmp.newFolder("android", "app")
        config = File(tmp.root, "config.yaml")
        // YAML は JSON の上位互換なので、値は JSON の文字列で書く
        config.writeText(
            """
            server: "$url"
            token: "$tokenWin"
            state_dir: "${winState.path}"
            targets:
              $target:
                path: "${winRoot.path}"
                exclude: ["*.log"]
            """.trimIndent(),
        )
    }

    // ---------------------------------------------------------------- 補助

    private class Proc(val code: Int, val out: String)

    /** Python 版クライアントを実行する */
    private fun py(vararg args: String): Proc {
        val pb = ProcessBuilder(listOf(python!!, client!!, "-c", config.path) + args)
            .redirectErrorStream(true)
        pb.environment().apply {
            put("PYTHONIOENCODING", "utf-8")
            put("NO_PROXY", "*")
            listOf("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy").forEach { remove(it) }
        }
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue("python が終わらない", p.waitFor(60, TimeUnit.SECONDS))
        return Proc(p.exitValue(), out)
    }

    private fun android(mode: SyncMode = SyncMode.SYNC, prefer: Prefer? = null): SyncResult = runBlocking {
        SyncEngine(api, JavaLocalFs(), baseStore, andApp.path, mutexes = TargetMutexes())
            .sync(SyncTarget(target, andRoot.path, listOf("*.log")), mode, prefer)
    }

    private fun put(root: File, rel: String, bytes: ByteArray) {
        val f = File(root, rel)
        val before = if (f.exists()) f.lastModified() else 0L
        f.parentFile!!.mkdirs()
        f.writeBytes(bytes)
        f.setLastModified(maxOf(System.currentTimeMillis(), before + 2000))
    }

    private fun put(root: File, rel: String, text: String) = put(root, rel, text.toByteArray())

    // ---------------------------------------------------------------- テスト

    @Test
    fun filesTravelBothWays_includingBinaryAndUnicodeNames() {
        val memcard = Random(1).nextBytes(1 shl 20)
        val unicode = "セーブ データ/a+b&c=d%e #1.bin"
        put(winRoot, data, "win1")
        put(winRoot, "Mcd001.ps2", memcard)
        put(winRoot, unicode, "u")

        val p1 = py("sync", target)
        assertEquals(p1.out, 0, p1.code)
        assertTrue(p1.out, p1.out.contains("↑ 送信  $data"))

        val r1 = android()
        assertEquals(listOf("Mcd001.ps2", data, unicode).sortedWith(SyncEngine.CODE_POINT_ORDER), r1.downloaded)
        assertTrue(r1.isClean)
        assertEquals("win1", File(andRoot, data).readText())
        assertArrayEquals(memcard, File(andRoot, "Mcd001.ps2").readBytes())
        assertEquals("u", File(andRoot, unicode).readText())

        put(andRoot, data, "android2")
        put(andRoot, "$unicode-2", "u2")
        assertEquals(listOf(data, "$unicode-2"), android().uploaded)

        val p2 = py("sync", target)
        assertEquals(p2.out, 0, p2.code)
        assertTrue(p2.out, p2.out.contains("↓ 取得  $data"))
        assertEquals("android2", File(winRoot, data).readText())
        assertEquals("u2", File(winRoot, "$unicode-2").readText())

        // 両方とも、もう何もしない
        assertTrue(py("sync", target).out.contains("変更なし"))
        assertTrue(!android().hasChanges)
    }

    @Test
    fun conflictsAreDetectedTheSameWayOnBothSides() {
        put(winRoot, data, "v1")
        py("sync", target)
        android()

        // Android が先に送信 → Windows は競合
        put(andRoot, data, "android-edit")
        put(winRoot, data, "win-edit")
        assertEquals(listOf(data), android().uploaded)
        val p = py("sync", target)
        assertEquals(p.out, 2, p.code)
        assertTrue(p.out, p.out.contains("! 競合  $data"))
        assertEquals("win-edit", File(winRoot, data).readText())
        assertEquals("android-edit", File(winState, "conflicts/$target/$data.server-v2").readText())

        // Windows が手元優先で解決 → Android は取得するだけ
        assertEquals(0, py("sync", target, "--prefer", "local").code)
        assertEquals(listOf(data), android().downloaded)
        assertEquals("win-edit", File(andRoot, data).readText())

        // 今度は Windows が先に送信 → Android は競合
        put(andRoot, data, "android-edit2")
        put(winRoot, data, "win-edit2")
        assertEquals(0, py("sync", target).code)
        val c = android().conflicts.single()
        assertEquals(SyncConflict.Kind.BOTH_CHANGED, c.kind)
        assertEquals(4, c.serverVersion)
        assertEquals("win-edit2", File(c.savedCopyPath!!).readText())
        assertEquals("android-edit2", File(andRoot, data).readText())

        // Android がサーバー優先で解決 → Windows は何もしない
        assertEquals(listOf(data), android(prefer = Prefer.REMOTE).downloaded)
        assertEquals("win-edit2", File(andRoot, data).readText())
        assertTrue(py("sync", target).out.contains("変更なし"))
    }

    @Test
    fun excludesAgree() {
        put(winRoot, "debug.log", "w")
        put(winRoot, "sub/trace.log", "w")
        put(winRoot, data, "d")
        put(andRoot, "android.log", "a")
        py("sync", target)
        android()
        assertEquals(setOf(data), runBlocking { api.manifest("$target/") }.keys)
    }

    @Test
    fun restoreByWindows_isPulledByAndroid() {
        put(winRoot, data, "good")
        py("sync", target)
        put(winRoot, data, "broken")
        py("sync", target)
        android()
        assertEquals("broken", File(andRoot, data).readText())

        val r = py("restore", target, data, "1")
        assertEquals(r.out, 0, r.code)
        assertEquals(listOf(data), android(SyncMode.PULL).downloaded)
        assertEquals("good", File(andRoot, data).readText())
    }
}
