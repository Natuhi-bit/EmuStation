package com.emustation.emusync.desktop.meta

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import javax.imageio.ImageIO

/**
 * 任天堂のゲーム（DS・3DS・Wii）の ID・タイトル・アイコンを ROM から読む。
 * ID は GameTDB と同じもの（DS・3DS は 4 文字の例 ADMJ、Wii は 6 文字の例 RMCJ01）。4 文字目が地域（J＝日本）
 */
object NintendoRom {
    val TARGETS = setOf("nds", "3ds", "wii")

    fun idLength(target: String) = if (target == "wii") 6 else 4

    private val ID4 = Regex("[A-Z0-9]{4}")
    private val ID6 = Regex("[A-Z0-9]{6}")

    fun read(target: String, f: File): RomInfo? = when (target) {
        "nds" -> readNds(f)
        "3ds" -> read3ds(f)
        "wii" -> readWii(f)
        else -> null
    }

    // ---------------------------------------------------------------- DS
    /**
     * DS のヘッダー：0x0C に ID（4 文字）、0x68 にバナーの位置。
     * バナー：0x20 にアイコン（32×32・16 色）、0x220 にパレット、0x240 から各国語のタイトル（UTF-16、0x100 ずつ。最初が日本語）
     */
    fun readNds(f: File): RomInfo? {
        if (f.extension.lowercase() !in setOf("nds", "dsi", "srl")) return null
        return RandomAccessFile(f, "r").use { raf ->
            val h = raf.readAt(0, 0x200) ?: return null
            val id = ascii(h, 0x0C, 4)?.takeIf { ID4.matches(it) }
            val bannerAt = u32le(h, 0x68)
            var title: String? = null
            var icon: ByteArray? = null
            if (bannerAt in 0x200 until raf.length()) {
                raf.readAt(bannerAt, 0x840)?.let { b ->
                    title = utf16le(b, 0x240, 0x100)?.let(::bannerTitle)
                    icon = runCatching { ndsIcon(b) }.getOrNull()
                }
            }
            if (id == null && title == null) null else RomInfo(serial = id, title = title, icon = icon)
        }
    }

    /** バナーのタイトルは「タイトル\nサブタイトル\n発売元」。発売元を除いてつなげる */
    fun bannerTitle(s: String): String? {
        val lines = s.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        return (if (lines.size >= 2) lines.dropLast(1) else lines).joinToString(" ")
    }

    /** 32×32、8×8 のタイルが 4×4、1 画素 4 ビット。パレットは BGR555、0 番は透明 */
    private fun ndsIcon(b: ByteArray): ByteArray {
        val pal = IntArray(16) { i ->
            val c = (b[0x220 + i * 2].toInt() and 0xFF) or ((b[0x221 + i * 2].toInt() and 0xFF) shl 8)
            val r = (c and 0x1F) * 255 / 31
            val g = ((c shr 5) and 0x1F) * 255 / 31
            val bl = ((c shr 10) and 0x1F) * 255 / 31
            (if (i == 0) 0 else 0xFF shl 24) or (r shl 16) or (g shl 8) or bl
        }
        val img = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
        for (tile in 0 until 16) for (py in 0 until 8) for (px in 0 until 8) {
            val idx = 0x20 + tile * 32 + py * 4 + px / 2
            val v = b[idx].toInt() and 0xFF
            val color = if (px % 2 == 0) v and 0x0F else v shr 4
            img.setRGB((tile % 4) * 8 + px, (tile / 4) * 8 + py, pal[color])
        }
        return png(scale(img, 4))
    }

    // ---------------------------------------------------------------- 3DS
    /**
     * 3DS：.3ds・.cci（NCSD。最初のパーティションが NCCH）、.cxi（NCCH そのもの）、.cia（ヘッダーの後ろの最初のコンテンツ）。
     * NCCH の 0x150 に製品コード（CTR-P-AMKJ）。暗号化されていなければ ExeFS の icon（SMDH）から日本語のタイトルとアイコンを読む
     */
    fun read3ds(f: File): RomInfo? = RandomAccessFile(f, "r").use { raf ->
        val ncch = findNcch(raf) ?: return null
        val h = raf.readAt(ncch, 0x200) ?: return null
        val product = ascii(h, 0x150, 16)
        val id = product?.substringAfterLast('-')?.takeIf { ID4.matches(it) }
        var title: String? = null
        var icon: ByteArray? = null
        runCatching { readSmdh(raf, ncch, h) }.getOrNull()?.let { smdh ->
            title = smdhTitle(smdh)
            icon = runCatching { smdhIcon(smdh) }.getOrNull()
        }
        if (id == null && title == null) null else RomInfo(serial = id, title = title, icon = icon)
    }

    private fun findNcch(raf: RandomAccessFile): Long? {
        val h = raf.readAt(0, 0x200) ?: return null
        if (ascii(h, 0x100, 4) == "NCSD") return u32le(h, 0x120) * 0x200
        if (ascii(h, 0x100, 4) == "NCCH") return 0
        // CIA：ヘッダー・証明書・チケット・TMD（それぞれ 64 バイト境界）の後ろ
        val hdr = u32le(h, 0x00)
        if (hdr == 0x2020L) {
            fun align(n: Long) = (n + 63) / 64 * 64
            val off = align(hdr) + align(u32le(h, 0x08)) + align(u32le(h, 0x0C)) + align(u32le(h, 0x10))
            val c = raf.readAt(off, 0x200) ?: return null
            if (ascii(c, 0x100, 4) == "NCCH") return off
        }
        return null
    }

    private fun readSmdh(raf: RandomAccessFile, ncch: Long, h: ByteArray): ByteArray? {
        val flags7 = h[0x188 + 7].toInt() and 0xFF
        if (flags7 and 0x04 == 0) return null // 暗号化されている（読めない）
        val unit = 0x200L shl (h[0x188 + 6].toInt() and 0xFF)
        val exefs = ncch + u32le(h, 0x1A0) * unit
        val fs = raf.readAt(exefs, 0x200) ?: return null
        for (i in 0 until 10) {
            val name = ascii(fs, i * 16, 8) ?: continue
            if (name == "icon") {
                val off = u32le(fs, i * 16 + 8)
                val smdh = raf.readAt(exefs + 0x200 + off, 0x36C0) ?: return null
                return smdh.takeIf { ascii(it, 0, 4) == "SMDH" }
            }
        }
        return null
    }

    /** SMDH：0x08 から 16 言語 × 0x200（短い名前 0x80・長い名前 0x100・発売元 0x80）。最初が日本語 */
    private fun smdhTitle(smdh: ByteArray): String? {
        val long = utf16le(smdh, 0x08 + 0x80, 0x100)?.replace('\n', ' ')?.trim()?.takeIf { it.isNotEmpty() }
        val short = utf16le(smdh, 0x08, 0x80)?.replace('\n', ' ')?.trim()?.takeIf { it.isNotEmpty() }
        return short ?: long
    }

    /** 大きいアイコン：0x24C0 から 48×48、RGB565、8×8 のタイルの中は Z の順 */
    private fun smdhIcon(smdh: ByteArray): ByteArray {
        val img = BufferedImage(48, 48, BufferedImage.TYPE_INT_ARGB)
        var i = 0x24C0
        for (ty in 0 until 6) for (tx in 0 until 6) for (k in 0 until 64) {
            val x = (k and 1) or ((k shr 1) and 2) or ((k shr 2) and 4)
            val y = ((k shr 1) and 1) or ((k shr 2) and 2) or ((k shr 3) and 4)
            val c = (smdh[i].toInt() and 0xFF) or ((smdh[i + 1].toInt() and 0xFF) shl 8)
            i += 2
            val r = ((c shr 11) and 0x1F) * 255 / 31
            val g = ((c shr 5) and 0x3F) * 255 / 63
            val b = (c and 0x1F) * 255 / 31
            img.setRGB(tx * 8 + x, ty * 8 + y, (0xFF shl 24) or (r shl 16) or (g shl 8) or b)
        }
        return png(scale(img, 2))
    }

    // ---------------------------------------------------------------- Wii
    /**
     * Wii：ISO（0x18 が 5D1C9EA3、0 に ID）、WBFS（最初のディスクのヘッダーが 1 セクター目）、RVZ・WIA（0x58 にディスクのヘッダー）。
     * ディスクのヘッダーの 0x20 にゲームの名前（英語）。日本語のタイトルは GameTDB で引く
     */
    fun readWii(f: File): RomInfo? = RandomAccessFile(f, "r").use { raf ->
        val h = raf.readAt(0, 0x100) ?: return null
        val disc = when {
            ascii(h, 0, 4) == "WBFS" -> 1L shl (h[8].toInt() and 0xFF)
            ascii(h, 0, 3) == "RVZ" || ascii(h, 0, 3) == "WIA" -> 0x58L
            u32be(h, 0x18) == 0x5D1C9EA3L -> 0L
            else -> return null
        }
        val d = raf.readAt(disc, 0x60) ?: return null
        val id = ascii(d, 0, 6)?.takeIf { ID6.matches(it) } ?: return null
        val name = ascii(d, 0x20, 0x40)?.trim()?.takeIf { it.isNotEmpty() }
        RomInfo(serial = id, title = name)
    }

    // ---------------------------------------------------------------- ID・地域
    /** ファイル名の中の ID（"Mario Kart Wii [RMCJ01].rvz"・"(ADMJ)"） */
    fun idFromName(target: String, name: String): String? {
        val n = idLength(target)
        return Regex("[\\[(]([A-Z0-9]{$n})[\\])]").find(name)?.groupValues?.get(1)
    }

    /** ID の 4 文字目が地域 */
    fun regionOf(id: String): String? = when (id.getOrNull(3)) {
        'J' -> "日本"
        'E' -> "北米"
        'P', 'D', 'F', 'S', 'I', 'H', 'X', 'Y', 'Z', 'U', 'V' -> "欧州"
        'K' -> "韓国"
        'W' -> "台湾"
        'C' -> "中国"
        'A' -> "全地域"
        else -> null
    }

    /** GameTDB のカバーの言語（地域に合うもの → 日本語 → 英語） */
    fun coverLangs(id: String): List<String> {
        val first = when (id.getOrNull(3)) {
            'J' -> "JA"
            'E' -> "US"
            'K' -> "KO"
            'D' -> "DE"
            'F' -> "FR"
            'S' -> "ES"
            'I' -> "IT"
            'W', 'C' -> "ZH"
            else -> "EN"
        }
        return listOf(first, "JA", "EN", "US").distinct()
    }

    /** GameTDB のタイトルの一覧の「更新データ Ver. 1.2 マリオカート7」のような名前を直す */
    fun cleanTitle(t: String): String = t.replace(Regex("^更新データ\\s*Ver\\.?\\s*[\\d.]+\\s*"), "").trim()

    // ---------------------------------------------------------------- 下回り
    private fun RandomAccessFile.readAt(pos: Long, n: Int): ByteArray? {
        if (pos < 0 || pos + n > length()) return null
        seek(pos)
        return ByteArray(n).also { readFully(it) }
    }

    private fun u32le(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)

    private fun u32be(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

    /** 0 で終わる ASCII。印字できない文字が入っていれば null */
    private fun ascii(b: ByteArray, o: Int, n: Int): String? {
        if (o + n > b.size) return null
        val end = (o until o + n).firstOrNull { b[it].toInt() == 0 } ?: (o + n)
        val s = String(b, o, end - o, Charsets.ISO_8859_1)
        return s.takeIf { it.isNotEmpty() && it.all { c -> c.code in 0x20..0x7E } }
    }

    private fun utf16le(b: ByteArray, o: Int, n: Int): String? {
        if (o + n > b.size) return null
        var end = o
        while (end + 1 < o + n && (b[end].toInt() != 0 || b[end + 1].toInt() != 0)) end += 2
        return String(b, o, end - o, Charsets.UTF_16LE).takeIf { it.isNotBlank() }
    }

    private fun scale(img: BufferedImage, k: Int): BufferedImage {
        val out = BufferedImage(img.width * k, img.height * k, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until out.height) for (x in 0 until out.width) out.setRGB(x, y, img.getRGB(x / k, y / k))
        return out
    }

    private fun png(img: BufferedImage): ByteArray = ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
}
