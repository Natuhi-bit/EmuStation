package com.emustation.emusync.desktop.meta

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater

/** ROM から読み取った情報。読めなかったものは null */
data class RomInfo(
    /** 型番（例 SLPM-66168、ULJM-05800、BLJM-60051、PCSG-00001） */
    val serial: String? = null,
    /** ROM の中にあるタイトル（PSP・PS3・Vita の PARAM.SFO。日本版なら日本語） */
    val title: String? = null,
    /** ROM の中のアイコン（PNG） */
    val icon: ByteArray? = null,
)

/**
 * ROM（ディスクイメージ・PS3 のゲームフォルダ・Vita3K にインストールしたゲーム）から、型番・タイトル・アイコンを読む。
 * 読めない形式（CHD・ZSO など）やファイル名からは [serialFromName] で型番を探す。
 */
object RomReader {
    fun read(target: String, rom: String, savePath: String? = null, emulatorExe: String? = null): RomInfo {
        val fromRom = runCatching {
            when (target) {
                "ps2" -> readPs2(File(rom))
                "psp" -> readPsp(File(rom))
                "ps3" -> readPs3(File(rom))
                "vita" -> readVita(rom, savePath, emulatorExe)
                in NintendoRom.TARGETS -> NintendoRom.read(target, File(rom))
                else -> null
            }
        }.getOrNull()
        val serial = fromRom?.serial ?: if (target in NintendoRom.TARGETS) {
            NintendoRom.idFromName(target, File(rom).name)
        } else {
            serialFromName(File(rom).name)
        }
        return (fromRom ?: RomInfo()).copy(serial = serial)
    }

    // ---------------------------------------------------------------- 機種ごと
    private fun readPs2(f: File): RomInfo? = DiscImage.open(f)?.use { img ->
        val cnf = Iso9660(img).readFile("SYSTEM.CNF") ?: return null
        RomInfo(serial = ps2SerialFromSystemCnf(String(cnf, Charsets.ISO_8859_1)))
    }

    private fun readPsp(f: File): RomInfo? {
        if (f.extension.equals("pbp", true)) return readPbp(f)
        return DiscImage.open(f)?.use { img ->
            val iso = Iso9660(img)
            val sfo = iso.readFile("PSP_GAME/PARAM.SFO")?.let(ParamSfo::parse) ?: return null
            RomInfo(
                serial = (sfo["DISC_ID"] ?: sfo["TITLE_ID"])?.let(::normalizeSerial),
                title = sfo["TITLE"]?.let(::cleanSfoTitle),
                icon = iso.readFile("PSP_GAME/ICON0.PNG"),
            )
        }
    }

    /** PSP の EBOOT.PBP（ダウンロード版・自作ソフト） */
    private fun readPbp(f: File): RomInfo? = RandomAccessFile(f, "r").use { raf ->
        val head = ByteArray(40).also { raf.readFully(it) }
        if (!(head[0] == 0.toByte() && head[1] == 'P'.code.toByte() && head[2] == 'B'.code.toByte() && head[3] == 'P'.code.toByte())) return null
        val b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
        val off = IntArray(8) { b.getInt(8 + it * 4) }
        fun part(i: Int): ByteArray? {
            val len = off[i + 1] - off[i]
            if (len <= 0 || len > 4 * 1024 * 1024) return null
            return ByteArray(len).also { raf.seek(off[i].toLong()); raf.readFully(it) }
        }
        val sfo = part(0)?.let(ParamSfo::parse) ?: return null
        RomInfo(
            serial = (sfo["DISC_ID"] ?: sfo["TITLE_ID"])?.let(::normalizeSerial),
            title = sfo["TITLE"]?.let(::cleanSfoTitle),
            icon = part(1),
        )
    }

    /** PS3：EBOOT.BIN（PS3_GAME\USRDIR の中、または dev_hdd0\game\<ID>\USRDIR の中）か ISO */
    private fun readPs3(f: File): RomInfo? {
        if (f.extension.equals("iso", true)) {
            return DiscImage.open(f)?.use { img ->
                val iso = Iso9660(img)
                val sfo = iso.readFile("PS3_GAME/PARAM.SFO")?.let(ParamSfo::parse) ?: return null
                RomInfo(sfo["TITLE_ID"]?.let(::normalizeSerial), sfo["TITLE"]?.let(::cleanSfoTitle), iso.readFile("PS3_GAME/ICON0.PNG"))
            }
        }
        // EBOOT.BIN → USRDIR → PS3_GAME（または dev_hdd0/game/<ID>）
        val dir = generateSequence(if (f.isDirectory) f else f.parentFile) { it.parentFile }.take(4)
            .firstOrNull { File(it, "PARAM.SFO").isFile } ?: return null
        val sfo = ParamSfo.parse(File(dir, "PARAM.SFO").readBytes()) ?: return null
        return RomInfo(
            sfo["TITLE_ID"]?.let(::normalizeSerial),
            sfo["TITLE"]?.let(::cleanSfoTitle),
            File(dir, "ICON0.PNG").takeIf { it.isFile }?.readBytes(),
        )
    }

    /** Vita：タイトル ID と、Vita3K にインストールしたゲームのフォルダ（ux0/app/<ID>/sce_sys） */
    private fun readVita(titleId: String, savePath: String?, emulatorExe: String?): RomInfo? {
        val id = titleId.trim().uppercase()
        val ux0s = buildList {
            // セーブのフォルダ（…/ux0/user/00/savedata）から ux0 までさかのぼる
            savePath?.let { p -> generateSequence(File(p)) { it.parentFile }.firstOrNull { it.name.equals("ux0", true) }?.let(::add) }
            emulatorExe?.let { add(File(File(it).parentFile, "ux0")) }
            System.getenv("APPDATA")?.let { add(File(it, "Vita3K/Vita3K/ux0")) }
        }
        val sys = ux0s.map { File(it, "app/$id/sce_sys") }.firstOrNull { File(it, "param.sfo").isFile }
        val sfo = sys?.let { ParamSfo.parse(File(it, "param.sfo").readBytes()) }
        return RomInfo(
            serial = normalizeSerial(sfo?.get("TITLE_ID") ?: id),
            title = (sfo?.get("TITLE") ?: sfo?.get("STITLE"))?.let(::cleanSfoTitle),
            icon = sys?.let { File(it, "icon0.png") }?.takeIf { it.isFile }?.readBytes(),
        )
    }

    // ---------------------------------------------------------------- 型番
    private val SERIAL_IN_NAME = Regex("(?<![A-Za-z])([A-Za-z]{4})[-_ ]?(\\d{3})\\.?(\\d{2})(?!\\d)")
    private val SERIAL = Regex("([A-Z]{4})-?(\\d{5})")

    /** "SLPM-66168"・"SLPM_661.68"・"ULJM05800" → "SLPM-66168" */
    fun normalizeSerial(s: String): String? {
        val t = s.trim().uppercase().replace('_', '-').replace(".", "")
        return SERIAL.find(t)?.let { "${it.groupValues[1]}-${it.groupValues[2]}" }
    }

    /** ファイル名の中の型番（"Persona 3 FES [SLPM-66445].iso" など） */
    fun serialFromName(name: String): String? =
        SERIAL_IN_NAME.find(name)?.let { m -> "${m.groupValues[1].uppercase()}-${m.groupValues[2]}${m.groupValues[3]}" }
            ?.takeIf { KNOWN_PREFIX.matches(it.take(4)) }

    /** よくある型番の先頭（PS2・PSP・PS3・Vita）。でたらめな 4 文字＋数字を型番と思わないように */
    private val KNOWN_PREFIX = Regex("S[LC][A-Z][A-Z]|U[LC][A-Z][A-Z]|N[PL][A-Z][A-Z]|B[LC][A-Z][A-Z]|PCS[A-Z]|V[LC][A-Z][A-Z]|PAP[A-Z]")

    /** SYSTEM.CNF の BOOT2 = cdrom0:\SLPM_661.68;1 */
    fun ps2SerialFromSystemCnf(cnf: String): String? {
        val line = cnf.lineSequence().firstOrNull { it.trim().startsWith("BOOT2", true) } ?: return null
        val file = line.substringAfterLast('\\').substringAfterLast(':').substringBefore(';').trim()
        return normalizeSerial(file)
    }

    private fun cleanSfoTitle(t: String) = t.replace(Regex("[\\r\\n]+"), " ").replace(Regex("\\s{2,}"), " ").trim()
}

/** PARAM.SFO（PSP・PS3・Vita のゲームの情報）。文字列と数値の値を返す */
object ParamSfo {
    fun parse(b: ByteArray): Map<String, String>? {
        if (b.size < 20 || b[0] != 0.toByte() || b[1] != 'P'.code.toByte() || b[2] != 'S'.code.toByte() || b[3] != 'F'.code.toByte()) return null
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val keyTable = bb.getInt(8)
        val dataTable = bb.getInt(12)
        val count = bb.getInt(16)
        if (count !in 0..512) return null
        val out = LinkedHashMap<String, String>()
        for (i in 0 until count) {
            val e = 20 + i * 16
            if (e + 16 > b.size) break
            val keyOff = keyTable + (bb.getShort(e).toInt() and 0xFFFF)
            val fmt = bb.getShort(e + 2).toInt() and 0xFFFF
            val len = bb.getInt(e + 4)
            val dataOff = dataTable + bb.getInt(e + 12)
            val key = cString(b, keyOff) ?: continue
            if (dataOff < 0 || len < 0 || dataOff + len > b.size) continue
            out[key] = when (fmt) {
                0x0404 -> if (len >= 4) bb.getInt(dataOff).toString() else continue
                else -> String(b, dataOff, len, Charsets.UTF_8).trimEnd('\u0000')
            }
        }
        return out
    }

    private fun cString(b: ByteArray, off: Int): String? {
        if (off !in b.indices) return null
        var end = off
        while (end < b.size && b[end] != 0.toByte()) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }
}

/** ディスクイメージを 2048 バイトのセクター単位で読む（ISO・BIN（2352 バイト）・CSO） */
abstract class DiscImage : Closeable {
    /** [lba] から [count] セクター分のデータ（2048 バイト × count） */
    abstract fun read(lba: Long, count: Int): ByteArray

    companion object {
        /** 読めない形式（CHD・ZSO など）なら null */
        fun open(f: File): DiscImage? {
            if (!f.isFile) return null
            val raf = RandomAccessFile(f, "r")
            try {
                val head = ByteArray(minOf(24L, raf.length()).toInt()).also { raf.readFully(it) }
                if (head.size >= 4 && String(head, 0, 4, Charsets.ISO_8859_1) == "CISO") return Cso(raf)
                if (head.size >= 16 && isSync(head)) {
                    // 2352 バイトのセクター（BIN・MDF）。モード 1 はデータが 16 バイト目から、モード 2 は 24 バイト目から
                    return Raw(raf, 2352, if (head[15].toInt() == 2) 24 else 16)
                }
                if (raf.length() >= 17 * 2048L) return Raw(raf, 2048, 0)
            } catch (e: Exception) {
                raf.close()
                throw e
            }
            raf.close()
            return null
        }

        private fun isSync(h: ByteArray): Boolean =
            h[0] == 0.toByte() && (1..10).all { h[it] == 0xFF.toByte() } && h[11] == 0.toByte()
    }

    private class Raw(private val raf: RandomAccessFile, private val sectorSize: Int, private val dataOffset: Int) : DiscImage() {
        override fun read(lba: Long, count: Int): ByteArray {
            val out = ByteArray(2048 * count)
            if (sectorSize == 2048) {
                raf.seek(lba * 2048)
                raf.readFully(out)
            } else {
                for (i in 0 until count) {
                    raf.seek((lba + i) * sectorSize + dataOffset)
                    raf.readFully(out, i * 2048, 2048)
                }
            }
            return out
        }

        override fun close() = raf.close()
    }

    /** CSO（CISO v1）：ブロックごとに deflate で縮めたもの */
    private class Cso(private val raf: RandomAccessFile) : DiscImage() {
        private val blockSize: Int
        private val align: Int
        private val index: IntArray

        init {
            val h = ByteArray(24).also { raf.seek(0); raf.readFully(it) }
            val bb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
            val total = bb.getLong(8)
            blockSize = bb.getInt(16)
            align = h[21].toInt() and 0xFF
            require(blockSize in 2048..(1 shl 20) && total > 0) { "CSO のヘッダーが壊れています" }
            val blocks = ((total + blockSize - 1) / blockSize).toInt()
            val idx = ByteArray((blocks + 1) * 4).also { raf.seek(24); raf.readFully(it) }
            val ib = ByteBuffer.wrap(idx).order(ByteOrder.LITTLE_ENDIAN)
            index = IntArray(blocks + 1) { ib.getInt(it * 4) }
        }

        private fun block(i: Int): ByteArray {
            val plain = index[i] < 0
            val start = (index[i].toLong() and 0x7FFFFFFF) shl align
            val end = (index[i + 1].toLong() and 0x7FFFFFFF) shl align
            val raw = ByteArray((end - start).toInt().coerceIn(0, blockSize * 2)).also { raf.seek(start); raf.readFully(it) }
            if (plain) return raw.copyOf(blockSize)
            val inf = Inflater(true)
            try {
                inf.setInput(raw)
                val out = ByteArray(blockSize)
                var n = 0
                while (n < blockSize && !inf.finished()) {
                    val r = inf.inflate(out, n, blockSize - n)
                    if (r == 0 && (inf.needsInput() || inf.needsDictionary())) break
                    n += r
                }
                return out
            } finally {
                inf.end()
            }
        }

        override fun read(lba: Long, count: Int): ByteArray {
            val out = ByteArrayOutputStream(2048 * count)
            var pos = lba * 2048
            val end = (lba + count) * 2048
            while (pos < end) {
                val bi = (pos / blockSize).toInt()
                val b = block(bi)
                val inBlock = (pos % blockSize).toInt()
                val n = minOf(blockSize - inBlock.toLong(), end - pos).toInt()
                out.write(b, inBlock, n)
                pos += n
            }
            return out.toByteArray()
        }

        override fun close() = raf.close()
    }
}

/** ISO 9660 のファイルを、パスで探して読む（大文字・小文字は区別しない） */
class Iso9660(private val img: DiscImage) {
    private data class Entry(val name: String, val lba: Long, val size: Long, val dir: Boolean)

    private val root: Entry? = runCatching {
        val pvd = img.read(16, 1)
        if (pvd[0].toInt() != 1 || String(pvd, 1, 5, Charsets.ISO_8859_1) != "CD001") null
        else record(pvd, 156)
    }.getOrNull()

    private fun record(b: ByteArray, off: Int): Entry {
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val lba = bb.getInt(off + 2).toLong() and 0xFFFFFFFFL
        val size = bb.getInt(off + 10).toLong() and 0xFFFFFFFFL
        val flags = b[off + 25].toInt()
        val nameLen = b[off + 32].toInt() and 0xFF
        val name = String(b, off + 33, nameLen, Charsets.ISO_8859_1).substringBefore(';').trimEnd('.')
        return Entry(name, lba, size, flags and 2 != 0)
    }

    private fun list(dir: Entry): List<Entry> {
        val sectors = ((dir.size + 2047) / 2048).toInt().coerceIn(1, 256)
        val data = img.read(dir.lba, sectors)
        val out = mutableListOf<Entry>()
        var off = 0
        while (off < data.size) {
            val len = data[off].toInt() and 0xFF
            if (len == 0) {
                off = (off / 2048 + 1) * 2048 // 次のセクターへ（記録はセクターをまたがない）
                continue
            }
            if (off + len > data.size || len < 34) break
            val nameLen = data[off + 32].toInt() and 0xFF
            // "." と ".." は名前が 0x00・0x01 の 1 バイト
            if (!(nameLen == 1 && (data[off + 33].toInt() == 0 || data[off + 33].toInt() == 1))) out += record(data, off)
            off += len
        }
        return out
    }

    /** "PSP_GAME/PARAM.SFO" のようなパス。大きすぎるファイル（4 MB 超）は読まない */
    fun readFile(path: String): ByteArray? {
        var cur = root ?: return null
        for (part in path.split('/').filter { it.isNotEmpty() }) {
            if (!cur.dir) return null
            cur = list(cur).firstOrNull { it.name.equals(part, true) } ?: return null
        }
        if (cur.dir || cur.size > 4 * 1024 * 1024) return null
        val data = img.read(cur.lba, ((cur.size + 2047) / 2048).toInt().coerceAtLeast(1))
        return data.copyOf(cur.size.toInt())
    }
}
