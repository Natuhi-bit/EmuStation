package com.emustation.emusync.ui.common

import com.emustation.emusync.R
import com.emustation.emusync.data.api.ApiException

/** 接続エラーを利用者向けの文言にする */
fun connectionErrorText(e: Throwable): UiText = when (e) {
    is ApiException.Unauthorized -> UiText(R.string.conn_unauthorized)
    is ApiException.Offline ->
        if (e.httpCode != null) UiText(R.string.conn_server_down, e.httpCode)
        else UiText(R.string.conn_offline)
    is ApiException.Tls -> when (e.kind) {
        ApiException.Tls.Kind.UNTRUSTED -> UiText(R.string.conn_tls_untrusted)
        ApiException.Tls.Kind.HOSTNAME -> UiText(R.string.conn_tls_hostname)
        ApiException.Tls.Kind.OTHER -> UiText(R.string.conn_tls_other, e.message.orEmpty())
    }
    is ApiException.Http -> when (e.code) {
        404 -> UiText(R.string.conn_http_404)
        403 -> UiText(R.string.conn_http_403)
        else -> UiText(R.string.conn_http, e.code, e.detail)
    }
    is ApiException.Network -> UiText(R.string.conn_network, e.message.orEmpty())
    is ApiException.Protocol -> UiText(R.string.conn_protocol)
    else -> UiText(R.string.conn_unknown, e.message ?: e.javaClass.simpleName)
}
