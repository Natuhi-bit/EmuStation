package com.emustation.emusync.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.dp
import com.emustation.emusync.data.api.PlayLocks
import com.emustation.emusync.desktop.data.AppConfig
import com.emustation.emusync.desktop.data.ConfigStore
import com.emustation.emusync.desktop.data.Game
import com.emustation.emusync.desktop.data.SystemConfig
import com.emustation.emusync.desktop.domain.AppController
import com.emustation.emusync.desktop.domain.PlayPhase
import com.emustation.emusync.desktop.domain.SyncService
import com.emustation.emusync.desktop.platform.Sfx
import com.emustation.emusync.desktop.ui.EmusyncTheme
import com.emustation.emusync.desktop.ui.XmbApp
import com.emustation.emusync.domain.sync.RemoteApi
import com.emustation.emusync.domain.sync.TargetMutexes
import com.emustation.emusync.testing.FakeServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.imageio.ImageIO

/** PS3 風の画面（XMB）の操作と見た目。画像は desktop/build/screenshots/ に出る */
@OptIn(ExperimentalTestApi::class)
class XmbScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val server = FakeServer()
    private val release = CompletableDeferred<Unit>()
    private lateinit var app: AppController
    private val fixedNow = 1_763_800_000_000L

    @Before
    fun setUp() {
        val saves = tmp.newFolder("memcards")
        val exe = File(tmp.root, "pcsx2-qt.exe").apply { writeText("") }
        val store = ConfigStore(File(tmp.root, "config.json"))
        store.save(
            AppConfig(serverUrl = "https://192.168.50.49/emusync").withToken("t")
                .withSystem(SystemConfig("ps2", exe.path, "-batch -- \"{rom}\"", saves.path, listOf("*.bak")))
                .withSystem(SystemConfig("psp", "C:\\Emu\\PPSSPP\\PPSSPPWindows64.exe", "\"{rom}\"", tmp.newFolder("SAVEDATA").path))
                .withGame(Game(id = "p3", title = "ペルソナ3 フェス", target = "ps2", rom = "D:\\PS2\\p3fes.iso", lastPlayedAt = fixedNow - 3_600_000, playSeconds = 45_000))
                .withGame(Game(id = "dq8", title = "ドラゴンクエストVIII", target = "ps2", rom = "D:\\PS2\\dq8.iso", lastPlayedAt = fixedNow - 86_400_000, playSeconds = 200_000))
                .withGame(Game(id = "mhp3", title = "モンスターハンターポータブル 3rd", target = "psp", rom = "D:\\PSP\\mhp3.iso", playSeconds = 0)),
        )
        app = AppController(
            store, tmp.newFolder("data"), CoroutineScope(Dispatchers.Default),
            syncFactory = { c, r ->
                SyncService(c, File(tmp.root, "data"), r, apiFactory = {
                    object : RemoteApi by server.client("win-desktop"), PlayLocks by server.locks("win-desktop") {}
                }, mutexes = TargetMutexes())
            },
            runAndWait = { _, started -> started(); release.await(); 3600L },
            metadata = offlineMetadata(File(tmp.root, "metadata")),
        )
        server.seed("ps2/Mcd001.ps2", "x".toByteArray(), 1)
        runBlocking { app.refresh(); kotlinx.coroutines.delay(500) }
    }

    private fun show(category: Int = 1) = compose.setContent {
        EmusyncTheme {
            Box(Modifier.size(1280.dp, 720.dp)) { XmbApp(app, initialCategory = category, now = { fixedNow }, wavePhase = 0.3f) }
        }
    }

    private fun SemanticsNodeInteraction.save(name: String) {
        val dir = File(System.getProperty("user.dir"), "build/screenshots").apply { mkdirs() }
        ImageIO.write(captureToImage().toAwtImage(), "png", File(dir, "$name.png"))
    }

    @Test
    fun games_sortedByLastPlayed_andKeyboardNavigation() {
        show()
        compose.waitForIdle()
        compose.onNodeWithText("ペルソナ3 フェス").assertExists()
        compose.onNodeWithText("ゲームを追加").assertExists()
        compose.onRoot().save("desktop-games")

        // ハードごとの列：PS2 の列には PSP のゲームは出ない
        compose.onNodeWithText("モンスターハンターポータブル 3rd").assertDoesNotExist()
        compose.onNodeWithText("PS2").assertExists() // 選んでいる列の名前

        // 下で次のゲーム、右で PSP の列、もう一度右で「セーブデータ」、Tab でオプション
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()
        compose.onNodeWithText("モンスターハンターポータブル 3rd").assertExists()
        compose.onNodeWithText("ペルソナ3 フェス").assertDoesNotExist()
        compose.onRoot().save("desktop-games-psp")
        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()
        compose.onNodeWithText("PS2").assertExists()
        compose.onRoot().performKeyInput { pressKey(Key.Tab) }
        compose.waitForIdle()
        compose.onNodeWithText("今すぐ同期").assertExists()
        compose.onRoot().save("desktop-savedata-options")
    }

    @Test
    fun settings_andServerPanel() {
        show(category = 0)
        compose.waitForIdle()
        compose.onNodeWithText("PS3（RPCS3）").assertExists()
        compose.onRoot().save("desktop-settings")
        compose.onRoot().performKeyInput { pressKey(Key.Enter) }
        compose.waitForIdle()
        compose.onNodeWithTag("server-url").assertExists()
        compose.onRoot().save("desktop-server-panel")
        compose.onRoot().performKeyInput { pressKey(Key.Escape) }
        compose.waitForIdle()
        compose.onNodeWithTag("server-url").assertDoesNotExist()
    }

    @Test
    fun emulatorPanel() {
        show(category = 0)
        compose.onNodeWithTag("item-emu-ps2").performClick() // 選ぶ
        compose.onNodeWithTag("item-emu-ps2").performClick() // 開く
        compose.waitForIdle()
        compose.onNodeWithTag("emu-exe").assertExists()
        compose.onRoot().save("desktop-emulator-panel")
    }

    @Test
    fun play_showsOverlay_thenSavesWhenEmulatorCloses() {
        show()
        compose.waitForIdle()
        compose.onRoot().performKeyInput { pressKey(Key.Enter) } // ペルソナ3 をプレイ
        compose.waitUntil(10_000) { app.play.phase.value is PlayPhase.Playing }
        compose.waitForIdle()
        compose.onNodeWithTag("play-overlay").assertExists()
        compose.onRoot().save("desktop-playing")
        assertEquals("win-desktop", server.lockHolder("ps2"))

        release.complete(Unit) // エミュを閉じた
        compose.waitUntil(10_000) { app.play.phase.value is PlayPhase.Finished }
        compose.waitForIdle()
        compose.onRoot().save("desktop-finished")
        assertEquals(null, server.lockHolder("ps2"))
        compose.onNodeWithTag("overlay-ok").performClick()
        compose.waitForIdle()
        assertTrue(app.play.phase.value == null)
        assertEquals(3600L, app.config.value.games.first { it.id == "p3" }.playSeconds - 45_000)
    }

    @Test
    fun conflictOnPlay_showsChoices() {
        File(app.config.value.system("ps2")!!.savePath, "Mcd001.ps2").writeText("local, different")
        show()
        compose.waitForIdle()
        compose.onRoot().performKeyInput { pressKey(Key.Enter) }
        compose.waitUntil(10_000) { app.play.phase.value is PlayPhase.Conflicts }
        compose.waitForIdle()
        compose.onNodeWithText("競合が 1 件あります").assertExists()
        compose.onRoot().save("desktop-conflict")
        compose.onNodeWithText("競合を見る").performClick()
        compose.waitUntil(10_000) { app.play.phase.value == null }
        compose.waitForIdle()
        compose.onNodeWithText("サーバー版を使う").assertExists()
        compose.onRoot().save("desktop-conflicts-panel")
    }

    @Test
    fun operationSounds_likePs3() {
        val heard = mutableListOf<com.emustation.emusync.desktop.platform.Sfx>()
        com.emustation.emusync.desktop.platform.Sounds.listener = { heard += it }
        try {
            show()
            compose.waitForIdle()
            compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) } // 項目を動かす
            compose.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
            compose.onRoot().performKeyInput { pressKey(Key.DirectionUp) } // 一番上なので動かない → 鳴らない
            compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) } // 列を動かす
            compose.onRoot().performKeyInput { pressKey(Key.Tab) } // オプション
            compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
            compose.onRoot().performKeyInput { pressKey(Key.Escape) } // 閉じる
            compose.onRoot().performKeyInput { pressKey(Key.DirectionLeft); pressKey(Key.DirectionLeft) } // 設定の列
            compose.onRoot().performKeyInput { pressKey(Key.Enter) } // サーバーのパネル
            compose.waitForIdle()
            compose.onRoot().performKeyInput { pressKey(Key.Escape) }
            compose.waitForIdle()
            assertEquals(
                listOf(Sfx.Cursor, Sfx.Cursor, Sfx.Cursor, Sfx.Options, Sfx.Cursor, Sfx.Back, Sfx.Cursor, Sfx.Cursor, Sfx.Decide, Sfx.Back),
                heard,
            )
        } finally {
            com.emustation.emusync.desktop.platform.Sounds.listener = null
        }
    }
}
