package com.emustation.emusync.domain.play

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.emustation.emusync.data.api.EmusyncApi
import com.emustation.emusync.data.api.HttpClients
import com.emustation.emusync.data.api.PlayLocks
import com.emustation.emusync.domain.sync.RemoteApi
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.runner.RunWith
import java.net.Proxy
import java.util.UUID

/**
 * プレイの流れを本物のサーバー（server/app.py）で動かす。環境変数があるときだけ動く。
 * 「つながらない」は、通信を例外に差し替えて作る（サーバーを止めたときの分類は ServerIntegrationTest で確かめている）。
 */
@RunWith(AndroidJUnit4::class)
class RealServerPlayTest : PlayScenarioContract() {
    private val url = System.getenv("EMUSYNC_IT_URL")
    private val tokenA = System.getenv("EMUSYNC_IT_TOKEN")
    private val tokenB = System.getenv("EMUSYNC_IT_TOKEN_B")

    override val targetName = "play-" + UUID.randomUUID().toString().take(8)

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
    override fun locksFor(device: String): PlayLocks = api(device)
    override fun deviceName(device: String) = if (device == "B") "it-windows" else "it-android"
    override fun lockHolder(target: String) = runBlocking { api("A").locks() }.firstOrNull { it.target == target }?.device
}
