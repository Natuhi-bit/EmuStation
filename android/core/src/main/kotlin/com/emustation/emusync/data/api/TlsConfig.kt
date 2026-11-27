package com.emustation.emusync.data.api

import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * 自己署名の CA 証明書の扱い（設計書 §8.2）。
 *
 * - 取り込んだ CA だけを信頼する TrustManager を作る。CA が無いときは OS 標準の信頼ストアを使う
 * - ホスト名の検証は OkHttp の既定のまま（無効にしない）
 */
object CaCertificates {
    private val PEM_BLOCK = Regex(
        "-----BEGIN CERTIFICATE-----([A-Za-z0-9+/=\\s]+?)-----END CERTIFICATE-----",
    )

    /** PEM（複数可、前後に説明文があってもよい）または DER の証明書を読む */
    fun parse(bytes: ByteArray): List<X509Certificate> {
        val factory = CertificateFactory.getInstance("X.509")
        val text = String(bytes, Charsets.ISO_8859_1)
        val blocks = PEM_BLOCK.findAll(text).map { it.groupValues[1] }.toList()
        val ders = if (blocks.isNotEmpty()) {
            blocks.map {
                try {
                    Base64.getMimeDecoder().decode(it)
                } catch (e: IllegalArgumentException) {
                    throw CertificateException("PEM の中身を読めません", e)
                }
            }
        } else {
            listOf(bytes)
        }
        val certs = ders.map {
            factory.generateCertificate(ByteArrayInputStream(it)) as? X509Certificate
                ?: throw CertificateException("X.509 証明書ではありません")
        }
        if (certs.isEmpty()) throw CertificateException("証明書が入っていません")
        return certs
    }

    fun toPem(certs: List<X509Certificate>): String = buildString {
        val enc = Base64.getMimeEncoder(64, "\n".toByteArray())
        for (c in certs) {
            append("-----BEGIN CERTIFICATE-----\n")
            append(enc.encodeToString(c.encoded))
            append("\n-----END CERTIFICATE-----\n")
        }
    }

    /** SHA-256 指紋（`AB:CD:...` 形式） */
    fun sha256Fingerprint(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            .joinToString(":") { "%02X".format(it) }

    /** 指定した証明書だけを信頼アンカーにする TrustManager */
    fun trustManager(certs: List<X509Certificate>): X509TrustManager {
        require(certs.isNotEmpty())
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            certs.forEachIndexed { i, c -> setCertificateEntry("emusync-ca-$i", c) }
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(ks)
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }
}

/** 取り込んだ CA だけを信頼するようにする。ホスト名検証はそのまま */
fun OkHttpClient.Builder.trustOnly(certs: List<X509Certificate>): OkHttpClient.Builder {
    val tm = CaCertificates.trustManager(certs)
    val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
    return sslSocketFactory(ctx.socketFactory, tm)
}

object HttpClients {
    /** 設計書 §8.2 のタイムアウト：接続 10 秒、読み書き 120 秒 */
    fun base(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    fun withCa(base: OkHttpClient, certs: List<X509Certificate>?): OkHttpClient =
        if (certs.isNullOrEmpty()) base else base.newBuilder().trustOnly(certs).build()
}

/**
 * 取り込んだ CA 証明書をアプリ内部（filesDir/ca/）に PEM で保存する。
 * ファイル名は設定（DataStore）に記録する。
 */
class CaStore(private val dir: File) {

    /** 読み込んで検証し、保存する。返り値は保存したファイル名 */
    fun import(bytes: ByteArray): Pair<String, List<X509Certificate>> {
        require(bytes.size <= MAX_BYTES) { "ファイルが大きすぎます" }
        val certs = CaCertificates.parse(bytes)
        dir.mkdirs()
        val name = FILE_NAME
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(CaCertificates.toPem(certs))
        if (!tmp.renameTo(File(dir, name))) {
            tmp.delete()
            throw java.io.IOException("証明書を保存できません")
        }
        return name to certs
    }

    fun load(name: String): List<X509Certificate>? {
        val f = File(dir, name)
        if (!f.isFile) return null
        return runCatching { CaCertificates.parse(f.readBytes()) }.getOrNull()
    }

    fun delete(name: String) {
        File(dir, name).delete()
    }

    companion object {
        const val FILE_NAME = "ca.pem"
        private const val MAX_BYTES = 1 shl 20
    }
}
