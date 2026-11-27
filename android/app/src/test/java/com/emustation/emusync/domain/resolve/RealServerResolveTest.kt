package com.emustation.emusync.domain.resolve

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.emustation.emusync.data.api.EmusyncApi
import com.emustation.emusync.data.api.HistoryApi
import com.emustation.emusync.data.api.HttpClients
import com.emustation.emusync.domain.sync.RemoteApi
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.runner.RunWith
import java.net.Proxy
import java.util.UUID

/** 競合の解決と履歴・復元を、本物のサーバー（server/app.py）で動かす。環境変数があるときだけ動く */
@RunWith(AndroidJUnit4::class)
class RealServerResolveTest : ResolveScenarioContract() {
    private val url = System.getenv("EMUSYNC_IT_URL")
    private val tokenA = System.getenv("EMUSYNC_IT_TOKEN")
    private val tokenB = System.getenv("EMUSYNC_IT_TOKEN_B")

    override val targetName = "res-" + UUID.randomUUID().toString().take(8)

    @Before
    fun requireServer() {
        assumeTrue(url != null && tokenA != null && tokenB != null && System.getenv("EMUSYNC_IT_DOWN") != "1")
    }

    private fun api(device: String) = EmusyncApi(
        url!!,
        if (device == "B") tokenB!! else tokenA!!,
        HttpClients.base().newBuilder().proxy(Proxy.NO_PROXY).build(),
    )

    override fun remoteFor(device: String): RemoteApi = api(device)
    override fun historyFor(device: String): HistoryApi = api(device)
}
