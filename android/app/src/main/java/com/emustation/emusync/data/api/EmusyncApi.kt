package com.emustation.emusync.data.api

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Serializable
data class PingResponse(val ok: Boolean, val device: String, val time: Double)

/**
 * emusync サーバーの HTTP API（設計書 §3）。
 *
 * @param baseUrl 例 `https://ubuntu-server/emusync`（末尾スラッシュの有無は問わない）
 */
class EmusyncApi(
    baseUrl: String,
    token: String,
    private val client: OkHttpClient,
) {
    private val base: String = normalizeBaseUrl(baseUrl)
        ?: throw IllegalArgumentException("不正なサーバー URL: $baseUrl")
    private val authHeader: String = "Bearer ${token.trim()}"

    init {
        require(isValidToken(token)) { "トークンに使えない文字が含まれています" }
    }

    /** `GET /api/ping` */
    suspend fun ping(): PingResponse = send(get(url("ping"))) { resp ->
        decode<PingResponse>(resp)
    }

    // ------------------------------------------------------------ 内部

    /** Python の `base + "/api/" + "/".join(quote(p, safe="/") for p in parts)` と同じ */
    internal fun url(vararg parts: String): HttpUrl =
        (base + "/api/" + parts.joinToString("/") { encodePath(it) }).toHttpUrl()

    private fun get(url: HttpUrl): Request = Request.Builder().url(url).get().build()

    private suspend fun <T> send(request: Request, onSuccess: (Response) -> T): T {
        val req = request.newBuilder().header("Authorization", authHeader).build()
        val resp = try {
            client.newCall(req).await()
        } catch (e: IOException) {
            throw ApiException.fromIo(e)
        }
        return resp.use {
            if (!it.isSuccessful) throw errorFor(it)
            try {
                onSuccess(it)
            } catch (e: IOException) {
                throw ApiException.fromIo(e)
            }
        }
    }

    private inline fun <reified T> decode(resp: Response): T {
        val body = resp.body.string()
        return try {
            json.decodeFromString<T>(body)
        } catch (e: IllegalArgumentException) {
            throw ApiException.Protocol("応答を読めません: ${body.take(200)}", e)
        }
    }

    companion object {
        internal val json = Json { ignoreUnknownKeys = true }

        /** URL を正規化する。http/https 以外や、解析できないものは null */
        fun normalizeBaseUrl(raw: String): String? {
            val t = raw.trim().trimEnd('/')
            if (t.isEmpty()) return null
            val u = t.toHttpUrlOrNull() ?: return null
            if (u.querySize > 0 || u.fragment != null) return null
            return u.toString().trimEnd('/')
        }

        /** HTTP ヘッダーに入れられる文字（表示可能な ASCII）だけか */
        fun isValidToken(token: String): Boolean {
            val t = token.trim()
            return t.isNotEmpty() && t.all { it in '!'..'~' }
        }

        /** Python の `urllib.parse.quote(s, safe="/")` と同じエンコード */
        fun encodePath(s: String): String {
            val sb = StringBuilder()
            for (b in s.toByteArray(Charsets.UTF_8)) {
                val c = b.toInt() and 0xff
                val ch = c.toChar()
                if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch in "_.-~/") {
                    sb.append(ch)
                } else {
                    sb.append('%').append(HEX[c shr 4]).append(HEX[c and 0x0f])
                }
            }
            return sb.toString()
        }

        private const val HEX = "0123456789ABCDEF"

        /** エラー応答（FastAPI の `{"detail": 文字列 | オブジェクト}`）を例外にする */
        internal fun errorFor(resp: Response): ApiException {
            val body = runCatching { resp.body.string() }.getOrDefault("")
            val detail: JsonElement? = runCatching {
                json.parseToJsonElement(body).jsonObject["detail"]
            }.getOrNull()
            val obj = detail as? JsonObject
            val message = when (detail) {
                is JsonPrimitive -> detail.contentOrNull ?: ""
                is JsonObject -> (detail["message"] as? JsonPrimitive)?.contentOrNull ?: detail.toString()
                null -> body.take(200)
                else -> detail.toString()
            }
            fun str(key: String) = (obj?.get(key) as? JsonPrimitive)?.contentOrNull
            fun num(key: String) = obj?.get(key) as? JsonPrimitive
            return when (resp.code) {
                401 -> ApiException.Unauthorized(message)
                409 -> ApiException.Conflict409(
                    message,
                    serverVersion = num("server_version")?.intOrNull ?: 0,
                    serverSha256 = str("server_sha256"),
                )
                423 -> ApiException.Locked423(
                    message,
                    device = str("device") ?: "",
                    expiresAt = num("expires_at")?.doubleOrNull ?: 0.0,
                )
                // Nginx は届いたが、emusync サーバーが止まっている
                502, 503, 504 -> ApiException.Offline("HTTP ${resp.code}", httpCode = resp.code)
                else -> ApiException.Http(resp.code, message)
            }
        }
    }
}

/** OkHttp の非同期呼び出しをコルーチンのキャンセルに対応させて待つ */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { _, r, _ -> r.close() }
        }

        override fun onFailure(call: Call, e: IOException) {
            cont.resumeWithException(e)
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}
