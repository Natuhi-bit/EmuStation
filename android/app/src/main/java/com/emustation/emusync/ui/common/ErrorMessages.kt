package com.emustation.emusync.ui.common

import com.emustation.emusync.R
import com.emustation.emusync.data.api.ApiException

/** 接続エラーを利用者向けの文言にする */
fun connectionErrorText(e: Throwable): UiText = when (e) {
    is ApiException.Unauthorized -> UiText(R.string.conn_unauthorized)
    is ApiException.Offline ->
        e.httpCode?.let { UiText(R.string.conn_server_down, it) } ?: UiText(R.string.conn_offline)
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

/**
 * 原因を調べるための詳細（例外の種類とメッセージ）。例 `UnknownHostException: Unable to resolve host "ubuntu-server"`。
 * 通信の例外は ApiException に包まれているので、いちばん内側の原因を出す。HTTP の応答があったものは null
 */
fun technicalDetail(e: Throwable): String? {
    if (e is ApiException.Http || e is ApiException.Unauthorized) return null
    if (e is ApiException.Offline && e.httpCode != null) return null
    var c: Throwable = e
    while (c.cause != null && c.cause !== c) c = c.cause!!
    if (c === e && e is ApiException) return null
    return listOfNotNull(c.javaClass.simpleName, c.message).joinToString(": ")
}
