package com.emustation.emusync.domain.resolve

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.emustation.emusync.data.api.HistoryApi
import com.emustation.emusync.domain.sync.RemoteApi
import com.emustation.emusync.testing.FakeServer
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FakeServerResolveTest : ResolveScenarioContract() {
    private val server = FakeServer()
    override fun remoteFor(device: String): RemoteApi = server.client(device)
    override fun historyFor(device: String): HistoryApi = server.history(device)
}
