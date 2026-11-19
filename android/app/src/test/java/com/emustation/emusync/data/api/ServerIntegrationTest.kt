package com.emustation.emusync.data.api

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.Proxy

/**
 * 本物のサーバー（server/app.py）に対する確認。環境変数があるときだけ動く。
 *
 * - EMUSYNC_IT_URL   : 例 http://127.0.0.1:8765
 * - EMUSYNC_IT_TOKEN : add-device で発行したトークン
 * - EMUSYNC_IT_DEVICE: そのトークンの端末名
 * - EMUSYNC_IT_DOWN=1: サーバーを止めた状態で「つながらない」ことを確かめる
 *
 * 手順は android/scripts/server-integration.sh を参照。
 */
class ServerIntegrationTest {
    private val url: String? = System.getenv("EMUSYNC_IT_URL")
    private val token: String = System.getenv("EMUSYNC_IT_TOKEN").orEmpty()
    private val down = System.getenv("EMUSYNC_IT_DOWN") == "1"

    private fun api(token: String) = EmusyncApi(
        url!!,
        token,
        HttpClients.base().newBuilder().proxy(Proxy.NO_PROXY).build(),
    )

    @Test
    fun ping_withValidToken_succeeds() = runTest {
        assumeTrue(url != null && !down)
        val pong = api(token).ping()
        assertEquals(System.getenv("EMUSYNC_IT_DEVICE"), pong.device)
        assertEquals(true, pong.ok)
    }

    @Test
    fun ping_withWrongToken_isUnauthorized() = runTest {
        assumeTrue(url != null && !down)
        try {
            api("wrong-token").ping()
            fail("expected 401")
        } catch (e: ApiException.Unauthorized) {
            assertEquals("トークンが無効です", e.detail)
        }
    }

    @Test
    fun ping_whenServerStopped_isOffline() = runTest {
        assumeTrue(url != null && down)
        try {
            api(token).ping()
            fail("expected Offline")
        } catch (_: ApiException.Offline) {
        }
    }
}
