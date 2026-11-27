package com.emustation.emusync.desktop.platform

import javazoom.jl.decoder.Bitstream
import javazoom.jl.decoder.Decoder
import javazoom.jl.decoder.SampleBuffer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.FloatControl
import kotlin.math.log10

/** 操作音の種類（PS3 の XMB のように、カーソル・決定・戻る・オプション） */
enum class Sfx(val label: String) {
    Cursor("カーソルを動かす"),
    Decide("決定"),
    Back("戻る・閉じる"),
    Options("オプションを開く"),
}

/** 読み込んだ音（16 ビットの PCM） */
class Pcm(val format: AudioFormat, val data: ByteArray)

/**
 * 操作音。音のファイル（MP3・WAV）はアプリに入れず、利用者が選んだものを sounds/ に置いて使う。
 * すぐ鳴るよう、読み込んでおいた音を Clip に入れ、続けて鳴らしても途切れないよう 4 つを順に使う
 */
object Sounds {
    @Volatile var enabled: Boolean = true

    /** 0〜100 */
    @Volatile var volume: Int = 80

    private val pools = ConcurrentHashMap<Sfx, ClipPool>()
    private val player = Executors.newSingleThreadExecutor { r -> Thread(r, "emusync-sounds").apply { isDaemon = true } }
    private val exts = listOf("mp3", "wav")

    /** sounds/ の中の、その種類の音のファイル（cursor.mp3 など） */
    fun fileOf(dir: File, sfx: Sfx): File? = exts.map { File(dir, "${sfx.name.lowercase()}.$it") }.firstOrNull { it.isFile }

    /** 選んだファイルを sounds/ に写して使う（元のファイルは動かしてもよい）。読めない形式なら例外 */
    fun install(dir: File, sfx: Sfx, src: File) {
        val ext = src.extension.lowercase()
        require(ext in exts) { "MP3 か WAV のファイルを選んでください" }
        decode(src) // 読めるか先に確かめる
        dir.mkdirs()
        remove(dir, sfx)
        src.copyTo(File(dir, "${sfx.name.lowercase()}.$ext"), overwrite = true)
        load(dir)
    }

    fun remove(dir: File, sfx: Sfx) {
        exts.forEach { File(dir, "${sfx.name.lowercase()}.$it").delete() }
        pools.remove(sfx)?.close()
    }

    /** sounds/ の音をすべて読み込み直す */
    fun load(dir: File) {
        for (sfx in Sfx.entries) {
            pools.remove(sfx)?.close()
            val f = fileOf(dir, sfx) ?: continue
            runCatching { ClipPool(decode(f)) }.onSuccess { pools[sfx] = it }
        }
    }

    fun has(sfx: Sfx) = pools.containsKey(sfx)

    /** 鳴らす（音がない・鳴らせない環境では何もしない）。呼んだスレッドは止めない */
    /** 鳴らした音を知らせる（テスト用） */
    @Volatile var listener: ((Sfx) -> Unit)? = null

    fun play(sfx: Sfx) {
        listener?.invoke(sfx)
        if (!enabled || volume <= 0) return
        val pool = pools[sfx] ?: return
        player.execute { runCatching { pool.play(volume) } }
    }

    /** MP3・WAV を 16 ビットの PCM に */
    fun decode(f: File): Pcm = when (f.extension.lowercase()) {
        "mp3" -> decodeMp3(f.readBytes())
        else -> AudioSystem.getAudioInputStream(f).use { ais ->
            val target = AudioFormat(ais.format.sampleRate, 16, ais.format.channels, true, false)
            val conv = if (ais.format.matches(target)) ais else AudioSystem.getAudioInputStream(target, ais)
            Pcm(target, conv.readAllBytes())
        }
    }

    fun decodeMp3(bytes: ByteArray): Pcm {
        val bits = Bitstream(ByteArrayInputStream(bytes))
        val dec = Decoder()
        val out = ByteArrayOutputStream()
        var rate = 0
        var channels = 0
        try {
            while (true) {
                val h = bits.readFrame() ?: break
                val buf = dec.decodeFrame(h, bits) as SampleBuffer
                rate = buf.sampleFrequency
                channels = buf.channelCount
                val n = buf.bufferLength
                val samples = buf.buffer
                for (i in 0 until n) {
                    val v = samples[i].toInt()
                    out.write(v and 0xFF)
                    out.write((v shr 8) and 0xFF)
                }
                bits.closeFrame()
            }
        } finally {
            bits.close()
        }
        require(rate > 0 && out.size() > 0) { "MP3 として読めません" }
        return Pcm(AudioFormat(rate.toFloat(), 16, channels, true, false), out.toByteArray())
    }

    /** 同じ音を重ねて鳴らせるように、いくつかの Clip を順に使う */
    private class ClipPool(pcm: Pcm, size: Int = 4) {
        private val clips: List<Clip> = List(size) {
            AudioSystem.getClip().apply {
                open(AudioInputStream(ByteArrayInputStream(pcm.data), pcm.format, pcm.data.size.toLong() / pcm.format.frameSize))
            }
        }
        private var next = 0

        @Synchronized
        fun play(volume: Int) {
            val c = clips[next]
            next = (next + 1) % clips.size
            (c.getControl(FloatControl.Type.MASTER_GAIN) as? FloatControl)?.let { g ->
                val db = (20 * log10(volume.coerceIn(1, 100) / 100.0)).toFloat()
                g.value = db.coerceIn(g.minimum, g.maximum)
            }
            c.stop()
            c.framePosition = 0
            c.start()
        }

        fun close() = clips.forEach { runCatching { it.close() } }
    }
}
