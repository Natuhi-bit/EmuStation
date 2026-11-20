package com.emustation.emusync.domain.play

import com.emustation.emusync.data.db.PlaySessionEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayWatcherTest {
    private var now = 0L
    private val sessions = mutableMapOf<String, PlaySessionEntity>()
    private val running = mutableSetOf<String>()
    private val finished = mutableListOf<String>()
    private val resumed = mutableListOf<String>()
    private var sig = FolderSignature(1, 10, 1)
    private var lockedBy: String? = null

    private val w = PlayWatcher(
        awayMs = { 120_000 },
        sessions = { sessions.values.toList() },
        packageOf = { mapOf("ps2" to "xyz.armsx2", "psp" to "org.ppsspp.ppsspp")[it] },
        signatureOf = { sig },
        finishRunning = { it in running },
        startFinish = { finished += it; running += it },
        resume = { t ->
            resumed += t
            val s = PlaySessionEntity(t, now, lockedBy == null)
            sessions[t] = s
            ResumeResult(s, lockedBy)
        },
        clock = { now },
        graceMs = 600_000,
    )

    private fun tick(fg: String?, screen: Boolean = true) = runBlocking { w.tick(fg, screen) }

    /** 送信のジョブが終わった（プレイセッションが消えた） */
    private fun jobDone(target: String) {
        running -= target
        sessions.remove(target)
    }

    private fun play(target: String) {
        sessions[target] = PlaySessionEntity(target, now, true)
    }

    @Test
    fun closesEmulator_autoFinish_thenGrace_thenIdle() {
        play("ps2")
        tick("xyz.armsx2")
        now = 10_000
        assertEquals(listOf(WatchItem.Playing("ps2", 120_000)), tick("com.android.launcher"))
        now = 130_000
        assertEquals(listOf(WatchItem.Finishing("ps2")), tick("com.android.launcher"))
        assertEquals(listOf("ps2"), finished)

        now = 135_000
        assertEquals(listOf(WatchItem.Finishing("ps2")), tick(null))
        jobDone("ps2")
        now = 140_000
        assertEquals(listOf(WatchItem.Grace("ps2", 740_000)), tick(null))
        now = 740_000
        assertEquals(emptyList<WatchItem>(), tick(null))
        assertTrue(w.isIdle)
    }

    @Test
    fun returnsToEmulatorWithinGrace_resumesPlaying() {
        play("ps2")
        tick(null)
        now = 120_000
        tick(null)
        jobDone("ps2")
        now = 125_000
        tick(null) // 待ちに入る
        now = 200_000
        lockedBy = "win-desktop"
        assertEquals(listOf(WatchItem.Resumed("ps2", "win-desktop")), tick("xyz.armsx2"))
        assertEquals(listOf("ps2"), resumed)
        // また見張っている
        now = 210_000
        assertEquals(listOf(WatchItem.Playing("ps2", null)), tick("xyz.armsx2"))
    }

    @Test
    fun manualFinish_stopsWatchingWithoutGrace() {
        play("psp")
        tick("org.ppsspp.ppsspp")
        sessions.remove("psp") // 通知の [送信して終了] で送った
        assertEquals(emptyList<WatchItem>(), tick(null))
        assertTrue(w.isIdle)
        assertEquals(emptyList<String>(), finished)
    }

    @Test
    fun finishFailed_keepsSession_watchesAgain() {
        play("ps2")
        tick(null)
        now = 120_000
        tick(null)
        running -= "ps2" // 失敗（つながらない）。プレイセッションは残る
        now = 125_000
        assertEquals(listOf(WatchItem.Playing("ps2", 120_000)), tick(null))
        now = 245_000
        assertEquals(listOf(WatchItem.Finishing("ps2")), tick(null)) // もう一度試す
        assertEquals(listOf("ps2", "ps2"), finished)
    }

    @Test
    fun screenOff_neverFinishes() {
        play("ps2")
        for (t in 0..3600 step 5) {
            now = t * 1000L
            tick(null, screen = false)
        }
        assertEquals(emptyList<String>(), finished)
    }

    @Test
    fun targetWithoutEmulator_isNotWatched() {
        sessions["custom"] = PlaySessionEntity("custom", 0, true)
        assertEquals(emptyList<WatchItem>(), tick(null))
        assertTrue(w.isIdle)
    }
}
