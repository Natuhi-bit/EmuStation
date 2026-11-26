package com.emustation.emusync.desktop

import com.emustation.emusync.desktop.platform.AltF4Detector
import com.emustation.emusync.desktop.platform.Launcher
import com.emustation.emusync.desktop.platform.RunningEmulator
import com.emustation.emusync.desktop.platform.isWindows
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test

/** Alt+F4・「エミュを終了」で、エミュを子プロセスまで含めて終わらせる */
class RunningEmulatorTest {
    @Test
    fun altF4_onlyWhenEmulatorIsInFront() {
        val emu = setOf(100L, 101L)
        val d = AltF4Detector()
        assertFalse(d.update(alt = true, f4 = false, foregroundPid = 100, emulatorPids = emu))
        assertTrue(d.update(alt = true, f4 = true, foregroundPid = 100, emulatorPids = emu))     // 押した瞬間
        assertFalse(d.update(alt = true, f4 = true, foregroundPid = 100, emulatorPids = emu))    // 押し続けても 1 回だけ
        assertFalse(d.update(alt = false, f4 = false, foregroundPid = 100, emulatorPids = emu))
        assertFalse(d.update(alt = false, f4 = true, foregroundPid = 100, emulatorPids = emu))   // F4 だけ
        assertFalse(d.update(alt = false, f4 = false, foregroundPid = 7, emulatorPids = emu))
        assertFalse(d.update(alt = true, f4 = true, foregroundPid = 7, emulatorPids = emu))      // ほかのアプリでは反応しない
        assertFalse(d.update(alt = false, f4 = false, foregroundPid = 101, emulatorPids = emu))
        // エミュの窓がすぐ閉じて、前の窓が変わっていても（1 つ前がエミュなら）反応する
        assertTrue(d.update(alt = true, f4 = true, foregroundPid = 7, emulatorPids = emu))
    }

    /** エミュの代わりのプロセスを起動して、[body] の間に終わらせる */
    private fun launchAndClose(script: String, graceMs: Long, windows: RunningEmulator.WindowCloser = RunningEmulator.WindowCloser { 0 }): Pair<Boolean, Long> = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cmd = if (isWindows) listOf("cmd", "/c", script) else listOf("sh", "-c", script)
        val run = async(Dispatchers.IO) { Launcher.runAndWait(cmd) { started.complete(Unit) } }
        withTimeout(10_000) { started.await() }
        // 子プロセスが起動するまで待つ
        withTimeout(10_000) { while (RunningEmulator.pids().size < 2) kotlinx.coroutines.delay(100) }
        assertTrue(RunningEmulator.isRunning())
        val t0 = System.currentTimeMillis()
        val ok = RunningEmulator.close(graceMs = graceMs, windows = windows)
        val took = System.currentTimeMillis() - t0
        withTimeout(10_000) { run.await() }   // 終わったら、プレイの処理（同期）へ進む
        assertFalse(RunningEmulator.isRunning())
        ok to took
    }

    @Test
    fun close_politeEmulator_endsWithoutForce() {
        assumeFalse(isWindows)
        val (ok, took) = launchAndClose("sleep 100 & wait", graceMs = 5_000)
        assertTrue(ok)
        assertTrue("頼んだだけで終わるはず（$took ms）", took < 4_000)
    }

    @Test
    fun close_stubbornEmulator_isForcedAfterGrace() {
        assumeFalse(isWindows)
        // 終了の頼み（SIGTERM）を無視し続けるエミュ
        val (ok, took) = launchAndClose("trap '' TERM; while true; do sleep 0.2; done", graceMs = 1_000)
        assertTrue(ok)
        assertTrue("待ってから強制終了するはず（$took ms）", took >= 1_000)
    }

    @Test
    fun close_onWindows_endsEmulatorAndChildren() {
        org.junit.Assume.assumeTrue(isWindows)
        // 窓のないエミュの代わり（cmd → ping）。窓がなければ、そのまま終わらせる
        val (ok, _) = launchAndClose("ping -n 100 127.0.0.1 > NUL", graceMs = 3_000, windows = RunningEmulator.defaultCloser)
        assertTrue(ok)
        // 窓を探す処理そのものが動く（無いプロセスには何もしない）
        assertEquals(0, RunningEmulator.defaultCloser.closeWindowsOf(setOf(-1L)))
    }

    @Test
    fun close_whenNothingRunning_isNoop() {
        assertFalse(RunningEmulator.isRunning())
        assertEquals(true, RunningEmulator.close(graceMs = 100, windows = { 0 }))
    }
}
