package com.emustation.emusync.desktop

import com.emustation.emusync.desktop.platform.RemotePlay
import com.emustation.emusync.desktop.platform.SingleInstance
import com.emustation.emusync.desktop.platform.SunshineClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Credentials
import okhttp3.OkHttpClient
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** リモートプレイ：Sunshine への登録と、--remote での起動 */
class RemotePlayTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val server = MockWebServer().apply { start() }

    @After
    fun tearDown() = server.close()

    private fun client(user: String = "me", pass: String = "pa ss") =
        SunshineClient(server.url("/").toString(), user, pass, OkHttpClient())

    private fun appsJson(vararg names: String) =
        """{"apps":[${names.joinToString(",") { """{"name":"$it","cmd":"x"}""" }}]}"""

    @Test
    fun register_addsNewApp() {
        server.enqueue(MockResponse.Builder().body(appsJson("Desktop", "Steam Big Picture")).build())
        server.enqueue(MockResponse.Builder().body("""{"status":true}""").build())
        val r = client().register("\"C:\\Apps\\EmuStation\\EmuStation.exe\" --remote", "C:\\data\\remote\\emustation-poster.png")
        assertEquals(SunshineClient.Result.Added(updated = false), r)

        val list = server.takeRequest()
        assertEquals("GET", list.method)
        assertEquals("/api/apps", list.url.encodedPath)
        assertEquals(Credentials.basic("me", "pa ss", Charsets.UTF_8), list.headers["Authorization"])
        val save = server.takeRequest()
        assertEquals("POST", save.method)
        assertEquals("/api/apps", save.url.encodedPath)
        // ブラウザの呼び出しではないので Origin・Referer は付けない（Sunshine の CSRF の確認が要らない）
        assertNull(save.headers["Origin"])
        assertNull(save.headers["Referer"])
        assertTrue(save.headers["Content-Type"]!!.startsWith("application/json"))
        val body = Json.parseToJsonElement(save.body!!.utf8()).jsonObject
        assertEquals("EmuStation", body["name"]!!.jsonPrimitive.content)
        assertEquals(-1, body["index"]!!.jsonPrimitive.int)
        assertEquals("\"C:\\Apps\\EmuStation\\EmuStation.exe\" --remote", body["cmd"]!!.jsonPrimitive.content)
        assertEquals("C:\\data\\remote\\emustation-poster.png", body["image-path"]!!.jsonPrimitive.content)
        assertTrue(body["auto-detach"]!!.jsonPrimitive.boolean)   // EmuStation は常駐するので、起動したコマンドはすぐ終わる
    }

    @Test
    fun register_updatesExistingApp() {
        server.enqueue(MockResponse.Builder().body(appsJson("Desktop", "EmuStation", "Steam Big Picture")).build())
        server.enqueue(MockResponse.Builder().body("""{"status":true}""").build())
        assertEquals(SunshineClient.Result.Added(updated = true), client().register("cmd --remote", null))
        server.takeRequest()
        val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals(1, body["index"]!!.jsonPrimitive.int)   // 同じものを 2 つ作らない
        assertFalse(body.containsKey("image-path"))
    }

    @Test
    fun register_reportsBadLoginAndErrors() {
        server.enqueue(MockResponse.Builder().code(401).build())
        assertEquals(SunshineClient.Result.BadLogin, client().register("c", null))
        server.enqueue(MockResponse.Builder().body(appsJson()).build())
        server.enqueue(MockResponse.Builder().code(400).body("""{"error":"bad"}""").build())
        assertTrue(client().register("c", null) is SunshineClient.Result.Failed)
        server.enqueue(MockResponse.Builder().body("not json").build())
        assertTrue(client().register("c", null) is SunshineClient.Result.Failed)
    }

    @Test
    fun register_whenSunshineIsNotRunning() {
        val port = ServerSocket(0).use { it.localPort }
        val r = SunshineClient("http://127.0.0.1:$port", "u", "p", OkHttpClient()).register("c", null)
        assertEquals(SunshineClient.Result.NotRunning, r)
    }

    @Test
    fun secondLaunchWithRemote_asksFirstToGoFullscreen() {
        val port = ServerSocket(0).use { it.localPort }
        val got = LinkedBlockingQueue<Boolean>()
        val first = SingleInstance(port)
        assertTrue(first.acquire { got += it })
        try {
            assertFalse(SingleInstance(port).acquire(remote = false) { })
            assertEquals(false, got.poll(5, TimeUnit.SECONDS))
            assertFalse(SingleInstance(port).acquire(remote = true) { })
            assertEquals(true, got.poll(5, TimeUnit.SECONDS))
        } finally {
            first.release()
        }
    }

    @Test
    fun posterIsExported() {
        val f = RemotePlay.exportPoster(tmp.root)!!
        assertTrue(f.isFile && f.length() > 1000)
        assertEquals("\"C:\\EmuStation\\EmuStation.exe\" --remote", RemotePlay.command("C:\\EmuStation\\EmuStation.exe"))
    }
}
