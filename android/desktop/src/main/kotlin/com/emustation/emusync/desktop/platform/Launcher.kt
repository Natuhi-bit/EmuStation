package com.emustation.emusync.desktop.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
     * emusync 自身のフォルダ（同梱の Java の実行環境など）。インストールしたアプリは、起動したときに
     * runtime\bin を PATH の先頭に足すので、そのままエミュに渡すと、エミュが emusync の DLL（msvcp140.dll など）を
     * 読んでしまう（RPCS3 が「msvcp140.dll was incorrectly installed」で止まる）
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
        val p = pb.start()
        onStarted()
        val tracked = mutableSetOf<ProcessHandle>()
        while (p.isAlive) {
            p.toHandle().descendants().forEach { tracked += it }
            delay(1_000)
        }
        while (true) {
            tracked.removeAll { !it.isAlive }
            if (tracked.isEmpty()) break
            delay(1_000)
        }
        (System.currentTimeMillis() - started) / 1000
    }
}
