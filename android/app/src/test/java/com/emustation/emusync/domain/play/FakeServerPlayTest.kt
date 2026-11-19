package com.emustation.emusync.domain.play

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.emustation.emusync.data.api.PlayLocks
import com.emustation.emusync.domain.sync.RemoteApi
import com.emustation.emusync.testing.FakeServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FakeServerPlayTest : PlayScenarioContract() {
    private val server = FakeServer()

    override fun remoteFor(device: String): RemoteApi = server.client(device)
    override fun locksFor(device: String): PlayLocks = server.locks(device)
    override fun deviceName(device: String) = device
    override fun lockHolder(target: String) = server.lockHolder(target)

    @Test
    fun lockTtlIs12Hours_andOfflineSessionTriesLockBeforePush() {
        assertTrue(a.start() is StartResult.Ready)
        assertTrue(server.lockLog.single().endsWith("ttl=43200 force=false"))
        a.finish()

        server.lockLog.clear()
        runBlocking { a.manager.startOffline(targetName) }
        a.finish()
        assertEquals(listOf("A lock psp ttl=43200 force=false", "A unlock psp"), server.lockLog)
    }
}
