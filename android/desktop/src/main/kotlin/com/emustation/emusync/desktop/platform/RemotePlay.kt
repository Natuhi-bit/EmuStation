package com.emustation.emusync.desktop.platform

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * リモートプレイ（Sunshine＋Moonlight）。この PC の Sunshine に EmuStation をアプリとして登録すると、
 * Moonlight（Android など）から「EmuStation」を選ぶだけで、全画面の EmuStation が映り、コントローラーでゲームを選んで遊べる
 */
object RemotePlay {
    /** この引数で起動すると、全画面で前に出す（起動中なら、起動中のものを全画面にする） */
    const val ARG = "--remote"
    const val APP_NAME = "EmuStation"

    /** インストールした EmuStation の exe（開発中に動かしているときは null） */
    fun exePath(): String? = System.getProperty("jpackage.app-path")?.takeIf { File(it).isFile }

    /** Moonlight で「アプリを終了」したとき（Sunshine が実行する）。起動中の EmuStation の全画面を解いて最小化する */
    const val END_ARG = "--remote-end"

    /** Sunshine に登録するコマンド（起動） */
    fun command(exe: String): String = "\"$exe\" $ARG"

    /** Sunshine に登録するコマンド（終了） */
    fun endCommand(exe: String): String = "\"$exe\" $END_ARG"

    /**
     * この PC の、自宅のネットワークでの IP アドレス（192.168.x.x・10.x.x.x・172.16〜31.x.x）。
     * WireGuard で家の外からつなぐとき、Moonlight に入れるアドレス
     */
    fun localAddresses(): List<String> = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<java.net.Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .map { it.hostAddress }
            .distinct()
            .sorted()
    }.getOrDefault(emptyList())

    /** Moonlight のアプリ一覧に出す絵（ポスター）を、データのフォルダに書き出す。書き出したファイル */
    fun exportPoster(root: File): File? = runCatching {
        val out = File(root, "remote/emustation-poster.png")
        val bytes = RemotePlay::class.java.classLoader.getResourceAsStream("poster.png")?.use { it.readBytes() } ?: return null
        if (!out.isFile || out.length() != bytes.size.toLong()) {
            out.parentFile.mkdirs()
            out.writeBytes(bytes)
        }
        out
    }.getOrNull()
}

/**
 * Sunshine の設定画面の API（https://localhost:47990）。ユーザー名とパスワードは、Sunshine の設定画面に入るときのもの。
 * ブラウザ以外からの呼び出しなので CSRF のトークンは要らない（Origin・Referer を付けない）
 */
class SunshineClient(
    private val baseUrl: String = DEFAULT_URL,
    private val user: String,
    private val password: String,
    private val http: OkHttpClient = localClient(),
) {
    sealed interface Result {
        data class Added(val updated: Boolean) : Result
        data object NotRunning : Result
        data object BadLogin : Result
        data class Failed(val message: String) : Result
    }

    private fun get(path: String) = Request.Builder().url(baseUrl.trimEnd('/') + path)
        .header("Authorization", Credentials.basic(user, password, Charsets.UTF_8))

    /**
     * EmuStation を登録する（すでにあれば、コマンドと絵を新しくする）。
     * 起動は「切り離して実行するコマンド」（detached）にする。アプリのコマンドを空にすると、Sunshine は
     * Moonlight で「アプリを終了」するまでセッションを続け、終了したら終わりのコマンド（prep-cmd の undo）を実行する。
     * EmuStation 自身は Sunshine に終わらされない（常駐し続ける）
     */
    fun register(command: String, endCommand: String, imagePath: String?): Result {
        return try {
            val list = http.newCall(get("/api/apps").build()).execute().use { r ->
                if (r.code == 401) return Result.BadLogin
                if (!r.isSuccessful) return Result.Failed("アプリの一覧を読めません（HTTP ${r.code}）")
                Json.parseToJsonElement(r.body.string()).jsonObject["apps"]?.jsonArray ?: return Result.Failed("アプリの一覧の形が違います")
            }
            val index = list.indexOfFirst { it.jsonObject["name"]?.jsonPrimitive?.content == RemotePlay.APP_NAME }
            val body = buildJsonObject {
                put("name", RemotePlay.APP_NAME)
                put("index", index)
                put("cmd", "")
                put("output", "")
                put("exclude-global-prep-cmd", false)
                put("elevated", false)
                put("auto-detach", true)
                put("wait-all", false)
                put("exit-timeout", 5)
                put("prep-cmd", buildJsonArray {
                    add(buildJsonObject {
                        put("do", "")
                        put("undo", endCommand)
                        put("elevated", false)
                    })
                })
                put("detached", buildJsonArray { add(JsonPrimitive(command)) })
                if (!imagePath.isNullOrBlank()) put("image-path", imagePath)
            }
            val req = get("/api/apps").post(body.toString().toRequestBody("application/json".toMediaType())).build()
            http.newCall(req).execute().use { r ->
                when {
                    r.code == 401 -> Result.BadLogin
                    !r.isSuccessful -> Result.Failed("登録できません（HTTP ${r.code}：${r.body.string().take(200)}）")
                    else -> Result.Added(updated = index >= 0)
                }
            }
        } catch (e: java.net.ConnectException) {
            Result.NotRunning
        } catch (e: java.io.IOException) {
            Result.Failed(e.message ?: e.javaClass.simpleName)
        } catch (e: IllegalArgumentException) {
            Result.Failed("Sunshine の返事を読めません")
        }
    }

    companion object {
        const val DEFAULT_URL = "https://localhost:47990"

        /**
         * この PC の Sunshine だけにつなぐ（Sunshine の設定画面は自分で作った証明書なので、localhost に限って確かめない）
         */
        fun localClient(): OkHttpClient {
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) }
            return OkHttpClient.Builder()
                .sslSocketFactory(ssl.socketFactory, trustAll)
                .hostnameVerifier { host, _ -> host == "localhost" || host == "127.0.0.1" }
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
        }
    }
}
