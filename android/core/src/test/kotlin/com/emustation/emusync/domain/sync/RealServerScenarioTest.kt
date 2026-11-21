package com.emustation.emusync.domain.sync

import com.emustation.emusync.data.api.EmusyncApi
import com.emustation.emusync.data.api.HttpClients
import org.junit.Assume.assumeTrue
import org.junit.Before
import java.net.Proxy
import java.util.UUID

/**
 * シナリオ 1〜6 を、本物のサーバー（server/app.py）で動かす。環境変数があるときだけ動く。
 *
 * - EMUSYNC_IT_URL, EMUSYNC_IT_TOKEN（端末 A）, EMUSYNC_IT_TOKEN_B（端末 B）
 *
 * テストごとにターゲット名を変えて、前のテストの状態が残らないようにする。
 * 手順は android/scripts/server-integration.sh を参照。
 */
class RealServerScenarioTest : SyncScenarioContract() {
    private val url = System.getenv("EMUSYNC_IT_URL")
    private val tokenA = System.getenv("EMUSYNC_IT_TOKEN")
    private val tokenB = System.getenv("EMUSYNC_IT_TOKEN_B")

    override val targetName = "psp-" + UUID.randomUUID().toString().take(8)

    @Before
    fun requireServer() {
        assumeTrue(url != null && tokenA != null && tokenB != null && System.getenv("EMUSYNC_IT_DOWN") != "1")
    }

    override fun remoteFor(device: String): RemoteApi = EmusyncApi(
        url!!,
        if (device == "B") tokenB!! else tokenA!!,
        HttpClients.base().newBuilder().proxy(Proxy.NO_PROXY).build(),
    )
}
