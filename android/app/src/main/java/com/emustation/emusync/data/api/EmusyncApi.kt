package com.emustation.emusync.data.api

import com.emustation.emusync.data.storage.toHex
import com.emustation.emusync.domain.sync.Downloaded
import com.emustation.emusync.domain.sync.RemoteApi
import com.emustation.emusync.domain.sync.RemoteFile
import com.emustation.emusync.domain.sync.UploadContent
import com.emustation.emusync.domain.sync.UploadOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Serializable
data class PingResponse(val ok: Boolean, val device: String, val time: Double)

@Serializable
data class ManifestEntry(
    val path: String,
    val version: Int,
    val sha256: String,
    val size: Long,
    @SerialName("updated_at") val updatedAt: Double,
    val device: String,
)

@Serializable
private data class ManifestResponse(val files: List<ManifestEntry>)

@Serializable
private data class UploadResponse(
    val path: String,
    val version: Int,
    val sha256: String,
    val unchanged: Boolean = false,
)

@Serializable
data class HistoryVersion(
    val version: Int,
    val sha256: String,
    val size: Long,
    @SerialName("created_at") val createdAt: Double,
    val device: String,
)

@Serializable
private data class HistoryResponse(val path: String, val versions: List<HistoryVersion>)

@Serializable
private data class RestoreResponse(val path: String, val version: Int)

@Serializable
data class LockInfo(
    val target: String,
    val device: String,
    @SerialName("expires_at") val expiresAt: Double,
)

@Serializable
private data class LocksResponse(val locks: List<LockInfo>)

/**
 * emusync サーバーの HTTP API（設計書 §3）。
 *
 * @param baseUrl 例 `https://ubuntu-server/emusync`（末尾スラッシュの有無は問わない）
 */
class EmusyncApi(
    baseUrl: String,
    token: String,
    private val client: OkHttpClient,
) : RemoteApi {
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

    /** `GET /api/manifest?prefix=`。キーは prefix を取り除いた相対パス */
    override suspend fun manifest(prefix: String): Map<String, RemoteFile> {
        val u = url("manifest").newBuilder().addQueryParameter("prefix", prefix).build()
        val files = send(get(u)) { decode<ManifestResponse>(it) }.files
        return files.associate { f ->
            val rel = f.path.substring(prefix.length.coerceAtMost(f.path.length))
            rel to RemoteFile(rel, f.version, f.sha256, f.size, f.updatedAt, f.device)
        }
    }

    /** `GET /api/files/{path}`（`version` を指定すると過去の版）。本文の SHA-256 を検証する */
    override suspend fun download(path: String, out: OutputStream, version: Int?): Downloaded {
        val b = url("files", path).newBuilder()
        if (version != null && version != 0) b.addQueryParameter("version", version.toString())
        return send(get(b.build())) { resp ->
            val ver = resp.header("X-Version")?.toIntOrNull()
                ?: throw ApiException.Protocol("X-Version がありません: $path")
            val expected = resp.header("X-SHA256")
                ?: throw ApiException.Protocol("X-SHA256 がありません: $path")
            val md = MessageDigest.getInstance("SHA-256")
            resp.body.byteStream().use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                    out.write(buf, 0, n)
                }
            }
            val sha = md.digest().toHex()
            if (!sha.equals(expected, ignoreCase = true)) {
                throw ApiException.Protocol("ダウンロード内容が壊れています: $path")
            }
            Downloaded(ver, sha)
        }
    }

    /** `PUT /api/files/{path}`（楽観ロック）。409 は [UploadOutcome.Rejected] で返す */
    override suspend fun upload(path: String, content: UploadContent, baseVersion: Int): UploadOutcome {
        val req = Request.Builder()
            .url(url("files", path))
            .header("If-Match", baseVersion.toString())
            .header("X-SHA256", content.sha256)
            .put(StreamBody(content))
            .build()
        return try {
            val r = send(req) { decode<UploadResponse>(it) }
            UploadOutcome.Ok(r.version, r.sha256, r.unchanged)
        } catch (e: ApiException.Conflict409) {
            UploadOutcome.Rejected(e.serverVersion, e.serverSha256)
        }
    }

    /** `GET /api/history/{path}`（新しい順） */
    suspend fun history(path: String): List<HistoryVersion> =
        send(get(url("history", path))) { decode<HistoryResponse>(it) }.versions

    /** `POST /api/restore/{path}`。返り値は新しくできた版 */
    suspend fun restore(path: String, version: Int): Int {
        val body = buildJsonObject { put("version", version) }.toString().toRequestBody(JSON)
        return send(Request.Builder().url(url("restore", path)).post(body).build()) {
            decode<RestoreResponse>(it)
        }.version
    }

    /** `POST /api/locks/{target}`。他の端末が保持していれば [ApiException.Locked423] */
    suspend fun lock(target: String, ttlSeconds: Int = DEFAULT_LOCK_TTL, force: Boolean = false): LockInfo {
        val body = buildJsonObject {
            put("ttl", ttlSeconds)
            put("force", force)
        }.toString().toRequestBody(JSON)
        return send(Request.Builder().url(url("locks", target)).post(body).build()) { decode<LockInfo>(it) }
    }

    /** `DELETE /api/locks/{target}`（自分のロックだけを消す） */
    suspend fun unlock(target: String) {
        send(Request.Builder().url(url("locks", target)).delete().build()) { }
    }

    /** `GET /api/locks`（期限内のものだけ） */
    suspend fun locks(): List<LockInfo> = send(get(url("locks"))) { decode<LocksResponse>(it) }.locks

    // ------------------------------------------------------------ 内部

    /** ファイルから流し込む本文。OkHttp が再送するときは開き直す */
    private class StreamBody(private val content: UploadContent) : RequestBody() {
        override fun contentType() = OCTET_STREAM
        override fun contentLength() = content.size
        override fun writeTo(sink: BufferedSink) {
            content.open().source().use { sink.writeAll(it) }
        }
    }

    /** Python の `base + "/api/" + "/".join(quote(p, safe="/") for p in parts)` と同じ */
    internal fun url(vararg parts: String): HttpUrl =
        (base + "/api/" + parts.joinToString("/") { encodePath(it) }).toHttpUrl()

    private fun get(url: HttpUrl): Request = Request.Builder().url(url).get().build()

    private suspend fun <T> send(request: Request, onSuccess: (Response) -> T): T = withContext(Dispatchers.IO) {
        val req = request.newBuilder().header("Authorization", authHeader).build()
        val resp = try {
            client.newCall(req).await()
        } catch (e: IOException) {
            throw ApiException.fromIo(e)
        }
        resp.use {
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
        private val JSON = "application/json".toMediaType()
        private val OCTET_STREAM = "application/octet-stream".toMediaType()

        /** サーバーの既定と同じ 6 時間 */
        const val DEFAULT_LOCK_TTL = 6 * 3600

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
