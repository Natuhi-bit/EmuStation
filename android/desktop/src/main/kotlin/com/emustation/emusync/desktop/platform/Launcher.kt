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
    suspend fun runAndWait(command: List<String>, onStarted: () -> Unit = {}): Long = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val workDir = File(command.first()).parentFile?.takeIf { it.isDirectory }
        val p = ProcessBuilder(command).directory(workDir).redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
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
