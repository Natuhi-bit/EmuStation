package com.emustation.emusync.ui

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Environment
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.testing.WorkManagerTestInitHelper
import com.emustation.emusync.MainActivity
import com.emustation.emusync.data.SyncOutcome
import com.emustation.emusync.data.api.EmusyncApi
import com.emustation.emusync.data.api.HttpClients
import com.emustation.emusync.data.db.TargetEntity
import com.emustation.emusync.data.storage.ExcludeMatcher
import com.emustation.emusync.domain.play.FinishResult
import com.emustation.emusync.domain.sync.SyncMode
import com.emustation.emusync.domain.sync.UploadContent
import com.emustation.emusync.testing.MonkeyApp
import com.emustation.emusync.ui.settings.Permissions
import com.emustation.emusync.testing.sha256
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.net.Proxy
import java.util.Collections
import java.util.UUID
import kotlin.random.Random

/**
 * モンキーテスト：アプリ全体（MainActivity・画面の移動・ViewModel・Room・WorkManager）を PC 上で動かし、
 * 見えているボタン・入力欄をでたらめに操作し続ける。戻る・画面の回転（作り直し）・スクロールも混ぜる。
 * 本物のサーバー（EMUSYNC_IT_URL など。android/scripts/server-integration.sh が立てる）があれば、その間に
 * ほかの端末がセーブを送る・ロックを奪う、手元のセーブが変わる、なども起こし、最後に
 *   - 送り残し（プレイの記録）もロックも残らない
 *   - 競合として記録されたもの以外は、手元とサーバーの中身が一致する
 * ことを確かめる。サーバーがなければ「つながらない」状態で画面だけを確かめる。
 * どちらでも、例外で落ちない（画面・裏の処理とも）ことを確かめる。
 * MONKEY_SEED=7 で再現、MONKEY_STEPS=2000 で回数を変える。
 */
@RunWith(AndroidJUnit4::class)
@Config(application = MonkeyApp::class)
class AppMonkeyTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val app get() = ApplicationProvider.getApplicationContext<MonkeyApp>()
    private val steps = System.getenv("MONKEY_STEPS")?.toInt() ?: 200
    private val seed = System.getenv("MONKEY_SEED")?.split(',')?.first()?.trim()?.toInt() ?: 1
    private val rnd = Random(seed)

    private val url = System.getenv("EMUSYNC_IT_URL")?.takeIf { System.getenv("EMUSYNC_IT_DOWN") != "1" }
    private val token = System.getenv("EMUSYNC_IT_TOKEN")
    private val tokenB = System.getenv("EMUSYNC_IT_TOKEN_B")
    private val realServer = url != null && token != null && tokenB != null

    /** サーバーに残る名前がほかのテストとぶつからないように */
    private val prefix = "mk" + UUID.randomUUID().toString().take(6)
    private val targets = listOf("$prefix-psp", "$prefix-ps2", "$prefix-vita")
    private val emuPackages = listOf("org.ppsspp.ppsspp", "xyz.armsx2", "x.vita3k")
    private lateinit var dirs: Map<String, File>

    private val log = Collections.synchronizedList(mutableListOf<String>())
    private val errors = Collections.synchronizedList(mutableListOf<String>())
    private val clicked = sortedMapOf<String, Int>()
    private var previousHandler: Thread.UncaughtExceptionHandler? = null
    private lateinit var scenario: ActivityScenario<MainActivity>

    private fun note(s: String) {
        log += s
        if (log.size > 60) log.removeAt(0)
    }

    private fun failWith(msg: String): Nothing =
        throw AssertionError("$msg\n  seed=$seed サーバー=${if (realServer) "あり" else "なし"}\n  直前の操作:\n    " + log.takeLast(20).joinToString("\n    "))

    private fun other(): EmusyncApi =
        EmusyncApi(url!!, tokenB!!, HttpClients.base().newBuilder().proxy(Proxy.NO_PROXY).build())

    private fun self(): EmusyncApi =
        EmusyncApi(url!!, token!!, HttpClients.base().newBuilder().proxy(Proxy.NO_PROXY).build())

    @Before
    fun setUp() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e -> errors += "スレッド ${t.name} で例外: $e\n${e.stackTraceToString().take(1500)}" }
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        // 「すべてのファイルへのアクセス」を許可済みにする
        Permissions.allFilesAccessCheck = { true }

        // エミュ（ランチャーに出るアプリ）を入れておく
        val pm = shadowOf(app.packageManager)
        for (pkg in emuPackages + "com.example.other") {
            pm.installPackage(PackageInfo().apply {
                packageName = pkg
                applicationInfo = ApplicationInfo().apply { packageName = pkg; nonLocalizedLabel = pkg.substringAfterLast('.') }
            })
            val c = ComponentName(pkg, "$pkg.Main")
            pm.addActivityIfNotPresent(c)
            pm.addIntentFilterForActivity(c, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
        }

        dirs = targets.associateWith { tmp.newFolder(it) }
        File(dirs.getValue(targets[0]), "SAVEDATA/ULJM05800/DATA.BIN").apply { parentFile!!.mkdirs() }.writeBytes(byteArrayOf(1, 2, 3))
        File(dirs.getValue(targets[1]), "memcards/Mcd001.ps2").apply { parentFile!!.mkdirs() }.writeBytes(Random(seed).nextBytes(64))
        runBlocking {
            val c = app.container
            c.settingsStore.saveServer(url ?: "http://127.0.0.1:9", token ?: "t")
            c.db.targets().upsert(TargetEntity(targets[0], emuPackages[0], dirs.getValue(targets[0]).path, "SAVEDATA", emptyList(), sortOrder = 0))
            c.db.targets().upsert(TargetEntity(targets[1], emuPackages[1], dirs.getValue(targets[1]).path, "memcards", listOf("*.bak"), sortOrder = 1))
            c.db.targets().upsert(TargetEntity(targets[2], emuPackages[2], dirs.getValue(targets[2]).path, "", emptyList(), sortOrder = 2))
            if (realServer) other().upload("${targets[0]}/ULJM05800/DATA.BIN", bytes(byteArrayOf(1, 2, 3)), 0)
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        runCatching { scenario.close() }
        Permissions.allFilesAccessCheck = { Environment.isExternalStorageManager() }
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
    }

    private fun bytes(b: ByteArray) = UploadContent(b.size.toLong(), sha256(b)) { b.inputStream() }

    // ---------------------------------------------------------------- でたらめな操作
    private val texts = listOf(
        "", "psp", "ps2-new", "a b", "../x", "日本語", "😀", "x".repeat(300), "*.bak\n*.tmp", "SAVEDATA", "/storage/emulated/0/PSP",
        "https://192.168.50.49/emusync", "http://", "not a url", "0", "-1", "99999",
    )

    /** ダイアログやメニューが開いているときは、その中だけを操作する（実機では後ろは押せない） */
    private fun scoped(m: SemanticsMatcher): SemanticsMatcher {
        val popup = compose.onAllNodes(isPopup()).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        val dialog = compose.onAllNodes(isDialog()).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        return when {
            popup -> m and hasAnyAncestor(isPopup())
            dialog -> m and hasAnyAncestor(isDialog())
            else -> m
        }
    }

    private fun onHomeWithoutDialog(): Boolean =
        compose.onAllNodes(hasTestTag("server")).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() &&
            compose.onAllNodes(isDialog()).fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty() &&
            compose.onAllNodes(isPopup()).fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()

    private fun step(i: Int) {
        val k = rnd.nextInt(100)
        when {
            k < 55 -> {
                val m = scoped(hasClickAction())
                val all = compose.onAllNodes(m).fetchSemanticsNodes(atLeastOneRootRequired = false)
                val roots = compose.onAllNodes(isRoot()).fetchSemanticsNodes(atLeastOneRootRequired = false)
                // 画面の外（スクロールの外など）は押せないので除く
                val onScreen = all.indices.filter { idx -> roots.any { r -> r.boundsInRoot.contains(all[idx].boundsInRoot.center) } }
                if (onScreen.isEmpty()) return
                val idx = onScreen.random(rnd)
                val n = all[idx]
                val label = (n.config.getOrNull(SemanticsProperties.Text)?.joinToString() ?: "") +
                    (n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()?.let { " ($it)" } ?: "") +
                    (n.config.getOrNull(SemanticsProperties.TestTag)?.let { " #$it" } ?: "")
                note("$i クリック [$label]")
                clicked.merge(label.take(28), 1, Int::plus)
                compose.onAllNodes(m)[idx].performClick()
            }
            k < 67 -> {
                val m = scoped(hasSetTextAction())
                val nodes = compose.onAllNodes(m).fetchSemanticsNodes(atLeastOneRootRequired = false)
                if (nodes.isEmpty()) return
                val idx = rnd.nextInt(nodes.size)
                val t = texts.random(rnd)
                note("$i 入力 ${t.take(30)}")
                compose.onAllNodes(m)[idx].performTextReplacement(t)
            }
            k < 75 -> {
                if (onHomeWithoutDialog()) return // ホームで戻るとアプリが閉じる
                note("$i 戻る")
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            }
            k < 80 -> {
                val up = rnd.nextBoolean()
                note("$i スクロール ${if (up) "上" else "下"}")
                compose.onAllNodes(isRoot())[0].performTouchInput { if (up) swipeUp() else swipeDown() }
            }
            k < 83 -> {
                note("$i 画面を作り直す（回転など）")
                scenario.recreate()
            }
            else -> outsideEvent(i)
        }
    }

    /** 画面の外で起きること */
    private fun outsideEvent(i: Int) {
        val t = targets.random(rnd)
        val dir = dirs.getValue(t)
        when (rnd.nextInt(if (realServer) 5 else 2)) {
            0 -> {
                val sub = when (t) { targets[0] -> "SAVEDATA/ULJM05800"; targets[1] -> "memcards"; else -> "ux0" }
                val f = File(dir, "$sub/" + listOf("DATA.BIN", "Mcd001.ps2", "save-${rnd.nextInt(3)}.dat").random(rnd))
                if (rnd.nextInt(4) == 0) { note("$i 手元で ${f.name} を消す"); f.delete() }
                else { note("$i 手元で ${f.name} を書く"); f.parentFile!!.mkdirs(); f.writeBytes(rnd.nextBytes(rnd.nextInt(1, 100))) }
            }
            1 -> { note("$i データフォルダごと消える/戻る"); if (dir.exists() && rnd.nextInt(5) == 0) dir.deleteRecursively() else dir.mkdirs() }
            2 -> {
                val rel = listOf("ULJM05800/DATA.BIN", "Mcd001.ps2", "save-1.dat", "new-${rnd.nextInt(3)}.dat").random(rnd)
                note("$i ほかの端末が $t/$rel を送る")
                runBlocking {
                    val api = other()
                    val cur = runCatching { api.manifest("$t/")[rel]?.version ?: 0 }.getOrDefault(0)
                    runCatching { api.upload("$t/$rel", bytes(rnd.nextBytes(rnd.nextInt(1, 100))), cur) }
                }
            }
            3 -> { note("$i ほかの端末が $t のロックを奪う"); runBlocking { runCatching { other().lock(t, 120, force = true) } } }
            else -> { note("$i ほかの端末が $t のロックを外す"); runBlocking { runCatching { other().unlock(t) } } }
        }
    }

    // ---------------------------------------------------------------- 確かめる
    private fun checkNoErrors() {
        if (errors.isNotEmpty()) failWith(errors.first())
    }

    private fun verifyWithServer() {
        note("== 後片付け")
        // 進行中のダイアログ・同期が終わるまで待つ（固まっていないこと）
        try {
            compose.waitUntil(30_000) {
                compose.onAllNodes(isDialog()).fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty() ||
                    compose.onAllNodes(hasAnyAncestor(isDialog()) and hasClickAction()).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
            }
        } catch (e: Throwable) {
            failWith("30 秒たっても進行中のダイアログが閉じない")
        }
        // 最後の操作で開いたプレイのダイアログ（競合など。開いている間はロックを持つ作り）は［やめる］で閉じる
        repeat(3) {
            val cancel = compose.onAllNodes(hasAnyAncestor(isDialog()) and hasText("やめる") and hasClickAction())
            if (cancel.fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()) {
                note("［やめる］でダイアログを閉じる")
                cancel[0].performClick()
                compose.waitForIdle()
                Thread.sleep(300)
                compose.waitForIdle()
            }
        }
        val c = app.container
        runBlocking {
            // でたらめに書き換えられたサーバーの設定と、消されたデータフォルダを元に戻す（つながらないままでは確かめられない）
            c.settingsStore.saveServer(url!!, token!!)
            c.settingsStore.setCaFileName(null)
            for (t in c.db.targets().getAll()) if (!File(t.dataDir).isDirectory) dirs[t.name]?.let { File(it, t.relSubdir).mkdirs() }
            for (t in targets) runCatching { other().unlock(t) }
            for (s in c.db.playSessions().observeAll().first()) {
                val r = c.playSessionManager.finish(s.target)
                if (r !is FinishResult.Done) {
                    val t = c.db.targets().get(s.target)
                    val why = (r as? FinishResult.Failed)?.message?.resolve(app) ?: r.toString()
                    failWith("送り残しを送れない: ${s.target}「$why」 データフォルダ=${t?.dataDir}（${if (t != null && File(t.dataDir).isDirectory) "ある" else "ない"}） 相対=${t?.relSubdir}")
                }
            }
            if (c.db.playSessions().observeAll().first().isNotEmpty()) failWith("プレイの記録が残っている")
            val held = self().locks().filter { it.target in targets && it.device == "it-android" }
            if (held.isNotEmpty()) failWith("プレイしていないのにロックが残っている: $held")

            for (t in c.db.targets().getAll().filter { it.name in targets && it.enabled }) {
                val root = File(if (t.relSubdir.isBlank()) t.dataDir else "${t.dataDir}/${t.relSubdir}")
                if (!root.isDirectory) continue
                val o = c.syncRepository.run(t, SyncMode.SYNC)
                if (o !is SyncOutcome.Done) failWith("最後の同期に失敗: ${t.name} $o")
                if (o.result.errors.isNotEmpty()) failWith("最後の同期でエラー: ${o.result.errors}")
                val conflicts = c.db.conflicts().getAll(t.name).map { it.rel }.toSet() + o.result.conflicts.map { it.rel }
                val remote = self().manifest("${t.name}/")
                val excludes = ExcludeMatcher(t.excludes)
                root.walkTopDown().filter { it.isFile }.forEach { f ->
                    val rel = f.relativeTo(root).invariantSeparatorsPath
                    if (rel in conflicts || excludes.isExcluded(rel)) return@forEach
                    val r = remote[rel] ?: failWith("サーバーにない: ${t.name}/$rel")
                    if (r.sha256 != sha256(f.readBytes())) failWith("中身が違う: ${t.name}/$rel")
                }
            }
        }
    }

    /** 連打（ダブルタップ）しても、エミュは 1 回だけ起動する */
    @Test
    fun doubleTapOnLaunchWithoutSync_launchesEmulatorOnce() {
        runBlocking { app.container.settingsStore.saveServer("http://127.0.0.1:9", "t") } // つながらない
        scenario.recreate()
        compose.onNode(hasTestTag("play-${targets[0]}")).performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("同期せずに起動")).fetchSemanticsNodes().isNotEmpty() }
        while (shadowOf(app).nextStartedActivity != null) Unit // ここまでに開いたものは数えない
        // 1 回目の処理が進む前に、2 回目が届く
        compose.onNode(hasText("同期せずに起動")).performTouchInput { click(); advanceEventTime(80); click() }
        // 「プレイ中」と出たあとにエミュを起動するので、起動が記録されるまで待つ。そのあとも少し待って、2 回目がないことを確かめる
        var launched = 0
        fun collect() {
            launched += generateSequence { shadowOf(app).nextStartedActivity }
                .count { it.`package` == emuPackages[0] || it.component?.packageName == emuPackages[0] }
        }
        // （PC 上ではメインスレッドの処理は待っている間にしか進まないので、毎回 waitForIdle する）
        val deadline = System.currentTimeMillis() + 15_000
        while (launched == 0 && System.currentTimeMillis() < deadline) { compose.waitForIdle(); collect(); Thread.sleep(50) }
        repeat(10) { compose.waitForIdle(); Thread.sleep(100); collect() }
        assertEquals("エミュは 1 回だけ起動する", 1, launched)
        checkNoErrors()
    }

    @Test
    fun monkey() {
        try {
            run()
        } finally {
            println("操作 $steps 回（seed=$seed サーバー=${if (realServer) "あり" else "なし"}）")
            println("押したもの（回数）: " + clicked.entries.sortedByDescending { it.value }.joinToString { "${it.key}=${it.value}" })
        }
    }

    private fun run() {
        compose.waitForIdle()
        for (i in 0 until steps) {
            try {
                step(i)
                compose.waitForIdle()
            } catch (e: AssertionError) {
                // 操作しようとした要素が、その間に画面から消えた（スクロール・画面の移動）ときは無視する。
                // それ以外（このテストの確認の失敗など）はそのまま失敗
                if (isStale(e)) continue
                throw e
            } catch (e: Throwable) {
                val m = e.message.orEmpty()
                // 画面が変わって押せなくなった要素は無視する。それ以外（アプリの例外）は失敗
                if (isStale(e) || e is IllegalStateException && ("No node" in m || "Failed to perform" in m || "doesn't have" in m ||
                        "not attached" in m || "No compose hierarchies" in m || "outside" in m)) continue
                failWith("操作で例外: $e\n${e.stackTraceToString().take(2500)}")
            }
            checkNoErrors()
        }
        if (realServer) verifyWithServer()
        checkNoErrors()
    }

    /** 操作しようとした要素が、その間に画面から消えた（テストの都合で、アプリの不具合ではない） */
    private fun isStale(e: Throwable): Boolean {
        val m = e.message.orEmpty()
        // 「Can't retrieve node at index」：押せる要素を数えてから押すまでの間に、要素が減った
        return "seed=" !in m && (
            "no longer in the tree" in m || "Failed to perform" in m || "No node" in m || "outside of the" in m ||
                "Can't retrieve node at index" in m
            )
    }
}
