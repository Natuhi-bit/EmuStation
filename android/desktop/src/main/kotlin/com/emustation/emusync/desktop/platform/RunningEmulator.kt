package com.emustation.emusync.desktop.platform

import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import java.util.concurrent.ConcurrentHashMap

/**
 * 今起動しているエミュ（[Launcher.runAndWait] が登録する）。
 * Alt+F4・「エミュを終了」で、子プロセスまで含めて終わらせる（PCSX2・RPCS3 は Alt+F4 でゲームの画面だけ閉じて、
 * 本体が残ることがある。残ると「プレイ中」のままで、セーブの送信が始まらない）
 */
object RunningEmulator {
    @Volatile
    private var root: ProcessHandle? = null
    private val seen: MutableSet<ProcessHandle> = ConcurrentHashMap.newKeySet()

    /** 窓を閉じる方法（テストでは差し替える） */
    fun interface WindowCloser {
        /** [pids] のプロセスの窓に「閉じて」と頼む。頼んだ窓の数 */
        fun closeWindowsOf(pids: Set<Long>): Int
    }

    fun started(p: ProcessHandle) {
        seen.clear()
        root = p
    }

    /** 起動中に見つけた子孫（エミュが別のプロセスを起動して、自分は先に終わる場合もあるので覚えておく） */
    fun track(h: ProcessHandle) { seen += h }

    fun finished(p: ProcessHandle) {
        if (root == p) {
            root = null
            seen.clear()
        }
    }

    /** 今生きている、エミュのプロセス（本体・子孫） */
    fun alive(): List<ProcessHandle> {
        val r = root ?: return emptyList()
        val all = LinkedHashSet<ProcessHandle>()
        all += r
        runCatching { r.descendants().forEach { all += it } }
        all += seen
        seen.toList().forEach { h -> runCatching { h.descendants().forEach { all += it } } }
        return all.filter { it.isAlive }
    }

    fun isRunning(): Boolean = root != null && alive().isNotEmpty()

    fun pids(): Set<Long> = alive().map { it.pid() }.toSet()

    /**
     * 終わらせる：まず窓を閉じる（Windows は WM_CLOSE、ほかは SIGTERM。いつもの終了と同じ）。
     * [graceMs] たっても残っていれば強制終了する。終わったら true
     */
    fun close(graceMs: Long = 8_000, windows: WindowCloser = defaultCloser): Boolean {
        val procs = alive()
        if (procs.isEmpty()) return true
        val closed = if (isWindows) runCatching { windows.closeWindowsOf(procs.map { it.pid() }.toSet()) }.getOrDefault(0) else 0
        if (!isWindows || closed == 0) procs.forEach { runCatching { it.destroy() } }
        val until = System.currentTimeMillis() + graceMs
        while (System.currentTimeMillis() < until) {
            if (alive().isEmpty()) return true
            Thread.sleep(100)
        }
        // 子から先に
        alive().sortedByDescending { if (it == root) 0 else 1 }.forEach { runCatching { it.destroyForcibly() } }
        val end = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < end) {
            if (alive().isEmpty()) return true
            Thread.sleep(100)
        }
        return alive().isEmpty()
    }

    /** Windows：そのプロセスの、見えている窓すべてに WM_CLOSE を送る */
    val defaultCloser = WindowCloser { pids ->
        if (!isWindows) return@WindowCloser 0
        val targets = mutableListOf<WinDef.HWND>()
        User32.INSTANCE.EnumWindows({ hwnd, _: Pointer? ->
            val pid = IntByReference()
            User32.INSTANCE.GetWindowThreadProcessId(hwnd, pid)
            if (pid.value.toLong() in pids && User32.INSTANCE.IsWindowVisible(hwnd)) targets += hwnd
            true
        }, null)
        targets.forEach { User32.INSTANCE.PostMessage(it, WinUser.WM_CLOSE, null, null) }
        targets.size
    }

    /** Windows：前に出ている窓のプロセス */
    fun foregroundPid(): Long? {
        if (!isWindows) return null
        val hwnd = User32.INSTANCE.GetForegroundWindow() ?: return null
        val pid = IntByReference()
        User32.INSTANCE.GetWindowThreadProcessId(hwnd, pid)
        return pid.value.toLong().takeIf { it > 0 }
    }
}

/**
 * Alt+F4 を見張る（押した瞬間に 1 回）。エミュの窓が前に出ているときだけ反応する。
 * Alt+F4 でエミュの窓がすぐ閉じて、前の窓が変わってしまうこともあるので、1 つ前に見た前の窓でも判定する
 */
class AltF4Detector {
    private var f4Was = false
    private var lastFg: Long? = null

    fun update(alt: Boolean, f4: Boolean, foregroundPid: Long?, emulatorPids: Set<Long>): Boolean {
        val pressed = f4 && !f4Was
        f4Was = f4
        val prevFg = lastFg
        lastFg = foregroundPid
        if (!pressed || !alt) return false
        return (foregroundPid != null && foregroundPid in emulatorPids) || (prevFg != null && prevFg in emulatorPids)
    }
}
