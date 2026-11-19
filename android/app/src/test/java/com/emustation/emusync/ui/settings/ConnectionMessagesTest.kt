package com.emustation.emusync.ui.settings

import com.emustation.emusync.R
import com.emustation.emusync.data.api.ApiException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/** 段階 1 の完成条件の文言（「トークンが無効」「つながらない」）に正しく振り分けられるか */
class ConnectionMessagesTest {
    @Test
    fun unauthorized() {
        assertEquals(R.string.conn_unauthorized, connectionErrorText(ApiException.Unauthorized("x")).id)
    }

    @Test
    fun offline_variants() {
        listOf(UnknownHostException("h"), ConnectException("c"), SocketTimeoutException("t")).forEach {
            assertEquals(R.string.conn_offline, connectionErrorText(ApiException.fromIo(it)).id)
        }
        assertEquals(
            R.string.conn_server_down,
            connectionErrorText(ApiException.Offline("HTTP 502", httpCode = 502)).id,
        )
    }

    @Test
    fun tls_variants() {
        assertEquals(
            R.string.conn_tls_hostname,
            connectionErrorText(ApiException.fromIo(SSLPeerUnverifiedException("Hostname x not verified"))).id,
        )
        val untrusted = SSLHandshakeException("x").apply {
            initCause(java.security.cert.CertPathValidatorException("no anchor"))
        }
        assertEquals(R.string.conn_tls_untrusted, connectionErrorText(ApiException.fromIo(untrusted)).id)
    }

    @Test
    fun http_variants() {
        assertEquals(R.string.conn_http_404, connectionErrorText(ApiException.Http(404, "")).id)
        assertEquals(R.string.conn_http_403, connectionErrorText(ApiException.Http(403, "")).id)
        assertEquals(R.string.conn_http, connectionErrorText(ApiException.Http(500, "")).id)
    }
}
