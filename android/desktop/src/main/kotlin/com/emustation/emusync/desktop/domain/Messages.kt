package com.emustation.emusync.desktop.domain

import com.emustation.emusync.data.api.ApiException

/** 利用者向けの文言 */
object Messages {
    fun connection(e: Throwable): String = when (e) {
        is ApiException.Unauthorized -> "トークンが無効です。設定のトークンを確認してください"
        is ApiException.Offline -> e.httpCode?.let { "サーバーにつながりません（HTTP $it）。サーバー（emusync）が止まっている可能性があります" }
            ?: "サーバーにつながりません。VPN（WireGuard）を確認してください"
        is ApiException.Tls -> when (e.kind) {
            ApiException.Tls.Kind.UNTRUSTED -> "サーバーの証明書を信頼できません。自己署名の証明書なら、CA 証明書を取り込んでください"
            ApiException.Tls.Kind.HOSTNAME -> "サーバーの証明書のホスト名が、サーバー URL と一致しません"
            ApiException.Tls.Kind.OTHER -> "TLS の接続に失敗しました: ${e.message}"
        }
        is ApiException.Http -> when (e.code) {
            404 -> "API が見つかりません（HTTP 404）。サーバー URL を確認してください（例 https://192.168.50.49/emusync）"
            403 -> "アクセスが拒否されました（HTTP 403）。Nginx の allow の設定を確認してください"
            413 -> "ファイルが大きすぎます（サーバーの上限を超えています）"
            else -> "サーバーがエラーを返しました（HTTP ${e.code}）: ${e.detail}"
        }
        is ApiException.Network -> "通信エラー: ${e.message}"
        is ApiException.Protocol -> "サーバーの応答を読めません。サーバー URL を確認してください"
        else -> "エラー: ${e.message ?: e.javaClass.simpleName}"
    }

    fun isUnreachable(e: Throwable?) = e is ApiException.Offline || e is ApiException.Network

    fun summary(up: Int, down: Int, conflicts: Int, errors: Int): String {
        val parts = buildList {
            if (up > 0) add("送信 $up")
            if (down > 0) add("取得 $down")
            if (conflicts > 0) add("競合 $conflicts")
            if (errors > 0) add("失敗 $errors")
        }
        return if (parts.isEmpty()) "変更なし" else parts.joinToString("・")
    }
}
