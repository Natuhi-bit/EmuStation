package com.emustation.emusync.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.emustation.emusync.data.api.PlayLocks
import com.emustation.emusync.desktop.data.AppConfig
import com.emustation.emusync.desktop.data.ConfigStore
import com.emustation.emusync.desktop.data.Game
import com.emustation.emusync.desktop.data.SystemConfig
import com.emustation.emusync.desktop.domain.AppController
import com.emustation.emusync.desktop.domain.PlayPhase
import com.emustation.emusync.desktop.domain.SyncService
import com.emustation.emusync.desktop.platform.Gamepad
import com.emustation.emusync.desktop.platform.PadButton
import com.emustation.emusync.desktop.platform.PadButton.A
import com.emustation.emusync.desktop.platform.PadButton.B
import com.emustation.emusync.desktop.platform.PadButton.Down
import com.emustation.emusync.desktop.platform.PadButton.LB
import com.emustation.emusync.desktop.platform.PadButton.Left
import com.emustation.emusync.desktop.platform.PadButton.RB
import com.emustation.emusync.desktop.platform.PadButton.Right
import com.emustation.emusync.desktop.platform.PadButton.Up
import com.emustation.emusync.desktop.platform.PadButton.Y
import com.emustation.emusync.desktop.platform.PadKeys
import com.emustation.emusync.desktop.platform.PadMode
import com.emustation.emusync.desktop.platform.PadRepeater
import com.emustation.emusync.desktop.platform.PadState
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.event.KeyEvent
import java.io.File

/** コントローラー（XInput）の読み取り・くり返し・キーへの割り当てと、コントローラーだけでの操作 */
@OptIn(ExperimentalTestApi::class)
class GamepadTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    @org.junit.After
    fun resetInputMode() {
        Gamepad.markKeyboard()
        Gamepad.setStyle(com.emustation.emusync.desktop.platform.PadStyle.Xbox)
    }

    // ---------------------------------------------------------------- 読み取りと割り当て
    @Test
    fun xinputBitsAndStick() {
        assertEquals(setOf(A, PadButton.Start), PadState.fromXInput(0x1000 or 0x0010, 0, 0).pressed)
        assertEquals(setOf(Up, LB), PadState.fromXInput(0x0001 or 0x0100, 0, 0).pressed)
        // スティック：少しだけ倒したのは無視、強く倒した向きだけ（斜めは大きい方）
        assertEquals(emptySet<PadButton>(), PadState.fromXInput(0, 8000, -9000).pressed)
        assertEquals(setOf(Right), PadState.fromXInput(0, 30000, 20000).pressed)
        assertEquals(setOf(Down), PadState.fromXInput(0, -10000, -32768).pressed)
    }

    @Test
    fun repeater_pressOnce_andRepeatDirections() {
        val r = PadRepeater(initialDelayMs = 300, repeatMs = 100)
        val down = PadState(setOf(Down, A))
        assertEquals(listOf(Down, A), r.update(down, 0))
        assertEquals(emptyList<PadButton>(), r.update(down, 200)) // まだくり返さない
        assertEquals(listOf(Down), r.update(down, 300)) // A はくり返さない
        assertEquals(emptyList<PadButton>(), r.update(down, 350))
        assertEquals(listOf(Down), r.update(down, 400))
        assertEquals(emptyList<PadButton>(), r.update(PadState.NONE, 410))
        assertEquals(listOf(Down), r.update(PadState(setOf(Down)), 420)) // 離して押し直し
    }

    @Test
    fun mapping() {
        assertEquals(KeyEvent.VK_DOWN, PadKeys.map(Down, PadMode.Xmb)!!.code)
        assertEquals(KeyEvent.VK_RIGHT, PadKeys.map(RB, PadMode.Xmb)!!.code)
        assertEquals(KeyEvent.VK_TAB, PadKeys.map(Y, PadMode.Xmb)!!.code)
        assertEquals(KeyEvent.VK_ENTER, PadKeys.map(A, PadMode.Xmb)!!.code)
        assertEquals(KeyEvent.VK_ESCAPE, PadKeys.map(B, PadMode.Form)!!.code)
        // パネルでは上下でボタンを移る
        assertEquals(com.emustation.emusync.desktop.platform.KeyStroke(KeyEvent.VK_TAB, shift = true), PadKeys.map(Up, PadMode.Form))
        assertEquals(com.emustation.emusync.desktop.platform.KeyStroke(KeyEvent.VK_TAB), PadKeys.map(Down, PadMode.Form))
        assertNull(PadKeys.map(Y, PadMode.Form))
        // B で決定（PS3 の日本の並び）
        assertEquals(KeyEvent.VK_ENTER, PadKeys.map(B, PadMode.Xmb, confirmIsB = true)!!.code)
        assertEquals(KeyEvent.VK_ESCAPE, PadKeys.map(A, PadMode.Xmb, confirmIsB = true)!!.code)
    }

    @Test
    fun playStationAndSwitch_labelsAndTypes() {
        val ps = com.emustation.emusync.desktop.platform.PadStyle.PlayStation
        val ns = com.emustation.emusync.desktop.platform.PadStyle.Nintendo
        assertEquals("×", Gamepad.label(A, ps))
        assertEquals("○", Gamepad.label(B, ps))
        assertEquals("△", Gamepad.label(Y, ps))
        assertEquals("L1", Gamepad.label(LB, ps))
        assertEquals("B", Gamepad.label(A, ns)) // Switch は下が B
        assertEquals("A", Gamepad.label(B, ns))
        assertEquals("A", Gamepad.label(A))
        val sdl = com.emustation.emusync.desktop.platform.SdlSource
        assertEquals(ps, sdl.styleOf(org.lwjgl.sdl.SDLGamepad.SDL_GAMEPAD_TYPE_PS4))
        assertEquals(ps, sdl.styleOf(org.lwjgl.sdl.SDLGamepad.SDL_GAMEPAD_TYPE_PS5))
        assertEquals(ns, sdl.styleOf(org.lwjgl.sdl.SDLGamepad.SDL_GAMEPAD_TYPE_NINTENDO_SWITCH_PRO))
        assertEquals(com.emustation.emusync.desktop.platform.PadStyle.Xbox, sdl.styleOf(org.lwjgl.sdl.SDLGamepad.SDL_GAMEPAD_TYPE_XBOXONE))
        // 12 のボタンがすべて割り当てられている
        assertEquals(PadButton.entries.toSet(), sdl.BUTTONS.map { it.second }.toSet())
        // スティック：SDL は下が +（読むところで直す）。ここでは上が + の値で確かめる
        assertEquals(Up, PadState.stick(0, 30000))
        assertEquals(null, PadState.stick(1000, -1000))
    }

    /** SDL（ネイティブのライブラリ）が読み込めて、コントローラーがなくても動くこと */
    @Test
    fun sdl_opensAndPollsWithoutControllers() {
        var result: Result<PadState>? = null
        val t = Thread {
            result = runCatching {
                val src = com.emustation.emusync.desktop.platform.SdlSource.open() ?: error("SDL を開けない")
                repeat(3) { src.poll() }
                src.poll()
            }
        }
        t.start(); t.join(20_000)
        val r = result ?: error("SDL の初期化が終わらない")
        assertEquals(emptySet<PadButton>(), r.getOrThrow().pressed)
    }

    // ---------------------------------------------------------------- コントローラーだけで操作する
    private val server = FakeServer()
    private val release = CompletableDeferred<Unit>()
    private lateinit var app: AppController
    private lateinit var saves: File

    private fun setUp() {
        saves = tmp.newFolder("memcards")
        val exe = File(tmp.root, "pcsx2-qt.exe").apply { writeText("") }
        val store = ConfigStore(File(tmp.root, "config.json"))
        store.save(
            AppConfig(serverUrl = "https://x/emusync").withToken("t")
                .withSystem(SystemConfig("ps2", exe.path, "\"{rom}\"", saves.path))
                .withSystem(SystemConfig("psp", exe.path, "\"{rom}\"", tmp.newFolder("psp").path))
                .withGame(Game(id = "p3", title = "ペルソナ3", target = "ps2", rom = "p3.iso", lastPlayedAt = 2))
                .withGame(Game(id = "dq8", title = "ドラゴンクエストVIII", target = "ps2", rom = "dq8.iso", lastPlayedAt = 1))
                .withGame(Game(id = "mh", title = "モンハン", target = "psp", rom = "mh.iso")),
        )
        app = AppController(
            store, tmp.newFolder("data"), CoroutineScope(Dispatchers.Default),
            syncFactory = { c, r ->
                SyncService(c, File(tmp.root, "data"), r, apiFactory = {
                    object : RemoteApi by server.client("win"), PlayLocks by server.locks("win") {}
                }, mutexes = TargetMutexes())
            },
            runAndWait = { _, started -> started(); release.await(); 60L },
            metadata = offlineMetadata(File(tmp.root, "meta")),
        )
        compose.setContent { EmusyncTheme { Box(Modifier.fillMaxSize()) { XmbApp(app, initialCategory = 1, now = { 1_763_800_000_000L }, wavePhase = 0.3f) } } }
        compose.waitForIdle()
    }

    private fun shot(name: String) {
        val dir = File(System.getProperty("user.dir"), "build/screenshots").apply { mkdirs() }
        javax.imageio.ImageIO.write(compose.onRoot().captureToImage().toAwtImage(), "png", File(dir, "$name.png"))
    }

    /** ボタンを押す：本物と同じく、今の画面の状態（Gamepad.mode）でキーに直して送る */
    private fun pad(vararg buttons: PadButton) {
        for (b in buttons) {
            val k = PadKeys.map(b, Gamepad.mode, app.config.value.confirmButton == "B") ?: continue
            val key = when (k.code) {
                KeyEvent.VK_UP -> Key.DirectionUp
                KeyEvent.VK_DOWN -> Key.DirectionDown
                KeyEvent.VK_LEFT -> Key.DirectionLeft
                KeyEvent.VK_RIGHT -> Key.DirectionRight
                KeyEvent.VK_ENTER -> Key.Enter
                KeyEvent.VK_ESCAPE -> Key.Escape
                KeyEvent.VK_TAB -> Key.Tab
                else -> error("想定していないキー ${k.code}")
            }
            // 本物（AwtKeys）と同じく「コントローラーから送っている」印を付けて送る
            Gamepad.dispatching = true
            try {
                compose.onRoot().performKeyInput { if (k.shift) withKeyDown(Key.ShiftLeft) { pressKey(key) } else pressKey(key) }
            } finally {
                Gamepad.dispatching = false
            }
            compose.waitForIdle()
        }
    }

    private fun focusedHasText(t: String) =
        compose.onAllNodes(isFocused() and SemanticsMatcher("text") { n ->
            n.config.toString().contains(t) || n.children.any { it.config.toString().contains(t) }
        }).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun play_withController_thenOk() {
        setUp()
        assertEquals(PadMode.Xmb, Gamepad.mode)
        pad(Down, Up) // ドラクエ → ペルソナ
        pad(A) // プレイ
        compose.waitUntil(10_000) { app.play.phase.value is PlayPhase.Playing }
        compose.waitForIdle()
        assertEquals(PadMode.Form, Gamepad.mode)
        release.complete(Unit)
        compose.waitUntil(10_000) { app.play.phase.value is PlayPhase.Finished }
        compose.waitForIdle()
        pad(A) // OK（最初から選んである）
        assertNull(app.play.phase.value)
        assertEquals(60L, app.config.value.games.first { it.id == "p3" }.playSeconds)
        assertEquals(PadMode.Xmb, Gamepad.mode)
    }

    @Test
    fun conflict_B_cancelsAndReleasesLock() {
        setUp()
        server.seed("ps2/Mcd001.ps2", "server".toByteArray(), 1)
        File(saves, "Mcd001.ps2").writeText("local")
        pad(A)
        compose.waitUntil(10_000) { app.play.phase.value is PlayPhase.Conflicts }
        compose.waitForIdle()
        assertEquals("win", server.lockHolder("ps2"))
        Gamepad.setStyle(com.emustation.emusync.desktop.platform.PadStyle.PlayStation)
        Gamepad.markPad()
        compose.waitForIdle()
        shot("desktop-pad-playstation-dialog")
        pad(B) // やめる
        compose.waitUntil(10_000) { app.play.phase.value == null && server.lockHolder("ps2") == null }
    }

    @Test
    fun columnsOptionsAndPanels_withController() {
        setUp()
        Gamepad.markPad() // 本物ではコントローラーのボタンを送るときに印を付ける（画面の下の説明がコントローラー用になる）
        pad(RB) // PSP の列
        compose.onNode(hasText("モンハン")).assertExists()
        pad(LB, Y) // PS2 に戻ってオプション
        compose.onNode(hasText("情報とカバーを取得し直す")).assertExists()
        shot("desktop-pad-options")
        pad(B) // オプションを閉じる
        compose.onNode(hasText("情報とカバーを取得し直す")).assertDoesNotExist()

        // 設定 → コントローラー（サーバー・エミュ（機種の数だけ）・ゲームの情報 の次）→ 「B で決定」を選ぶ
        pad(Left)
        repeat(com.emustation.emusync.desktop.domain.Presets.all.size + 2) { pad(Down) }
        pad(A)
        val found = compose.onAllNodes(hasText("決定のボタン")).fetchSemanticsNodes().isNotEmpty()
        assertTrue("コントローラーのパネルを開けない", found)
        shot("desktop-controller-panel")
        assertEquals(PadMode.Form, Gamepad.mode)
        var chosen = false
        repeat(15) {
            if (!chosen) {
                pad(Down)
                if (focusedHasText("右のボタンで決定")) { shot("desktop-controller-focus"); pad(A); chosen = true }
            }
        }
        assertTrue("「右のボタンで決定」を選べない", chosen)
        assertEquals("B", app.config.value.confirmButton)
        // 決定と戻るが入れ替わったので、A で閉じる
        pad(A)
        compose.onNode(hasText("決定のボタン")).assertDoesNotExist()
        assertEquals(PadMode.Xmb, Gamepad.mode)
    }
}
