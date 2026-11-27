package com.emustation.emusync.desktop.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.sun.jna.Native
import com.sun.jna.WString
import com.sun.jna.win32.StdCallLibrary
import java.io.File

/**
 * エミュの起動と、終了を待つ処理（Steam のように、アプリを通して起動する）。
 *
 * 引数のひな形の {rom} をゲームのパスに置き換える。例 PCSX2：`-batch -- "{rom}"`
 */
object Launcher {
    /** "a \"b c\" d" → [a, b c, d]。{rom} は置き換えてから分けるので、空白を含むパスもそのまま1つの引数になる */
    fun buildCommand(exe: String, argsTemplate: String, rom: String?): List<String> {
        val args = splitArgs(argsTemplate).mapNotNull { a ->
            when {
                !a.contains("{rom}") -> a
                rom == null -> null // ゲームなしで起動するときは {rom} を含む引数を落とす
                else -> a.replace("{rom}", rom)
            }
        }
        return listOf(exe) + args
    }

    fun splitArgs(s: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quoted = false
        var has = false
        for (c in s) {
            when {
                c == '"' -> { quoted = !quoted; has = true }
                c.isWhitespace() && !quoted -> {
                    if (has) out += cur.toString()
                    cur.clear()
                    has = false
                }
                else -> { cur.append(c); has = true }
            }
        }
        if (has) out += cur.toString()
        return out
    }

    /**
     * 起動して、終わるまで待つ。エミュが子プロセスを起動して自分は先に終わる場合もあるので、
     * 起動中に見つけた子孫のプロセスがすべて終わるまで待つ。
     * @return 起動から終了までの秒数
     */
    /**
     * EmuStation 自身のフォルダ（同梱の Java の実行環境など）。ここが PATH に入っていると、エミュが EmuStation の
     * DLL（msvcp140.dll など）を読んでしまうので、エミュに渡す PATH からは外す（念のため。本当の原因は [DllDirectory]）
     */
    fun ownDirs(): List<File> = listOfNotNull(
        System.getProperty("java.home")?.let(::File),
        System.getProperty("jpackage.app-path")?.let { File(it).parentFile },
    )

    /** エミュに渡す環境変数から、emusync 自身のフォルダを PATH から外す（Windows の Path は大文字・小文字を問わない） */
    fun cleanEnvironment(env: MutableMap<String, String>, own: List<File>) {
        if (own.isEmpty()) return
        val roots = own.map { it.absoluteFile.normalize().path.trimEnd('\\', '/').lowercase() }
        val sep = if (isWindows) ";" else File.pathSeparator
        for (key in env.keys.filter { it.equals("PATH", ignoreCase = true) }) {
            val kept = env.getValue(key).split(sep).filter { entry ->
                val e = runCatching { File(entry.trim()).absoluteFile.normalize().path.trimEnd('\\', '/').lowercase() }.getOrDefault("")
                entry.isNotBlank() && roots.none { r -> e == r || e.startsWith(r + File.separator) || e.startsWith("$r\\") || e.startsWith("$r/") }
            }
            env[key] = kept.joinToString(sep)
        }
    }

    suspend fun runAndWait(command: List<String>, onStarted: () -> Unit = {}): Long = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val workDir = File(command.first()).parentFile?.takeIf { it.isDirectory }
        val pb = ProcessBuilder(command).directory(workDir).redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        cleanEnvironment(pb.environment(), ownDirs())
        val p = DllDirectory.withoutOwn { pb.start() }
        val handle = p.toHandle()
        RunningEmulator.started(handle)
        try {
            onStarted()
            val tracked = mutableSetOf<ProcessHandle>()
            while (p.isAlive) {
                handle.descendants().forEach { tracked += it; RunningEmulator.track(it) }
                delay(500)
            }
            while (true) {
                tracked.toList().forEach { t -> runCatching { t.descendants().forEach { tracked += it; RunningEmulator.track(it) } } }
                tracked.removeAll { !it.isAlive }
                if (tracked.isEmpty()) break
                delay(500)
            }
        } finally {
            RunningEmulator.finished(handle)
        }
        (System.currentTimeMillis() - started) / 1000
    }
}

/**
 * Windows の「DLL を探す追加のフォルダ」（SetDllDirectory）。インストールした EmuStation は、起動部分（jpackage）が
 * Java の実行環境のフォルダ（runtime\bin）をここに入れる。これは起動したエミュにも引き継がれ、C:\Windows\System32 より
 * 先に探されるので、エミュが EmuStation 同梱の古い msvcp140.dll を読んでしまう
 * （RPCS3 が「msvcp140.dll was incorrectly installed at ...\EmuStation\runtime\bin」で止まる）。
 * エミュを起動する瞬間だけ外して、すぐ元に戻す
 */
object DllDirectory {
    @Suppress("FunctionName")
    private interface Kernel32 : StdCallLibrary {
        fun GetDllDirectoryW(nBufferLength: Int, lpBuffer: CharArray?): Int
        fun SetDllDirectoryW(lpPathName: WString?): Boolean
    }

    private val k32: Kernel32? by lazy {
        if (!isWindows) null else runCatching { Native.load("kernel32", Kernel32::class.java) }.getOrNull()
    }

    /** 今の設定（なければ null） */
    fun current(): String? {
        val k = k32 ?: return null
        val need = runCatching { k.GetDllDirectoryW(0, null) }.getOrDefault(0)
        if (need <= 0) return null
        val buf = CharArray(need + 1)
        val n = runCatching { k.GetDllDirectoryW(buf.size, buf) }.getOrDefault(0)
        return if (n in 1 until buf.size) String(buf, 0, n) else null
    }

    /** 設定する（テスト用。null で既定に戻す） */
    internal fun set(path: String?): Boolean = k32?.let { k -> runCatching { k.SetDllDirectoryW(path?.let(::WString)) }.getOrDefault(false) } ?: false

    /** 設定を外して [block]（プロセスの起動）を行い、元に戻す。Windows 以外や、設定がないときはそのまま */
    @Synchronized
    fun <T> withoutOwn(block: () -> T): T {
        val k = k32 ?: return block()
        val saved = current()?.takeIf { it.isNotEmpty() } ?: return block()
        runCatching { k.SetDllDirectoryW(null) }
        try {
            return block()
        } finally {
            runCatching { k.SetDllDirectoryW(WString(saved)) }
        }
    }
}
