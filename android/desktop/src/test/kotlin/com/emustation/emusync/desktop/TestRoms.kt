package com.emustation.emusync.desktop

import com.emustation.emusync.desktop.meta.GameMetadata
import com.emustation.emusync.desktop.meta.MetaSources
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater

/** テスト用の小さな ROM（ISO 9660・CSO・PBP・PARAM.SFO）を作る */
object TestRoms {
    /** ファイルの一覧（"PSP_GAME/PARAM.SFO" → 中身）から ISO 9660 のイメージを作る */
    fun iso(files: Map<String, ByteArray>): ByteArray {
        // ディレクトリの木
        class Dir(val name: String) {
            val dirs = linkedMapOf<String, Dir>()
            val files = linkedMapOf<String, ByteArray>()
            var lba = 0
        }
        val root = Dir("")
        for ((path, data) in files) {
            val parts = path.split('/')
            var d = root
            for (p in parts.dropLast(1)) d = d.dirs.getOrPut(p) { Dir(p) }
            d.files[parts.last()] = data
        }
        val allDirs = mutableListOf<Dir>()
        fun walk(d: Dir) { allDirs += d; d.dirs.values.forEach(::walk) }
        walk(root)
        var next = 18L
        allDirs.forEach { it.lba = (next++).toInt() } // ディレクトリは 1 セクターずつ
        val fileLba = HashMap<ByteArray, Int>()
        for (d in allDirs) for (f in d.files.values) {
            fileLba[f] = next.toInt()
            next += ((f.size + 2047) / 2048).coerceAtLeast(1)
        }
        val img = ByteArray((next * 2048).toInt())
        fun rec(name: ByteArray, lba: Int, size: Int, dir: Boolean): ByteArray {
            val len = 33 + name.size + (if (name.size % 2 == 0) 1 else 0)
            val b = ByteBuffer.allocate(len)
            b.put(0, len.toByte())
            b.order(ByteOrder.LITTLE_ENDIAN).putInt(2, lba)
            b.order(ByteOrder.BIG_ENDIAN).putInt(6, lba)
            b.order(ByteOrder.LITTLE_ENDIAN).putInt(10, size)
            b.order(ByteOrder.BIG_ENDIAN).putInt(14, size)
            b.put(25, if (dir) 2 else 0)
            b.put(32, name.size.toByte())
            for (i in name.indices) b.put(33 + i, name[i])
            return b.array()
        }
        for (d in allDirs) {
            val out = ByteArrayOutputStream()
            out.write(rec(byteArrayOf(0), d.lba, 2048, true))
            out.write(rec(byteArrayOf(1), d.lba, 2048, true))
            d.dirs.values.forEach { out.write(rec(it.name.toByteArray(), it.lba, 2048, true)) }
            d.files.forEach { (n, data) -> out.write(rec("$n;1".toByteArray(), fileLba.getValue(data), data.size, false)) }
            out.toByteArray().copyInto(img, d.lba * 2048)
        }
        for (d in allDirs) for (f in d.files.values) f.copyInto(img, fileLba.getValue(f) * 2048)
        // 基本ボリューム記述子
        val pvd = 16 * 2048
        img[pvd] = 1
        "CD001".toByteArray().copyInto(img, pvd + 1)
        rec(byteArrayOf(0), root.lba, 2048, true).copyInto(img, pvd + 156)
        img[17 * 2048] = 0xFF.toByte()
        "CD001".toByteArray().copyInto(img, 17 * 2048 + 1)
        return img
    }

    /** 2048 バイトのセクターを、2352 バイト（モード 2 フォーム 1、BIN）にする */
    fun rawBin(iso: ByteArray): ByteArray {
        val n = iso.size / 2048
        val out = ByteArray(n * 2352)
        for (i in 0 until n) {
            val o = i * 2352
            out[o] = 0
            for (k in 1..10) out[o + k] = 0xFF.toByte()
            out[o + 11] = 0
            out[o + 15] = 2
            iso.copyInto(out, o + 24, i * 2048, i * 2048 + 2048)
        }
        return out
    }

    /** CSO（CISO v1、ブロック 2048 バイト、deflate） */
    fun cso(iso: ByteArray): ByteArray {
        val blocks = iso.size / 2048
        val header = 24
        val indexSize = (blocks + 1) * 4
        val data = ByteArrayOutputStream()
        val index = IntArray(blocks + 1)
        var pos = header + indexSize
        for (i in 0 until blocks) {
            index[i] = pos
            val d = Deflater(Deflater.DEFAULT_COMPRESSION, true)
            d.setInput(iso, i * 2048, 2048)
            d.finish()
            val buf = ByteArray(4096)
            val n = d.deflate(buf)
            d.end()
            data.write(buf, 0, n)
            pos += n
        }
        index[blocks] = pos
        val b = ByteBuffer.allocate(header + indexSize).order(ByteOrder.LITTLE_ENDIAN)
        b.put("CISO".toByteArray()).putInt(header).putLong(iso.size.toLong()).putInt(2048).put(1).put(0).put(0).put(0)
        index.forEach { b.putInt(it) }
        return b.array() + data.toByteArray()
    }

    /** PARAM.SFO（文字列の値だけ） */
    fun sfo(values: Map<String, String>): ByteArray {
        val keys = ByteArrayOutputStream()
        val data = ByteArrayOutputStream()
        val index = ByteBuffer.allocate(values.size * 16).order(ByteOrder.LITTLE_ENDIAN)
        for ((k, v) in values) {
            val bytes = v.toByteArray() + 0
            val max = (bytes.size + 3) / 4 * 4
            index.putShort(keys.size().toShort()).putShort(0x0204).putInt(bytes.size).putInt(max).putInt(data.size())
            keys.write(k.toByteArray() + 0)
            data.write(bytes.copyOf(max))
        }
        while (keys.size() % 4 != 0) keys.write(0)
        val keyStart = 20 + values.size * 16
        val dataStart = keyStart + keys.size()
        val h = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
        h.put(0).put('P'.code.toByte()).put('S'.code.toByte()).put('F'.code.toByte()).putInt(0x0101).putInt(keyStart).putInt(dataStart).putInt(values.size)
        return h.array() + index.array() + keys.toByteArray() + data.toByteArray()
    }

    /** EBOOT.PBP（PARAM.SFO と ICON0.PNG だけ） */
    fun pbp(sfo: ByteArray, icon: ByteArray): ByteArray {
        val off0 = 40
        val off1 = off0 + sfo.size
        val off2 = off1 + icon.size
        val h = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN)
        h.put(0).put('P'.code.toByte()).put('B'.code.toByte()).put('P'.code.toByte()).putInt(0x10000)
        h.putInt(off0).putInt(off1)
        repeat(6) { h.putInt(off2) }
        return h.array() + sfo + icon
    }

    /** PNG らしく見えるだけのバイト列 */
    fun png(tag: Int = 1) = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, tag.toByte(), 0, 0)

    fun jpeg(tag: Int = 1) = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 16, tag.toByte(), 0, 0, 0)

    fun ps2SystemCnf(serialFile: String) = "BOOT2 = cdrom0:\\$serialFile;1\r\nVER = 1.00\r\nVMODE = NTSC\r\n".toByteArray()
}

/** つながらない（すぐ失敗する）ところを見に行く情報取得。ネットワークに出ないテスト用 */
fun offlineMetadata(dir: File) = GameMetadata(
    dir,
    sources = MetaSources(
        pcsx2GameIndex = "http://127.0.0.1:9/GameIndex.yaml",
        libretroDat = "http://127.0.0.1:9/dat",
        ps2Cover = "http://127.0.0.1:9/{serial}.jpg",
        ps3Cover = "http://127.0.0.1:9/{lang}/{id}.jpg",
        libretroThumbs = "http://127.0.0.1:9/thumbs",
        libretroTree = "http://127.0.0.1:9/{repo}",
    ),
)
