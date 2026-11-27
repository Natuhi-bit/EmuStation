package com.emustation.emusync.desktop.platform

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary

/**
 * ウィンドウを本当に前に出して、キーボード・コントローラーの入力を受け取れる状態にする。
 * Windows は、よそのプロセス（Sunshine から起動した 2 つ目の EmuStation・閉じたエミュなど）がきっかけのときは
 * 「前に出して」を断り、タスクバーを点滅させるだけにする（画面には出ても、入力は前の窓に行ったまま）。
 * 前に出ている窓の入力の流れに一時的に相乗りして（AttachThreadInput）から前に出す
 */
object WindowActivator {
    @Suppress("FunctionName")
    private interface U32 : StdCallLibrary {
        fun GetForegroundWindow(): Pointer?
        fun GetWindowThreadProcessId(hWnd: Pointer?, lpdwProcessId: Pointer?): Int
        fun AttachThreadInput(idAttach: Int, idAttachTo: Int, fAttach: Boolean): Boolean
        fun BringWindowToTop(hWnd: Pointer?): Boolean
        fun SetForegroundWindow(hWnd: Pointer?): Boolean
        fun ShowWindow(hWnd: Pointer?, nCmdShow: Int): Boolean
        fun IsIconic(hWnd: Pointer?): Boolean
        fun AllowSetForegroundWindow(dwProcessId: Int): Boolean
    }

    private interface K32 : StdCallLibrary {
        fun GetCurrentThreadId(): Int
    }

    private val u32: U32? by lazy { if (isWindows) runCatching { Native.load("user32", U32::class.java) }.getOrNull() else null }
    private val k32: K32? by lazy { if (isWindows) runCatching { Native.load("kernel32", K32::class.java) }.getOrNull() else null }

    /** ほかのプロセス（1 つ目の EmuStation）が前に出ることを許す（2 つ目の EmuStation が、知らせる前に呼ぶ） */
    fun allowOthers() {
        runCatching { u32?.AllowSetForegroundWindow(-1) } // ASFW_ANY
    }

    /** 前に出して入力を受け取れるようにする。Swing のスレッドで呼ぶ */
    fun activate(window: java.awt.Window) {
        window.isVisible = true
        if (window is java.awt.Frame && window.extendedState and java.awt.Frame.ICONIFIED != 0) {
            window.extendedState = window.extendedState and java.awt.Frame.ICONIFIED.inv()
        }
        val u = u32
        val k = k32
        if (u != null && k != null) {
            runCatching {
                val hwnd = Native.getWindowPointer(window)
                val fg = u.GetForegroundWindow()
                val me = k.GetCurrentThreadId()
                val fgThread = if (fg != null) u.GetWindowThreadProcessId(fg, null) else 0
                val attached = fgThread != 0 && fgThread != me && u.AttachThreadInput(me, fgThread, true)
                try {
                    if (u.IsIconic(hwnd)) u.ShowWindow(hwnd, 9) // SW_RESTORE
                    u.BringWindowToTop(hwnd)
                    u.SetForegroundWindow(hwnd)
                } finally {
                    if (attached) u.AttachThreadInput(me, fgThread, false)
                }
            }
        }
        // Java 側でも（Windows 以外・上が効かなかったとき）
        window.toFront()
        window.requestFocus()
    }
}
