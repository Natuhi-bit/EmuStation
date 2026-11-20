package com.emustation.emusync.ui.home

import com.emustation.emusync.data.LocalState
import com.emustation.emusync.data.api.LockInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class StatusesTest {
    private fun s(
        enabled: Boolean = true,
        last: Long? = 1L,
        conflicts: Int = 0,
        local: LocalState? = LocalState.Scanned(0, 3),
        lock: LockInfo? = null,
        self: String? = "phone",
        playing: Boolean = false,
    ) = statusesFor(enabled, last, conflicts, local, lock, self, playing)

    @Test
    fun synced() = assertEquals(listOf(TargetStatus.Synced), s())

    @Test
    fun unsent() = assertEquals(listOf(TargetStatus.Unsent(2)), s(local = LocalState.Scanned(2, 3)))

    @Test
    fun neverSynced_hidesUnsentCount() =
        assertEquals(listOf(TargetStatus.NeverSynced), s(last = null, local = LocalState.Scanned(3, 3)))

    @Test
    fun conflicts() = assertEquals(listOf(TargetStatus.Conflicts(2)), s(conflicts = 2))

    @Test
    fun playingElsewhere_stillShowsSynced() = assertEquals(
        listOf(TargetStatus.PlayingElsewhere("win-desktop"), TargetStatus.Synced),
        s(lock = LockInfo("psp", "win-desktop", 0.0)),
    )

    @Test
    fun playingHere_fromLockOrSession() {
        assertEquals(listOf(TargetStatus.PlayingHere), s(lock = LockInfo("psp", "phone", 0.0)))
        assertEquals(listOf(TargetStatus.PlayingHere, TargetStatus.Unsent(1)), s(playing = true, local = LocalState.Scanned(1, 1)))
    }

    @Test
    fun localProblems() {
        assertEquals(listOf(TargetStatus.NoPermission), s(local = LocalState.NoPermission))
        assertEquals(listOf(TargetStatus.DataDirMissing), s(local = LocalState.DataDirMissing))
        assertEquals(emptyList<TargetStatus>(), s(local = null))
    }

    @Test
    fun disabled() = assertEquals(listOf(TargetStatus.Disabled), s(enabled = false, local = null))
}
