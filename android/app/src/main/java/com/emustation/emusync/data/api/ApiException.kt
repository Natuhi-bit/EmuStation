package com.emustation.emusync.data.api

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.GeneralSecurityException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * サーバー通信のエラー（設計書 §3, §8.2）。
 *
 * HTTP のエラー（サーバーまで届いて応答があったもの）と、
 * サーバーまで届かなかったもの（[Offline]）を区別する。
 */
sealed class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** 401：トークンが無い・無効 */
    class Unauthorized(val detail: String) : ApiException(detail)

    /** 409：楽観ロックの失敗（他の端末が先に更新した） */
    class Conflict409(
        val detail: String,
        val serverVersion: Int,
        val serverSha256: String?,
    ) : ApiException(detail)

    /** 423：他の端末がプレイ中 */
    class Locked423(
        val detail: String,
        val device: String,
        val expiresAt: Double,
    ) : ApiException(detail)

    /**
     * サーバーにつながらない（VPN を確認）。
     * - 名前解決できない、接続を拒否された、タイムアウト、経路がない
     * - Nginx までは届いたが、その奥の emusync サーバーが止まっている（502/503/504）
     */
    class Offline(message: String, cause: Throwable? = null, val httpCode: Int? = null) :
        ApiException(message, cause)

    /** TLS の検証に失敗した（証明書が信頼できない、ホスト名が合わない など） */
    class Tls(val kind: Kind, cause: Throwable) : ApiException(cause.message ?: kind.name, cause) {
        enum class Kind { UNTRUSTED, HOSTNAME, OTHER }
    }

    /** 上記以外の通信エラー（接続が途中で切れた、平文通信が禁止されている など） */
    class Network(cause: IOException) : ApiException(cause.message ?: cause.javaClass.simpleName, cause)

    /** 上記以外の HTTP エラー */
    class Http(val code: Int, val detail: String) : ApiException("HTTP $code: $detail")

    /** 応答の形式がおかしい（JSON が読めない、ハッシュが合わない など） */
    class Protocol(message: String, cause: Throwable? = null) : ApiException(message, cause)

    companion object {
        /** OkHttp が投げた IOException を分類する */
        fun fromIo(e: IOException): ApiException = when {
            e is UnknownHostException || e is ConnectException || e is SocketTimeoutException ||
                e is NoRouteToHostException || e is PortUnreachableException ->
                Offline(e.message ?: e.javaClass.simpleName, e)
            e is SSLPeerUnverifiedException -> Tls(Tls.Kind.HOSTNAME, e)
            e is SSLException ->
                Tls(if (e.hasCause<GeneralSecurityException>()) Tls.Kind.UNTRUSTED else Tls.Kind.OTHER, e)
            else -> Network(e)
        }

        private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean {
            var c: Throwable? = this
            while (c != null) {
                if (c is T) return true
                c = c.cause.takeIf { it !== c }
            }
            return false
        }
    }
}
