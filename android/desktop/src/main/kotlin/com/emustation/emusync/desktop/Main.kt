package com.emustation.emusync.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import com.emustation.emusync.desktop.domain.AppController
import com.emustation.emusync.desktop.domain.PlayPhase
import com.emustation.emusync.desktop.platform.AltF4Detector
import com.emustation.emusync.desktop.platform.AwtKeys
import com.emustation.emusync.desktop.platform.Gamepad
import com.emustation.emusync.desktop.platform.PadKeys
import com.emustation.emusync.desktop.platform.PadRepeater
import com.emustation.emusync.desktop.platform.PadSource
import com.emustation.emusync.desktop.platform.PadState
import com.emustation.emusync.desktop.platform.RemotePlay
import com.emustation.emusync.desktop.platform.RunningEmulator
import com.emustation.emusync.desktop.platform.SdlSource
import com.emustation.emusync.desktop.platform.WindowActivator
import com.emustation.emusync.desktop.platform.SingleInstance
import com.emustation.emusync.desktop.platform.XInputSource
import com.emustation.emusync.desktop.platform.isWindows
import com.emustation.emusync.desktop.ui.EmusyncTheme
import com.emustation.emusync.desktop.ui.XmbApp
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlin.concurrent.thread
import org.jetbrains.skia.Image

/**
 * EmuStation（Windows 版）。タスクトレイに常駐し、ウィンドウを閉じてもトレイに残る。
 * `--minimized` で起動すると（Windows の起動時）、ウィンドウを出さずにトレイに入る。
 */
fun main(args: Array<String>) {
    val showRequests = MutableStateFlow(0)
    // リモートプレイ（Sunshine から --remote で起動）：全画面で前に出す。
    // Moonlight で「アプリを終了」すると Sunshine が --remote-end で起動する：全画面を解いて最小化する
    val remote = RemotePlay.ARG in args
    val fullscreenRequests = MutableStateFlow(if (remote) 1 else 0)
    val remoteEndRequests = MutableStateFlow(0)
    // リモートプレイ中か（このあいだは、エミュで遊んでいなければ、コントローラーで EmuStation を前に出して操作できる）
    val remoteSession = java.util.concurrent.atomic.AtomicBoolean(remote)
    val single = SingleInstance()
    if (RemotePlay.END_ARG in args) {
        // 終わりの知らせだけ。EmuStation が動いていなければ何もしない（ここで起動しない）
        single.send(SingleInstance.Request.RemoteEnd)
        return
    }
    // 2つ目は、1つ目に伝えて終わる
    val first = single.acquire(if (remote) SingleInstance.Request.Remote else SingleInstance.Request.Show) { r ->
        when (r) {
            SingleInstance.Request.Show -> showRequests.value++
            SingleInstance.Request.Remote -> { remoteSession.set(true); fullscreenRequests.value++; showRequests.value++ }
            SingleInstance.Request.RemoteEnd -> { remoteSession.set(false); remoteEndRequests.value++ }
        }
    }
    if (!first) return

    val app = AppController()
    // 操作音（利用者が選んだ音のファイル）
    com.emustation.emusync.desktop.platform.Sounds.load(java.io.File(app.root, "sounds"))
    app.refresh()
    app.startPeriodic()
    // まだ情報とカバーを調べていないゲーム（この機能より前に追加したものなど）を調べる
    app.fetchInfo()
    val startHidden = "--minimized" in args && app.config.value.startMinimized
    val icon = BitmapPainter(
        Image.makeFromEncoded(Thread.currentThread().contextClassLoader.getResourceAsStream("icon.png")!!.readBytes()).toComposeImageBitmap(),
    )

    // コントローラー：専用のスレッドで読み（SDL は同じスレッドから使う決まり）、押されたボタンをキーに直してウィンドウに送る。
    // SDL（PlayStation・Xbox・Switch Pro など）が使えなければ XInput（Xbox・ROG Ally）。ウィンドウが前のときだけ送る（エミュで遊んでいる間は送らない）
    val padTarget = AtomicReference<java.awt.Window?>(null)
    thread(isDaemon = true, name = "gamepad") {
        val source: PadSource = SdlSource.open() ?: XInputSource.open() ?: return@thread
        val repeater = PadRepeater()
        while (true) {
            val state = runCatching { source.poll() }.getOrDefault(PadState.NONE)
            val presses = repeater.update(state, System.currentTimeMillis())
            val c = app.config.value
            if (presses.isNotEmpty() && c.gamepad) {
                SwingUtilities.invokeLater {
                    val w = padTarget.get() ?: return@invokeLater
                    if (!w.isActive) {
                        // リモートプレイ中（Moonlight で EmuStation を映している）で、エミュで遊んでいなければ、
                        // EmuStation を操作しているはずなので前に出して受け取る（前に出せていないと入力が届かないため）
                        if (remoteSession.get() && w.isVisible && !RunningEmulator.isRunning()) WindowActivator.activate(w) else return@invokeLater
                    }
                    state.style?.let(Gamepad::setStyle)
                    Gamepad.markPad()
                    presses.forEach { b -> PadKeys.map(b, Gamepad.mode, c.confirmButton == "B")?.let { AwtKeys.send(w, it) } }
                }
            }
            Thread.sleep(16)
        }
    }

    // Alt+F4：エミュの窓が前のときに押したら、エミュ全体（子プロセスも）を終わらせる
    if (isWindows) thread(isDaemon = true, name = "alt-f4") {
        val detector = AltF4Detector()
        val user32 = com.sun.jna.platform.win32.User32.INSTANCE
        fun down(vk: Int) = runCatching { user32.GetAsyncKeyState(vk).toInt() and 0x8000 != 0 }.getOrDefault(false)
        var pids = emptySet<Long>()
        var pidsAt = 0L
        while (true) {
            if (!RunningEmulator.isRunning()) {
                detector.update(alt = false, f4 = false, foregroundPid = null, emulatorPids = emptySet())
                Thread.sleep(500)
                continue
            }
            val now = System.currentTimeMillis()
            if (now - pidsAt > 1_000) { pids = RunningEmulator.pids(); pidsAt = now }
            if (detector.update(down(0x12), down(0x73), RunningEmulator.foregroundPid(), pids)) {
                thread(isDaemon = true, name = "close-emulator") { RunningEmulator.close() }
            }
            Thread.sleep(30)
        }
    }

    application {
        var visible by remember { mutableStateOf(!startHidden) }
        val tray = rememberTrayState()
        val windowState = rememberWindowState(width = 1280.dp, height = 760.dp)
        val reopen by showRequests.collectAsState()
        LaunchedEffect(reopen) { if (reopen > 0) visible = true }
        // リモートプレイ：全画面にして前に出す（Moonlight の画面いっぱいに XMB を出す）
        val fullscreen by fullscreenRequests.collectAsState()
        val remoteEnd by remoteEndRequests.collectAsState()
        LaunchedEffect(remoteEnd) {
            if (remoteEnd > 0) {
                windowState.placement = WindowPlacement.Floating
                windowState.isMinimized = true
            }
        }
        LaunchedEffect(fullscreen) {
            if (fullscreen > 0) {
                visible = true
                windowState.isMinimized = false
                windowState.placement = WindowPlacement.Fullscreen
            }
        }
        LaunchedEffect(Unit) {
            app.notices.collect { n ->
                tray.sendNotification(Notification(n.title, n.text, if (n.error) Notification.Type.Warning else Notification.Type.Info))
            }
        }

        Tray(
            icon = icon,
            state = tray,
            tooltip = "EmuStation",
            onAction = { visible = true },
            menu = {
                Item("開く", onClick = { visible = true })
                Item("すべて同期", onClick = { app.syncAll() })
                Separator()
                Item("終了", onClick = { single.release(); exitApplication() })
            },
        )

        Window(
            onCloseRequest = { visible = false }, // 閉じてもトレイに残る
            visible = visible,
            title = "EmuStation",
            icon = icon,
            state = windowState,
            onPreviewKeyEvent = { e ->
                // F11 で全画面（Steam の Big Picture のように）
                if (e.key == Key.F11 && e.type == KeyEventType.KeyDown) {
                    windowState.placement = if (windowState.placement == WindowPlacement.Fullscreen) WindowPlacement.Floating else WindowPlacement.Fullscreen
                    true
                } else false
            },
        ) {
            LaunchedEffect(visible, reopen) {
                if (visible) {
                    // ほかの窓（Sunshine から起動したときなど）の後ろに隠れず、入力も受け取れるように前に出す
                    WindowActivator.activate(window)
                }
            }
            // コントローラーのボタンを送る先（このウィンドウ）
            DisposableEffect(window) {
                padTarget.set(window)
                onDispose { padTarget.compareAndSet(window, null) }
            }
            // ゲームを終えたら（エミュを閉じたら）、このウィンドウを前に出す（コントローラーでそのまま操作できるように）
            LaunchedEffect(Unit) {
                var prev: PlayPhase? = null
                app.play.phase.collect { p ->
                    if (prev is PlayPhase.Playing && p !is PlayPhase.Playing && visible) {
                        WindowActivator.activate(window)
                    }
                    prev = p
                }
            }
            EmusyncTheme { XmbApp(app) }
        }
    }
}
