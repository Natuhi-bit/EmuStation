package com.emustation.emusync.data.api

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.Proxy
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

class EmusyncApiTest {
    private lateinit var server: MockWebServer

    /** テスト環境の HTTP プロキシ設定を使わないクライアント */
    private fun client(): OkHttpClient = HttpClients.base().newBuilder().proxy(Proxy.NO_PROXY).build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun api(token: String = "tok") =
        EmusyncApi(server.url("/emusync/").toString(), token, client())

    private suspend inline fun <reified T : Throwable> assertThrows(block: () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw AssertionError("expected ${T::class.simpleName} but got $e", e)
        }
        fail("expected ${T::class.simpleName}")
        throw IllegalStateException()
    }

    // ------------------------------------------------------------ URL

    @Test
    fun encodePath_matchesPythonQuote() {
        // 期待値は Python の urllib.parse.quote(s, safe="/") の出力
        assertEquals("psp/ULJM05800/DATA.BIN", EmusyncApi.encodePath("psp/ULJM05800/DATA.BIN"))
        assertEquals(
            "vita/%E3%82%BB%E3%83%BC%E3%83%96%20%E3%83%87%E3%83%BC%E3%82%BF/a%2Bb%26c%3Dd%25e.bin",
            EmusyncApi.encodePath("vita/セーブ データ/a+b&c=d%e.bin"),
        )
        assertEquals("x/~tilde_-.%21%2A%28%29%27%23%3F", EmusyncApi.encodePath("x/~tilde_-.!*()'#?"))
        assertEquals("a%20b/c", EmusyncApi.encodePath("a b/c"))
    }

    @Test
    fun normalizeBaseUrl() {
        assertEquals("https://ubuntu-server/emusync", EmusyncApi.normalizeBaseUrl("https://ubuntu-server/emusync"))
        assertEquals("https://ubuntu-server/emusync", EmusyncApi.normalizeBaseUrl("  https://ubuntu-server/emusync/ "))
        assertEquals("https://ubuntu-server", EmusyncApi.normalizeBaseUrl("https://ubuntu-server/"))
        assertEquals("http://100.64.0.1:8765", EmusyncApi.normalizeBaseUrl("http://100.64.0.1:8765"))
        assertNull(EmusyncApi.normalizeBaseUrl(""))
        assertNull(EmusyncApi.normalizeBaseUrl("ubuntu-server/emusync"))
        assertNull(EmusyncApi.normalizeBaseUrl("ftp://ubuntu-server"))
        assertNull(EmusyncApi.normalizeBaseUrl("https://ubuntu-server/emusync?x=1"))
    }

    @Test
    fun url_keepsBasePathAndEncodesSegments() {
        val a = EmusyncApi("https://ubuntu-server/emusync/", "t", client())
        assertEquals("https://ubuntu-server/emusync/api/ping", a.url("ping").toString())
        assertEquals(
            "https://ubuntu-server/emusync/api/files/psp/a%20b/%23.bin",
            a.url("files", "psp/a b/#.bin").toString(),
        )
    }

    @Test
    fun token_validation() {
        assertTrue(EmusyncApi.isValidToken("AbC-_123"))
        assertTrue(!EmusyncApi.isValidToken(""))
        assertTrue(!EmusyncApi.isValidToken("あいう"))
        assertTrue(!EmusyncApi.isValidToken("a b"))
    }

    // ------------------------------------------------------------ ping

    @Test
    fun ping_success_sendsBearerToken() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"ok": true, "device": "android-phone", "time": 1763560000.5, "extra": 1}""")
                .build(),
        )
        val pong = api(" secret-token ").ping()
        assertEquals("android-phone", pong.device)
        val req = server.takeRequest()
        assertEquals("/emusync/api/ping", req.url.encodedPath)
        assertEquals("Bearer secret-token", req.headers["Authorization"])
    }

    @Test
    fun ping_401_isUnauthorized() = runTest {
        server.enqueue(MockResponse.Builder().code(401).body("""{"detail": "トークンが無効です"}""").build())
        val e = assertThrows<ApiException.Unauthorized> { api().ping() }
        assertEquals("トークンが無効です", e.detail)
    }

    @Test
    fun ping_502_fromNginx_isOffline() = runTest {
        server.enqueue(MockResponse.Builder().code(502).body("<html>Bad Gateway</html>").build())
        val e = assertThrows<ApiException.Offline> { api().ping() }
        assertEquals(502, e.httpCode)
    }

    @Test
    fun ping_404_isHttp() = runTest {
        server.enqueue(MockResponse.Builder().code(404).body("""{"detail":"Not Found"}""").build())
        val e = assertThrows<ApiException.Http> { api().ping() }
        assertEquals(404, e.code)
        assertEquals("Not Found", e.detail)
    }

    @Test
    fun ping_htmlBody_isProtocolError() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("<html>Nextcloud</html>").build())
        assertThrows<ApiException.Protocol> { api().ping() }
    }

    @Test
    fun connectionRefused_isOffline() = runTest {
        val port = ServerSocket(0).use { it.localPort }
        val a = EmusyncApi("http://127.0.0.1:$port/emusync", "t", client())
        assertThrows<ApiException.Offline> { a.ping() }
    }

    @Test
    fun readTimeout_isOffline() = runTest {
        // 接続は受け付けるが、何も返さないサーバー
        ServerSocket(0).use { ss ->
            val c = client().newBuilder().readTimeout(300, TimeUnit.MILLISECONDS).build()
            val a = EmusyncApi("http://127.0.0.1:${ss.localPort}/emusync", "t", c)
            assertThrows<ApiException.Offline> { a.ping() }
        }
    }

    @Test
    fun unknownHost_isOffline() = runTest {
        val a = EmusyncApi("https://emusync-test.invalid/emusync", "t", client())
        assertThrows<ApiException.Offline> { a.ping() }
    }

    // ------------------------------------------------------------ エラー本文の解析

    @Test
    fun errorDetail_objectForms() = runTest {
        server.enqueue(
            MockResponse.Builder().code(409).body(
                """{"detail": {"message": "サーバー側が更新されています", "server_version": 5, "server_sha256": "ab"}}""",
            ).build(),
        )
        server.enqueue(
            MockResponse.Builder().code(423).body(
                """{"detail": {"message": "win-desktop がプレイ中です", "device": "win-desktop", "expires_at": 1763600000.25}}""",
            ).build(),
        )
        val c = assertThrows<ApiException.Conflict409> { api().ping() }
        assertEquals(5, c.serverVersion)
        assertEquals("ab", c.serverSha256)
        val l = assertThrows<ApiException.Locked423> { api().ping() }
        assertEquals("win-desktop", l.device)
        assertEquals(1763600000.25, l.expiresAt, 0.0)
        assertEquals("win-desktop がプレイ中です", l.detail)
    }
}
