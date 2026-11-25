package com.emustation.emusync.desktop.platform

import com.sun.jna.platform.win32.Crypt32Util
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import kotlin.concurrent.thread

val isWindows: Boolean = System.getProperty("os.name").startsWith("Windows")

/** データの置き場所。Windows は %APPDATA%\emusync（アプリの名前を EmuStation に変える前からの場所のまま。設定とゲームをそのまま使うため）。テストでは EMUSYNC_DESKTOP_HOME で変えられる */
object AppPaths {
    val root: File by lazy {
        val override = System.getenv("EMUSYNC_DESKTOP_HOME")
        val dir = when {
            override != null -> File(override)
            isWindows -> File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "emusync")
            else -> File(System.getProperty("user.home"), ".config/emusync-desktop")
        }
        dir.apply { mkdirs() }
    }
}

/**
 * トークンなどの秘密を、この Windows ユーザーだけが読める形にする（DPAPI）。
 * Windows 以外（開発・テスト用）では Base64 にするだけ。
 */
object SecretBox {
    fun protect(plain: String): String =
        if (isWindows) "dpapi:" + Base64.getEncoder().encodeToString(Crypt32Util.cryptProtectData(plain.toByteArray()))
        else "plain:" + Base64.getEncoder().encodeToString(plain.toByteArray())

    /** 読めなければ null（別のユーザー・別の PC で保存したもの など） */
    fun unprotect(stored: String): String? = runCatching {
        val (kind, body) = stored.split(":", limit = 2)
        val bytes = Base64.getDecoder().decode(body)
        when (kind) {
            "dpapi" -> String(Crypt32Util.cryptUnprotectData(bytes))
            "plain" -> String(bytes)
            else -> null
        }
    }.getOrNull()
}

/** Windows にサインインしたときに起動する（HKCU の Run キー） */
object Autostart {
    private const val KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val NAME = "EmuStation"

    /** 名前を変える前（emusync）の登録。見つけたら消す */
    private const val OLD_NAME = "emusync"

    /** インストールしたアプリの exe（jpackage が設定する）。開発中は null */
    val exePath: String? get() = System.getProperty("jpackage.app-path")

    val supported: Boolean get() = isWindows && exePath != null

    fun isEnabled(): Boolean = supported && runReg("query", KEY, "/v", NAME) == 0

    fun set(enabled: Boolean): Boolean {
        if (!supported) return false
        runReg("delete", KEY, "/v", OLD_NAME, "/f")
        return if (enabled) {
            runReg("add", KEY, "/v", NAME, "/t", "REG_SZ", "/d", "\"$exePath\" --minimized", "/f") == 0
        } else {
            runReg("delete", KEY, "/v", NAME, "/f") == 0 || !isEnabled()
        }
    }

    private fun runReg(vararg args: String): Int =
        ProcessBuilder(listOf("reg") + args).redirectErrorStream(true).start().let {
            it.inputStream.readAllBytes()
            it.waitFor()
        }
}

/**
 * 同時に1つだけ動かす。2つ目を起動したら、1つ目のウィンドウを前に出して終わる
 * （Windows の起動時に常駐したあとで、スタートメニューから開いたとき）。
 */
class SingleInstance(private val port: Int = 47615) {
    private var server: ServerSocket? = null

    /** 1つ目なら true。2つ目なら1つ目に「表示して」と伝えて false */
    fun acquire(onShowRequest: () -> Unit): Boolean {
        return try {
            val s = ServerSocket(port, 4, InetAddress.getLoopbackAddress())
            server = s
            thread(isDaemon = true, name = "single-instance") {
                while (!s.isClosed) {
                    runCatching {
                        s.accept().use { c ->
                            if (c.getInputStream().bufferedReader().readLine() == MESSAGE) onShowRequest()
                        }
                    }
                }
            }
            true
        } catch (_: java.io.IOException) {
            runCatching {
                Socket(InetAddress.getLoopbackAddress(), port).use { it.getOutputStream().write("$MESSAGE\n".toByteArray()) }
            }
            false
        }
    }

    fun release() = runCatching { server?.close() }

    private companion object {
        const val MESSAGE = "emusync:show"
    }
}
