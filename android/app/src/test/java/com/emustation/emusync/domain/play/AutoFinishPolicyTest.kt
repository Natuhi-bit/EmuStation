package com.emustation.emusync.domain.play

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoFinishPolicyTest {
    private val sig = FolderSignature(2, 100, 1_000)
    private val changed = FolderSignature(2, 100, 2_000)
    private val p = AutoFinishPolicy(awayMs = 120_000, quietMs = 30_000)
    private val s = 1_000L // 秒

    @Test
    fun whilePlaying_neverFinishes() {
        for (t in 0..600 step 5) assertFalse(p.step(t * s, emulatorInForeground = true, screenOn = true, sig))
        assertNull(p.remainingMs(600 * s))
    }

    @Test
    fun closedEmulator_finishesAfterAwayTime() {
        p.step(0, true, true, sig)
        assertFalse(p.step(10 * s, false, true, sig)) // 閉じた
        assertEquals(120 * s, p.remainingMs(10 * s))
        assertFalse(p.step(129 * s, false, true, sig))
        assertTrue(p.step(130 * s, false, true, sig))
    }

    @Test
    fun returningWithinAwayTime_resetsTimer() {
        p.step(0, false, true, sig)
        assertFalse(p.step(100 * s, false, true, sig)) // LINE を見ている
        assertFalse(p.step(110 * s, true, true, sig)) // エミュに戻った
        assertFalse(p.step(200 * s, false, true, sig))
        assertFalse(p.step(319 * s, false, true, sig))
        assertTrue(p.step(320 * s, false, true, sig))
    }

    @Test
    fun screenOff_doesNotCount() {
        p.step(0, true, true, sig)
        // 遊んでいる途中でスリープ。エミュは前面から外れるが、画面が消えているので数えない
        for (t in 10..3600 step 10) assertFalse(p.step(t * s, false, screenOn = false, sig))
        assertFalse(p.step(3610 * s, true, true, sig)) // 画面を点けたら、またエミュ
    }

    @Test
    fun waitsUntilSavesStopChanging() {
        p.step(0, false, true, sig)
        assertFalse(p.step(119 * s, false, true, sig))
        // エミュを閉じた直後にセーブを書き込んでいる
        assertFalse(p.step(120 * s, false, true, changed))
        assertEquals(30 * s, p.remainingMs(120 * s))
        assertFalse(p.step(149 * s, false, true, changed))
        assertTrue(p.step(150 * s, false, true, changed))
    }
}
