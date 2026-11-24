package com.emustation.emusync.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import com.emustation.emusync.data.api.PlayLocks
import com.emustation.emusync.data.storage.ExcludeMatcher
import com.emustation.emusync.desktop.data.AppConfig
import com.emustation.emusync.desktop.data.ConfigStore
import com.emustation.emusync.desktop.data.Game
import com.emustation.emusync.desktop.data.SystemConfig
import com.emustation.emusync.desktop.domain.AppController
import com.emustation.emusync.desktop.domain.PlayPhase
import com.emustation.emusync.desktop.domain.SyncOutcome
import com.emustation.emusync.desktop.domain.SyncService
import com.emustation.emusync.desktop.ui.EmusyncTheme
import com.emustation.emusync.desktop.ui.Picker
import com.emustation.emusync.desktop.ui.Pickers
import com.emustation.emusync.desktop.ui.SystemPicker
import com.emustation.emusync.desktop.ui.XmbApp
import com.emustation.emusync.domain.sync.RemoteApi
import com.emustation.emusync.domain.sync.SyncMode
import com.emustation.emusync.domain.sync.TargetMutexes
import com.emustation.emusync.testing.FakeServer
import com.emustation.emusync.testing.OfflineRemote
import com.emustation.emusync.testing.sha256
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.Collections
import javax.imageio.ImageIO
import kotlin.random.Random

/**
 * モンキーテスト：PS3 風の画面（XMB）を、キー・クリック・文字入力ででたらめに操作し続ける。
 * その間に、サーバーが落ちる・ほかの端末がロックを奪う・ほかの端末がセーブを送る・手元のセーブが変わる、
 * エミュが起動に失敗する・遊んでいる間にセーブする、なども起こす。確かめること：
 *   - 例外で落ちない（画面の操作・裏の処理とも）
 *   - 固まらない（プレイが終わる）
 *   - 設定が保存され、読み直しても同じ
 *   - つながってから送り直すと、送り残し（プレイの記録）もロックも残らない
 *   - 競合として記録されたもの以外は、手元とサーバーの中身が一致する
 * MONKEY_SEED=7 で再現、MONKEY_STEPS=3000 で回数を変える。
 */
@OptIn(ExperimentalTestApi::class)
class MonkeyTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val steps = System.getenv("MONKEY_STEPS")?.toInt() ?: 250
    private val seeds = System.getenv("MONKEY_SEED")?.split(',')?.map { it.trim().toInt() } ?: listOf(1, 2)
    private val device = "win-desktop"
    private val other = "ps-vita-other"

    private lateinit var server: FakeServer
    private lateinit var app: AppController
    private lateinit var store: ConfigStore
    private lateinit var rnd: Random
    private val errors = Collections.synchronizedList(mutableListOf<String>())
    private val log = Collections.synchronizedList(mutableListOf<String>())

    @Volatile
    private var online = true

    /** 画面に出している AppController。createComposeRule は 1 回しか setContent できないので、種を変えるときは key で作り直す */
    private val current = mutableStateOf<AppController?>(null)

    /** どこまで操作が届いたか（プレイの段階・押したもの） */
    private val seenPhases = Collections.synchronizedSet(sortedSetOf<String>())
    private val clicked = Collections.synchronizedMap(sortedMapOf<String, Int>())
    private var seed = 0

    @After
    fun restorePickers() {
        Pickers.impl = SystemPicker
    }

    private fun note(s: String) {
        log += s
        if (log.size > 60) log.removeAt(0)
    }

    private fun failWith(msg: String): Nothing =
        throw AssertionError("$msg\n  seed=$seed\n  直前の操作:\n    " + log.takeLast(20).joinToString("\n    "))

    // ---------------------------------------------------------------- 準備
    private lateinit var saves: Map<String, File>
    private lateinit var exe: File

    private fun setUp(seed: Int) {
        this.seed = seed
        rnd = Random(seed)
        server = FakeServer()
        online = true
        errors.clear()
        log.clear()
        val root = tmp.newFolder("run-$seed")
        exe = File(root, "emu.exe").apply { writeText("") }
        saves = listOf("ps2", "psp", "vita").associateWith { File(root, "saves-$it").apply { mkdirs() } }
        File(saves.getValue("ps2"), "Mcd001.ps2").writeBytes(Random(seed).nextBytes(64))
        File(saves.getValue("psp"), "ULJM05800/DATA.BIN").apply { parentFile.mkdirs() }.writeBytes(byteArrayOf(1, 2, 3))
        server.seed("psp/ULJM05800/DATA.BIN", byteArrayOf(1, 2, 3), 1, other)
        server.seed("vita/PCSG00001/sce_sys/param.sfo", byteArrayOf(9), 1, other)
        val roms = File(root, "roms").apply { mkdirs() }
        listOf("ペルソナ3.iso", "dq8.iso", "mhp3.cso", "readme.txt").forEach { File(roms, it).writeText("rom") }

        store = ConfigStore(File(root, "config.json"))
        store.save(
            AppConfig(serverUrl = "https://192.168.50.49/emusync", deviceName = device).withToken("t")
                .withSystem(SystemConfig("ps2", exe.path, "-batch -- \"{rom}\"", saves.getValue("ps2").path, listOf("*.bak")))
                .withSystem(SystemConfig("psp", exe.path, "\"{rom}\"", saves.getValue("psp").path))
                .withSystem(SystemConfig("vita", File(root, "missing.exe").path, "-r {rom}", saves.getValue("vita").path))
                .withGame(Game(id = "p3", title = "ペルソナ3", target = "ps2", rom = File(roms, "ペルソナ3.iso").path))
                .withGame(Game(id = "mhp3", title = "MHP3", target = "psp", rom = File(roms, "mhp3.cso").path))
                .withGame(Game(id = "vita1", title = "Vita のゲーム", target = "vita", rom = "PCSG00001")),
        )
        val handler = CoroutineExceptionHandler { _, e -> errors += "裏の処理で例外: $e\n${e.stackTraceToString().take(1500)}" }
        app = AppController(
            store, File(root, "data"), CoroutineScope(SupervisorJob() + Dispatchers.Default + handler),
            syncFactory = { c, r ->
                SyncService(c, File(root, "data"), r, apiFactory = { if (online) remote() else OfflineRemote }, mutexes = TargetMutexes())
            },
            runAndWait = { cmd, started -> emulator(cmd, started) },
            metadata = offlineMetadata(File(root, "metadata")),
        )
        Pickers.impl = FakePicker(root, roms)
        app.scope.launch { app.play.phase.collect { p -> p?.let { seenPhases += it::class.simpleName!! } } }
        app.refresh()
    }

    private fun remote(): RemoteApi =
        object : RemoteApi by server.client(device), PlayLocks by server.locks(device) {}

    /** 偽のエミュ：たまに起動に失敗し、遊んでいる間にセーブを書き、少しして終わる */
    private suspend fun emulator(cmd: List<String>, started: () -> Unit): Long {
        val r = synchronized(rnd) { Random(rnd.nextInt()) }
        if (r.nextInt(10) == 0) throw IOException("起動できません（モンキー）")
        started()
        val target = app.config.value.systems.firstOrNull { it.emulatorExe == cmd.first() && it.savePath.isNotBlank() }
        delay(r.nextLong(10, 300))
        if (target != null && r.nextBoolean()) {
            runCatching { File(target.savePath, "save-${r.nextInt(3)}.dat").writeBytes(r.nextBytes(r.nextInt(1, 200))) }
        }
        delay(r.nextLong(10, 300))
        return r.nextLong(0, 7200)
    }

    private inner class FakePicker(private val root: File, private val roms: File) : Picker {
        override fun file(title: String, extensions: Set<String>?): File? = synchronized(rnd) {
            when (rnd.nextInt(5)) {
                0 -> null
                1 -> exe
                2 -> roms.listFiles()!!.random(rnd)
                3 -> File(root, "garbage.pem").apply { writeText("-----BEGIN CERTIFICATE-----\nnope\n") }
                else -> File(root, "does-not-exist.exe")
            }
        }.also { note("  選んだファイル: $it") }

        override fun folder(title: String, start: String?): File? = synchronized(rnd) {
            when (rnd.nextInt(4)) {
                0 -> null
                1 -> roms
                2 -> saves.values.random(rnd)
                else -> File(root, "no-such-folder")
            }
        }.also { note("  選んだフォルダ: $it") }

        override fun open(dir: File) = note("  フォルダを開く: $dir")
        override fun browse(url: String) = note("  ブラウザーで開く: $url")
    }

    // ---------------------------------------------------------------- でたらめな操作
    private val keys = listOf(
        Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown, Key.DirectionDown,
        Key.Enter, Key.Enter, Key.Escape, Key.Tab, Key.Backspace, Key.Spacebar, Key.F11,
    )
    private val texts = listOf(
        "", "https://192.168.50.49/emusync", "http://", "not a url", "日本語のゲーム", "\"{rom}\" --fullscreen",
        "{rom}", "-r {rom}", "*.bak\n*.tmp", "../../..", "x".repeat(500), "😀", "C:\\Emu\\pcsx2.exe", " ", "\n",
    )

    private fun step(i: Int) {
        val k = rnd.nextInt(100)
        when {
            k < 45 -> {
                val key = keys.random(rnd)
                note("$i キー $key")
                compose.onRoot().performKeyInput { pressKey(key) }
            }
            k < 75 -> {
                val all = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes(atLeastOneRootRequired = false)
                val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
                // 画面の外（スクロールの外など）にあるものは押せないので除く
                val onScreen = all.indices.filter { root.contains(all[it].boundsInRoot.center) }
                if (onScreen.isEmpty()) return
                val idx = onScreen.random(rnd)
                val nodes = all
                val label = nodes[idx].let { n ->
                    (n.config.getOrNull(SemanticsProperties.Text)?.joinToString() ?: "") +
                        (n.config.getOrNull(SemanticsProperties.TestTag)?.let { " #$it" } ?: "") +
                        n.children.mapNotNull { c -> c.config.getOrNull(SemanticsProperties.Text)?.joinToString() }.joinToString(" ")
                }
                note("$i クリック [$label]")
                clicked.merge(label.substringBefore(",").take(24), 1, Int::plus)
                compose.onAllNodes(hasClickAction())[idx].performClick()
            }
            k < 85 -> {
                val nodes = compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes(atLeastOneRootRequired = false)
                if (nodes.isEmpty()) return
                val idx = rnd.nextInt(nodes.size)
                val t = texts.random(rnd)
                note("$i 入力 ${t.take(30)}")
                compose.onAllNodes(hasSetTextAction())[idx].performTextReplacement(t)
            }
            else -> outsideEvent(i)
        }
    }

    /** 画面の外で起きること */
    private fun outsideEvent(i: Int) {
        val targets = listOf("ps2", "psp", "vita")
        when (rnd.nextInt(7)) {
            0 -> { online = !online; note("$i サーバー ${if (online) "復帰" else "停止"}") }
            1 -> {
                val t = targets.random(rnd)
                note("$i ほかの端末が $t のロックを奪う")
                runBlocking { server.locks(other).lock(t, 60, force = true) }
            }
            2 -> {
                val t = targets.random(rnd)
                note("$i ほかの端末が $t のロックを外す")
                runBlocking { runCatching { server.locks(other).unlock(t) } }
            }
            3 -> {
                val t = targets.random(rnd)
                val path = "$t/" + listOf("Mcd001.ps2", "ULJM05800/DATA.BIN", "save-1.dat", "new-${rnd.nextInt(3)}.dat").random(rnd)
                note("$i ほかの端末が $path を送る")
                server.seed(path, rnd.nextBytes(rnd.nextInt(1, 100)), (server.get(path)?.version ?: 0) + 1, other)
            }
            4 -> {
                val dir = saves.values.random(rnd)
                val f = File(dir, listOf("Mcd001.ps2", "save-0.dat", "save-2.dat", "ULJM05800/DATA.BIN").random(rnd))
                if (rnd.nextInt(4) == 0) {
                    note("$i 手元で ${f.name} を消す"); f.delete()
                } else {
                    note("$i 手元で ${f.name} を書く"); f.parentFile.mkdirs(); f.writeBytes(rnd.nextBytes(rnd.nextInt(1, 100)))
                }
            }
            5 -> { note("$i 定期送信"); runBlocking { runCatching { app.periodicOnce() }.onFailure { errors += "定期送信で例外: $it" } } }
            else -> { note("$i すべて同期"); app.syncAll() }
        }
    }

    // ---------------------------------------------------------------- 確かめる
    private fun checkNoErrors() {
        if (errors.isNotEmpty()) failWith(errors.first())
        // 裏の処理が書き換えている途中と比べないよう、書き換えと同じロックの中で比べる
        val same = synchronized(app) { store.load() == app.config.value }
        if (!same) failWith("保存した設定と、画面が使っている設定が違う")
        val ids = app.config.value.games.map { it.id }
        if (ids.size != ids.toSet().size) failWith("同じ ID のゲームが 2 つある: $ids")
        if (app.config.value.games.any { it.playSeconds < 0 }) failWith("プレイ時間がマイナス")
    }

    private fun drainAndVerify() {
        note("== 後片付け")
        online = true
        // プレイが終わるまで待つ（固まっていないこと）
        try {
            compose.waitUntil(30_000) { !app.play.busy && app.systems.value.values.none { it.syncing } }
        } catch (e: Throwable) {
            failWith("30 秒たってもプレイ・同期が終わらない（${app.play.phase.value}）")
        }
        (app.play.phase.value as? PlayPhase.Conflicts)?.let { runBlocking { app.play.abort(it.game) } }
        app.play.dismiss()
        compose.waitForIdle()

        // サーバーの設定をいじられていたら直す（つながらないままでは確かめられない）
        val cfg = app.config.value
        if (cfg.serverUrl.isBlank() || cfg.token.isBlank()) app.update { it.copy(serverUrl = "https://192.168.50.49/emusync").withToken("t") }

        // 送り残しを送る
        runBlocking {
            for (s in app.records.sessions()) {
                val o = app.play.finish(s.target)
                if (o is SyncOutcome.Failed) failWith("送り残しを送れない: ${s.target} ${o.message}")
            }
        }
        assertTrue("プレイの記録が残っている: ${app.records.sessions()}", app.records.sessions().isEmpty())
        for (t in listOf("ps2", "psp", "vita")) {
            if (server.lockHolder(t) == device) failWith("プレイしていないのに $t のロックが残っている")
        }

        // 競合として記録されたもの以外は、手元とサーバーが一致する
        for (s in app.config.value.systems.filter { it.enabled && it.savePath.isNotBlank() && File(it.savePath).isDirectory }) {
            val o = runBlocking { app.sync.run(s.target, SyncMode.SYNC, trigger = "monkey-final") }
            if (o !is SyncOutcome.Done) failWith("最後の同期に失敗: ${s.target} $o")
            if (o.result.errors.isNotEmpty()) failWith("最後の同期でエラー: ${o.result.errors}")
            val conflicts = app.records.conflicts(s.target).map { it.rel }.toSet() + o.result.conflicts.map { it.rel }
            val dir = File(s.savePath)
            dir.walkTopDown().filter { it.isFile }.forEach { f ->
                val rel = f.relativeTo(dir).invariantSeparatorsPath
                if (rel in conflicts || ExcludeMatcher(s.excludes).isExcluded(rel)) return@forEach
                val remote = server.get("${s.target}/$rel") ?: failWith("サーバーにない: ${s.target}/$rel")
                if (sha256(remote.data) != sha256(f.readBytes())) failWith("中身が違う: ${s.target}/$rel")
            }
        }
        checkNoErrors()
    }

    private fun SemanticsNodeInteraction.save(name: String) {
        val dir = File(System.getProperty("user.dir"), "build/screenshots").apply { mkdirs() }
        runCatching { ImageIO.write(captureToImage().toAwtImage(), "png", File(dir, "$name.png")) }
    }

    @Test
    fun monkey() {
        compose.setContent {
            EmusyncTheme {
                Box(Modifier.fillMaxSize()) {
                    current.value?.let { a -> key(a) { XmbApp(a, initialCategory = 1, wavePhase = 0.3f) } }
                }
            }
        }
        try {
            runSeeds()
        } finally {
            println("押したもの（回数）: " + clicked.entries.sortedByDescending { it.value }.joinToString { "${it.key}=${it.value}" })
        }
    }

    private fun runSeeds() {
        for (s in seeds) {
            setUp(s)
            compose.runOnIdle { current.value = app }
            compose.waitForIdle()
            for (i in 0 until steps) {
                try {
                    step(i)
                    compose.waitForIdle()
                } catch (e: AssertionError) {
                    throw e
                } catch (e: Throwable) {
                    // 画面が変わって押せなくなった要素などは無視し、それ以外（アプリの例外）は失敗にする
                    val m = e.message.orEmpty()
                    if (e is IllegalStateException && ("No node" in m || "Failed to perform" in m || "doesn't have" in m || "not attached" in m)) continue
                    failWith("操作で例外: $e\n${e.stackTraceToString().take(2000)}")
                }
                checkNoErrors()
            }
            compose.onRoot().save("desktop-monkey-seed$s")
            drainAndVerify()
            println("seed=$s プレイの段階: $seenPhases")
            assertEquals("ゲームの数がおかしい", app.config.value.games.size, app.config.value.games.distinctBy { it.id }.size)
        }
    }
}
