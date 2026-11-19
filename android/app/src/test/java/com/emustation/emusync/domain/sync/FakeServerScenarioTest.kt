package com.emustation.emusync.domain.sync

import com.emustation.emusync.testing.FakeServer

/** シナリオ 1〜6 を、メモリ上の偽サーバーで動かす */
class FakeServerScenarioTest : SyncScenarioContract() {
    private val server = FakeServer()

    override fun remoteFor(device: String): RemoteApi = server.client(device)
}
