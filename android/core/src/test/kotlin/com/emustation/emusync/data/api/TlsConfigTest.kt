package com.emustation.emusync.data.api

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetAddress
import java.net.Proxy
import java.security.cert.CertificateException

class TlsConfigTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val ca = HeldCertificate.Builder()
        .certificateAuthority(0)
        .commonName("emusync test CA")
        .build()
    private val otherCa = HeldCertificate.Builder()
        .certificateAuthority(0)
        .commonName("other CA")
        .build()

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
    }

    @After
    fun tearDown() {
        server.close()
    }

    /** 指定したホスト名の証明書で HTTPS サーバーを立てる */
    private fun startHttps(san: String) {
        val leaf = HeldCertificate.Builder()
            .commonName(san)
            .addSubjectAlternativeName(san)
            .signedBy(ca)
            .build()
        val serverCerts = HandshakeCertificates.Builder()
            .heldCertificate(leaf, ca.certificate)
            .build()
        server.useHttps(serverCerts.sslSocketFactory())
        // localhost が ::1 に解決される環境（GitHub Actions など）でも届くよう、IPv4 に固定する
        server.start(LOOPBACK, 0)
        server.enqueue(
            MockResponse.Builder().code(200)
                .body("""{"ok": true, "device": "d", "time": 1.0}""").build(),
        )
    }

    private fun client(pem: String?): OkHttpClient {
        val base = HttpClients.base().newBuilder().proxy(Proxy.NO_PROXY)
            .dns { host -> if (host == "localhost") listOf(LOOPBACK) else Dns.SYSTEM.lookup(host) }
            .build()
        return HttpClients.withCa(base, pem?.let { CaCertificates.parse(it.toByteArray()) })
    }

    @Test
    fun parse_pemWithSurroundingText_andMultipleCerts() {
        val pem = "Bag Attributes\n  friendlyName: x\n" + ca.certificatePem() + "\nnoise\n" +
            otherCa.certificatePem()
        val certs = CaCertificates.parse(pem.toByteArray())
        assertEquals(2, certs.size)
        assertEquals(ca.certificate, certs[0])
        assertEquals(otherCa.certificate, certs[1])
    }

    @Test
    fun parse_der() {
        val certs = CaCertificates.parse(ca.certificate.encoded)
        assertEquals(listOf(ca.certificate), certs)
    }

    @Test(expected = CertificateException::class)
    fun parse_garbage_throws() {
        CaCertificates.parse("not a certificate".toByteArray())
    }

    @Test
    fun fingerprint_format() {
        val fp = CaCertificates.sha256Fingerprint(ca.certificate)
        assertEquals(32 * 3 - 1, fp.length)
        assertTrue(fp.matches(Regex("([0-9A-F]{2}:){31}[0-9A-F]{2}")))
    }

    @Test
    fun caStore_roundTrip() {
        val store = CaStore(tmp.newFolder("ca"))
        val (name, certs) = store.import(ca.certificatePem().toByteArray())
        assertEquals(CaStore.FILE_NAME, name)
        assertEquals(listOf(ca.certificate), certs)
        assertEquals(listOf(ca.certificate), store.load(name))
        store.delete(name)
        assertEquals(null, store.load(name))
    }

    @Test
    fun importedCa_isTrusted() = runTest {
        startHttps("localhost")
        val url = server.url("/emusync").newBuilder().host("localhost").build().toString()
        val pong = EmusyncApi(url, "t", client(ca.certificatePem())).ping()
        assertEquals("d", pong.device)
    }

    @Test
    fun withoutCa_selfSignedIsRejected() = runTest {
        startHttps("localhost")
        val url = server.url("/emusync").newBuilder().host("localhost").build().toString()
        try {
            EmusyncApi(url, "t", client(null)).ping()
            fail("should not trust a self-signed server without the CA")
        } catch (e: ApiException.Tls) {
            assertEquals(ApiException.Tls.Kind.UNTRUSTED, e.kind)
        }
    }

    @Test
    fun otherCa_isRejected() = runTest {
        startHttps("localhost")
        val url = server.url("/emusync").newBuilder().host("localhost").build().toString()
        try {
            EmusyncApi(url, "t", client(otherCa.certificatePem())).ping()
            fail("should only trust the imported CA")
        } catch (e: ApiException.Tls) {
            assertEquals(ApiException.Tls.Kind.UNTRUSTED, e.kind)
        }
    }

    @Test
    fun hostnameVerification_isStillEnabled() = runTest {
        // 証明書は "ubuntu-server" 向け。localhost でつなぐとホスト名が合わない
        startHttps("ubuntu-server")
        val url = server.url("/emusync").newBuilder().host("localhost").build().toString()
        try {
            EmusyncApi(url, "t", client(ca.certificatePem())).ping()
            fail("hostname mismatch must be rejected")
        } catch (e: ApiException.Tls) {
            assertEquals(ApiException.Tls.Kind.HOSTNAME, e.kind)
        }
    }

    private companion object {
        val LOOPBACK: InetAddress = InetAddress.getByAddress("localhost", byteArrayOf(127, 0, 0, 1))
    }
}
