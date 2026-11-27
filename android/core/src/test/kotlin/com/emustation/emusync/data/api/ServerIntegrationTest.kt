package com.emustation.emusync.data.api

import com.emustation.emusync.domain.sync.UploadContent
import com.emustation.emusync.domain.sync.UploadOutcome
import com.emustation.emusync.testing.sha256
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
 * - EMUSYNC_IT_TOKEN_B: 2台目の端末のトークン（ロックの確認用）
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

    @Test
    fun locks_conflictAndForce() = runTest {
        val tokenB = System.getenv("EMUSYNC_IT_TOKEN_B")
        assumeTrue(url != null && !down && tokenB != null)
        val target = "lock-" + UUID.randomUUID().toString().take(8)
        val a = api(token)
        val b = api(tokenB!!)

        assertEquals(System.getenv("EMUSYNC_IT_DEVICE"), a.lock(target, ttlSeconds = 43200).device)
        a.lock(target) // 自分が持っていれば延長
        try {
            b.lock(target)
            fail("expected 423")
        } catch (e: ApiException.Locked423) {
            assertEquals(System.getenv("EMUSYNC_IT_DEVICE"), e.device)
        }
        assertEquals(target, a.locks().single { it.target == target }.target)
        val taken = b.lock(target, force = true)
        assertTrue(taken.device != System.getenv("EMUSYNC_IT_DEVICE"))
        a.unlock(target) // 他の端末のロックは消せない
        assertEquals(taken.device, b.locks().single { it.target == target }.device)
        b.unlock(target)
        assertTrue(b.locks().none { it.target == target })
    }

    @Test
    fun upload409_history_restore() = runTest {
        assumeTrue(url != null && !down)
        val path = "hist-" + UUID.randomUUID().toString().take(8) + "/f.bin"
        val a = api(token)
        fun content(s: String) = s.toByteArray().let { b -> UploadContent(b.size.toLong(), sha256(b)) { b.inputStream() } }

        assertEquals(1, (a.upload(path, content("v1"), 0) as UploadOutcome.Ok).version)
        assertEquals(2, (a.upload(path, content("v2"), 1) as UploadOutcome.Ok).version)
        assertEquals(UploadOutcome.Ok(2, sha256("v2"), true), a.upload(path, content("v2"), 2))
        assertEquals(UploadOutcome.Rejected(2, sha256("v2")), a.upload(path, content("v3"), 1))

        assertEquals(listOf(2, 1), a.history(path).map { it.version })
        assertEquals(3, a.restore(path, 1))
        val out = ByteArrayOutputStream()
        val d = a.download(path, out)
        assertEquals(3, d.version)
        assertEquals("v1", out.toString())
        val old = ByteArrayOutputStream()
        a.download(path, old, version = 2)
        assertEquals("v2", old.toString())
        try {
            a.restore(path, 99)
            fail("expected 404")
        } catch (e: ApiException.Http) {
            assertEquals(404, e.code)
        }
    }
}
