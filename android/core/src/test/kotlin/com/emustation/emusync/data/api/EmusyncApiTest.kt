package com.emustation.emusync.data.api

import com.emustation.emusync.domain.sync.UploadContent
import com.emustation.emusync.domain.sync.UploadOutcome
import com.emustation.emusync.testing.sha256
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
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

    // ------------------------------------------------------------ 同期で使う API

    @Test
    fun manifest_stripsPrefix_andSendsQuery() = runTest {
        server.enqueue(
            MockResponse.Builder().code(200).body(
                """{"files": [{"path": "psp/ULJM05800/DATA.BIN", "version": 3, "sha256": "ab",
                   "size": 12345, "updated_at": 1763560000.12, "device": "win-desktop"}]}""",
            ).build(),
        )
        val m = api().manifest("psp/")
        val f = m.getValue("ULJM05800/DATA.BIN")
        assertEquals(3, f.version)
        assertEquals(12345L, f.size)
        assertEquals("win-desktop", f.device)
        val req = server.takeRequest()
        assertEquals("/emusync/api/manifest", req.url.encodedPath)
        assertEquals("psp/", req.url.queryParameter("prefix"))
    }

    @Test
    fun download_verifiesSha_andReadsVersion() = runTest {
        val body = "save data"
        server.enqueue(
            MockResponse.Builder().code(200).body(body)
                .addHeader("X-Version", "7").addHeader("X-SHA256", sha256(body)).build(),
        )
        server.enqueue(
            MockResponse.Builder().code(200).body("tampered")
                .addHeader("X-Version", "7").addHeader("X-SHA256", sha256(body)).build(),
        )
        val out = ByteArrayOutputStream()
        val d = api().download("psp/a b/#.bin", out, version = 2)
        assertEquals(7, d.version)
        assertEquals(sha256(body), d.sha256)
        assertEquals(body, out.toString())
        val req = server.takeRequest()
        assertEquals("/emusync/api/files/psp/a%20b/%23.bin", req.url.encodedPath)
        assertEquals("2", req.url.queryParameter("version"))

        assertThrows<ApiException.Protocol> { api().download("psp/x", ByteArrayOutputStream()) }
    }

    @Test
    fun upload_sendsHeaders_andParsesResult() = runTest {
        val data = "new save".toByteArray()
        val content = UploadContent(data.size.toLong(), sha256(data)) { data.inputStream() }
        server.enqueue(MockResponse.Builder().code(200).body("""{"path": "psp/f", "version": 4, "sha256": "${sha256(data)}"}""").build())
        server.enqueue(MockResponse.Builder().code(200).body("""{"path": "psp/f", "version": 4, "sha256": "${sha256(data)}", "unchanged": true}""").build())
        server.enqueue(
            MockResponse.Builder().code(409).body(
                """{"detail": {"message": "サーバー側が更新されています", "server_version": 5, "server_sha256": "cd"}}""",
            ).build(),
        )

        assertEquals(UploadOutcome.Ok(4, sha256(data), false), api().upload("psp/f", content, 3))
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("3", req.headers["If-Match"])
        assertEquals(sha256(data), req.headers["X-SHA256"])
        assertEquals("application/octet-stream", req.headers["Content-Type"])
        assertEquals("new save", req.body!!.utf8())

        assertEquals(UploadOutcome.Ok(4, sha256(data), true), api().upload("psp/f", content, 4))
        assertEquals(UploadOutcome.Rejected(5, "cd"), api().upload("psp/f", content, 4))
    }

    @Test
    fun locks_history_restore() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("""{"target": "psp", "device": "me", "expires_at": 1.5}""").build())
        server.enqueue(MockResponse.Builder().code(200).body("""{"released": true}""").build())
        server.enqueue(MockResponse.Builder().code(200).body("""{"locks": [{"target": "ps2", "device": "win", "expires_at": 2.0}]}""").build())
        server.enqueue(
            MockResponse.Builder().code(200).body(
                """{"path": "psp/f", "versions": [{"version": 2, "sha256": "b", "size": 3, "created_at": 9.0, "device": "x"}]}""",
            ).build(),
        )
        server.enqueue(MockResponse.Builder().code(200).body("""{"path": "psp/f", "version": 3}""").build())

        assertEquals(LockInfo("psp", "me", 1.5), api().lock("psp", ttlSeconds = 43200, force = true))
        val lockReq = server.takeRequest()
        assertEquals("POST", lockReq.method)
        assertEquals("""{"ttl":43200,"force":true}""", lockReq.body!!.utf8())

        api().unlock("psp")
        assertEquals("DELETE", server.takeRequest().method)

        assertEquals(listOf(LockInfo("ps2", "win", 2.0)), api().locks())
        server.takeRequest()

        assertEquals(listOf(HistoryVersion(2, "b", 3, 9.0, "x")), api().history("psp/f"))
        assertEquals("/emusync/api/history/psp/f", server.takeRequest().url.encodedPath)

        assertEquals(3, api().restore("psp/f", 2))
        val rr = server.takeRequest()
        assertEquals("/emusync/api/restore/psp/f", rr.url.encodedPath)
        assertEquals("""{"version":2}""", rr.body!!.utf8())
    }
}
