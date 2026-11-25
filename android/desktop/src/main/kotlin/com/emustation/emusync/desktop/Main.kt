package com.emustation.emusync.desktop

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
import com.emustation.emusync.desktop.platform.AwtKeys
import com.emustation.emusync.desktop.platform.Gamepad
import com.emustation.emusync.desktop.platform.PadKeys
import com.emustation.emusync.desktop.platform.PadRepeater
import com.emustation.emusync.desktop.platform.SingleInstance
import com.emustation.emusync.desktop.platform.XInputSource
import com.emustation.emusync.desktop.ui.EmusyncTheme
import com.emustation.emusync.desktop.ui.XmbApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import javax.swing.SwingUtilities
import org.jetbrains.skia.Image

/**
 * emusync（Windows 版）。タスクトレイに常駐し、ウィンドウを閉じてもトレイに残る。
 * `--minimized` で起動すると（Windows の起動時）、ウィンドウを出さずにトレイに入る。
 */
fun main(args: Array<String>) {
    val showRequests = MutableStateFlow(0)
    val single = SingleInstance()
    if (!single.acquire { showRequests.value++ }) return // 2つ目は、1つ目を前に出して終わる

    val app = AppController()
    app.refresh()
    app.startPeriodic()
    // まだ情報とカバーを調べていないゲーム（この機能より前に追加したものなど）を調べる
    app.fetchInfo()
    val startHidden = "--minimized" in args && app.config.value.startMinimized
    val icon = BitmapPainter(
        Image.makeFromEncoded(Thread.currentThread().contextClassLoader.getResourceAsStream("icon.png")!!.readBytes()).toComposeImageBitmap(),
    )

    application {
        var visible by remember { mutableStateOf(!startHidden) }
        val tray = rememberTrayState()
        val windowState = rememberWindowState(width = 1280.dp, height = 760.dp)
        val reopen by showRequests.collectAsState()
        LaunchedEffect(reopen) { if (reopen > 0) visible = true }
        LaunchedEffect(Unit) {
            app.notices.collect { n ->
                tray.sendNotification(Notification(n.title, n.text, if (n.error) Notification.Type.Warning else Notification.Type.Info))
            }
        }

        Tray(
            icon = icon,
            state = tray,
            tooltip = "emusync",
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
            title = "emusync",
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
            LaunchedEffect(visible, reopen) { if (visible) window.toFront() }
            // コントローラー：押されたボタンをキーに直して、このウィンドウに送る（ほかのアプリ（エミュ）が前のときは送らない）
            LaunchedEffect(Unit) {
                val source = XInputSource.open() ?: return@LaunchedEffect
                withContext(Dispatchers.IO) {
                    val repeater = PadRepeater()
                    while (isActive) {
                        val presses = repeater.update(source.poll(), System.currentTimeMillis())
                        val c = app.config.value
                        if (presses.isNotEmpty() && c.gamepad) {
                            SwingUtilities.invokeLater {
                                if (!window.isActive) return@invokeLater
                                Gamepad.markPad()
                                presses.forEach { b -> PadKeys.map(b, Gamepad.mode, c.confirmButton == "B")?.let { AwtKeys.send(window, it) } }
                            }
                        }
                        delay(16)
                    }
                }
            }
            // ゲームを終えたら（エミュを閉じたら）、このウィンドウを前に出す（コントローラーでそのまま操作できるように）
            LaunchedEffect(Unit) {
                var prev: PlayPhase? = null
                app.play.phase.collect { p ->
                    if (prev is PlayPhase.Playing && p !is PlayPhase.Playing && visible) {
                        window.isAlwaysOnTop = true
                        window.toFront()
                        window.requestFocus()
                        window.isAlwaysOnTop = false
                    }
                    prev = p
                }
            }
            EmusyncTheme { XmbApp(app) }
        }
    }
}
