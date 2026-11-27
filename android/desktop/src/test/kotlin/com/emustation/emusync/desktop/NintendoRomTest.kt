package com.emustation.emusync.desktop

import com.emustation.emusync.desktop.TestRoms.jpeg
import com.emustation.emusync.desktop.TestRoms.png
import com.emustation.emusync.desktop.domain.LibraryScanner
import com.emustation.emusync.desktop.domain.Presets
import com.emustation.emusync.desktop.meta.GameMetadata
import com.emustation.emusync.desktop.meta.MetaSources
import com.emustation.emusync.desktop.meta.NintendoRom
import com.emustation.emusync.desktop.meta.RomReader
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections
import javax.imageio.ImageIO

/** DS・3DS・Wii：ROM から ID・タイトル・アイコンを読む／GameTDB で日本語のタイトルとカバーを探す */
class NintendoRomTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(name: String, bytes: ByteArray) = File(tmp.root, name).apply { parentFile.mkdirs(); writeBytes(bytes) }

    private fun ByteArray.put(off: Int, s: String) = s.toByteArray(Charsets.ISO_8859_1).copyInto(this, off)
    private fun ByteArray.putUtf16(off: Int, s: String) = s.toByteArray(Charsets.UTF_16LE).copyInto(this, off)
    private fun ByteArray.u32le(off: Int, v: Long) { for (i in 0 until 4) this[off + i] = (v shr (8 * i)).toByte() }
    private fun ByteArray.u32be(off: Int, v: Long) { for (i in 0 until 4) this[off + i] = (v shr (8 * (3 - i))).toByte() }

    // ---------------------------------------------------------------- ROM の形
    /** DS：ヘッダー（0x0C に ID、0x68 にバナーの位置）＋バナー（アイコン・パレット・日本語のタイトル） */
    private fun nds(id: String, jpTitle: String): ByteArray {
        val b = ByteArray(0x200 + 0x840)
        b.put(0, "GAMETITLE")
        b.put(0x0C, id)
        b.u32le(0x68, 0x200)
        val banner = 0x200
        for (i in 0 until 512) b[banner + 0x20 + i] = (i % 16 * 17).toByte() // アイコンの画素
        for (i in 1 until 16) { b[banner + 0x220 + i * 2] = (i * 2).toByte(); b[banner + 0x221 + i * 2] = 0x7C } // パレット
        b.putUtf16(banner + 0x240, jpTitle)
        return b
    }

    /** 3DS：NCSD（最初のパーティション）→ NCCH（製品コード・暗号化なし）→ ExeFS の icon → SMDH */
    private fun cci(product: String, jpShort: String?): ByteArray {
        val b = ByteArray(0x600 + 0x36C0)
        b.put(0x100, "NCSD")
        b.u32le(0x120, 1) // パーティション 0 は 1 × 0x200
        val ncch = 0x200
        b.put(ncch + 0x100, "NCCH")
        b.put(ncch + 0x150, product)
        if (jpShort != null) {
            b[ncch + 0x188 + 7] = 0x04 // NoCrypto
            b.u32le(ncch + 0x1A0, 1) // ExeFS は NCCH から 1 × 0x200
            val exefs = ncch + 0x200
            b.put(exefs, "icon")
            b.u32le(exefs + 8, 0)
            b.u32le(exefs + 12, 0x36C0)
            val smdh = exefs + 0x200
            b.put(smdh, "SMDH")
            b.putUtf16(smdh + 0x08, jpShort)
            for (i in 0 until 48 * 48) { b[smdh + 0x24C0 + i * 2] = 0x1F; b[smdh + 0x24C1 + i * 2] = 0xF8.toByte() }
        }
        return b
    }

    /** CIA：ヘッダー（0x2020）・証明書・チケット・TMD の後ろに NCCH（暗号化されている） */
    private fun cia(product: String): ByteArray {
        fun align(n: Int) = (n + 63) / 64 * 64
        val off = align(0x2020) + align(0xA00) + align(0x350) + align(0x208)
        val b = ByteArray(off + 0x200)
        b.u32le(0, 0x2020); b.u32le(0x08, 0xA00); b.u32le(0x0C, 0x350); b.u32le(0x10, 0x208)
        b.put(off + 0x100, "NCCH")
        b.put(off + 0x150, product)
        return b
    }

    private fun wiiIso(id: String, name: String) = ByteArray(0x8000).apply { put(0, id); u32be(0x18, 0x5D1C9EA3); put(0x20, name) }

    private fun wbfs(id: String) = ByteArray(0x400).apply { put(0, "WBFS"); this[8] = 9; put(0x200, id); put(0x220, "WBFS GAME") }

    private fun rvz(id: String) = ByteArray(0x200).apply { put(0, "RVZ"); this[3] = 1; put(0x58, id); put(0x78, "RVZ GAME") }

    // ---------------------------------------------------------------- ROM を読む
    @Test
    fun ds_readsIdJapaneseTitleAndIcon() {
        val r = RomReader.read("nds", file("どうぶつの森.nds", nds("ADMJ", "おいでよ どうぶつの森\nNintendo")).path)
        assertEquals("ADMJ", r.serial)
        assertEquals("おいでよ どうぶつの森", r.title) // 発売元の行は除く
        val icon = ImageIO.read(r.icon!!.inputStream())
        assertEquals(128, icon.width) // 32×32 を 4 倍
        // タイトルが 3 行（タイトル・サブタイトル・発売元）
        assertEquals("ポケットモンスター ダイヤモンド", NintendoRom.bannerTitle("ポケットモンスター\nダイヤモンド\nPokémon"))
    }

    @Test
    fun threeDs_cciCxiCia() {
        val r = RomReader.read("3ds", file("mk7.3ds", cci("CTR-P-AMKJ", "マリオカート7")).path)
        assertEquals("AMKJ", r.serial)
        assertEquals("マリオカート7", r.title)
        assertEquals(96, ImageIO.read(r.icon!!.inputStream()).width) // 48×48 を 2 倍
        // 暗号化されていれば、ID だけ
        val enc = RomReader.read("3ds", file("enc.cci", cci("CTR-P-AREJ", null)).path)
        assertEquals("AREJ", enc.serial)
        assertNull(enc.title)
        // .cxi は NCCH そのもの
        val cxi = cci("CTR-P-AMKJ", "マリオカート7").copyOfRange(0x200, 0x600 + 0x36C0)
        assertEquals("AMKJ", RomReader.read("3ds", file("mk7.cxi", cxi).path).serial)
        // .cia
        assertEquals("ECDJ", RomReader.read("3ds", file("game.cia", cia("CTR-N-ECDJ")).path).serial)
    }

    @Test
    fun wii_isoWbfsRvz() {
        val iso = RomReader.read("wii", file("mkw.iso", wiiIso("RMCJ01", "MARIO KART Wii")).path)
        assertEquals("RMCJ01", iso.serial)
        assertEquals("MARIO KART Wii", iso.title)
        assertEquals("SB4J01", RomReader.read("wii", file("g.wbfs", wbfs("SB4J01")).path).serial)
        assertEquals("RSBJ01", RomReader.read("wii", file("g.rvz", rvz("RSBJ01")).path).serial)
        // ゲームキューブのディスク（Wii の印がない）は読まない。ファイル名の ID を使う
        val gc = ByteArray(0x100).apply { put(0, "GALJ01") }
        assertEquals("RMGJ01", RomReader.read("wii", file("Mario Galaxy [RMGJ01].iso", gc).path).serial)
    }

    @Test
    fun idFromName_regionAndLangs() {
        assertEquals("RMCJ01", NintendoRom.idFromName("wii", "Mario Kart Wii (Japan) [RMCJ01].rvz"))
        assertEquals("ADMJ", NintendoRom.idFromName("nds", "Oide yo Doubutsu no Mori (ADMJ).nds"))
        assertNull(NintendoRom.idFromName("nds", "Game (Japan).nds")) // (Japan) は ID ではない
        assertEquals("日本", NintendoRom.regionOf("RMCJ01"))
        assertEquals("北米", NintendoRom.regionOf("ADME"))
        assertEquals("欧州", NintendoRom.regionOf("AMKP"))
        assertEquals(listOf("JA", "EN", "US"), NintendoRom.coverLangs("ADMJ"))
        assertEquals(listOf("US", "JA", "EN"), NintendoRom.coverLangs("ADME"))
        assertEquals("マリオカート7", NintendoRom.cleanTitle("更新データ Ver. 1.2 マリオカート7"))
    }

    @Test
    fun presets_andLibraryScan() {
        for (t in listOf("nds", "3ds", "wii")) assertNotNull(t, Presets.byTarget(t))
        assertEquals("DS", Presets.machineOf("nds"))
        val dir = tmp.newFolder("roms")
        File(dir, "a.nds").writeBytes(ByteArray(4))
        File(dir, "a.sav").writeBytes(ByteArray(4))
        File(dir, "b.rvz").writeBytes(ByteArray(4))
        assertEquals(listOf("a"), LibraryScanner.scan(Presets.byTarget("nds")!!, dir).map { it.title })
        assertEquals(listOf("b"), LibraryScanner.scan(Presets.byTarget("wii")!!, dir).map { it.title })
        // DS のセーブのフォルダにゲームがあっても、ゲーム本体は送らない
        val ex = com.emustation.emusync.data.storage.ExcludeMatcher(Presets.byTarget("nds")!!.excludes)
        assertTrue(ex.isExcluded("a.nds"))
        assertTrue(!ex.isExcluded("a.sav"))
        // 3DS のインストールしたゲームの本体（content）は送らない。セーブ（data）は送る
        val ex3 = com.emustation.emusync.data.storage.ExcludeMatcher(Presets.byTarget("3ds")!!.excludes)
        assertTrue(ex3.isExcluded("title/00040000/00030700/content/00000000.app"))
        assertTrue(!ex3.isExcluded("title/00040000/00030700/data/00000001.sav"))
        assertTrue(!ex3.isExcluded("extdata/00000000/00000898/user/save.dat"))
    }

    // ---------------------------------------------------------------- GameTDB
    private lateinit var server: MockWebServer
    private val requests = Collections.synchronizedList(mutableListOf<String>())
    private val art = mutableMapOf<String, ByteArray>()

    @Before
    fun startServer() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                requests += path
                fun ok(b: ByteArray) = MockResponse.Builder().code(200).body(Buffer().write(b)).build()
                return when {
                    path == "/tdb/wiitdb.txt" -> ok("TITLES = https://www.gametdb.com (type: Wii language: JA)\nRMCJ01 = マリオカートWii\nRMCE01 = Mario Kart Wii\n".toByteArray())
                    path == "/tdb/dstdb.txt" -> ok("TITLES = x\nADMJ = おいでよ　どうぶつの森\n".toByteArray())
                    path == "/tdb/3dstdb.txt" -> ok("TITLES = x\nAMKJ = 更新データ Ver. 1.2 マリオカート7\n".toByteArray())
                    path.startsWith("/art/") -> art[path.removePrefix("/art/")]?.let(::ok) ?: MockResponse.Builder().code(404).build()
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        server.start()
    }

    @After
    fun stopServer() = server.close()

    private fun meta() = GameMetadata(
        File(tmp.root, "cache"),
        sources = MetaSources(
            gametdbTitles = server.url("/tdb/").toString() + "{db}.txt",
            gametdbArt = server.url("/art").toString(),
        ),
    )

    @Test
    fun gametdb_japaneseTitleAndCover() = runBlocking {
        art["wii/cover/JA/RMCJ01.png"] = png(11)
        art["ds/coverM/JA/ADMJ.jpg"] = jpeg(12)
        art["3ds/coverM/EN/AMKJ.jpg"] = jpeg(13) // 日本語の絵がなければ英語
        val m = meta()

        val wii = m.lookup("wii", file("mkw.iso", wiiIso("RMCJ01", "MARIO KART Wii")).path)
        assertEquals("マリオカートWii", wii.title)
        assertEquals("日本", wii.region)
        assertArrayEquals(png(11), wii.cover)
        assertEquals("GameTDB", wii.coverSource)

        val ds = m.lookup("nds", file("dm.nds", nds("ADMJ", "どうぶつの森\nNintendo")).path)
        assertEquals("おいでよ　どうぶつの森", ds.title)
        assertArrayEquals(jpeg(12), ds.cover)

        val n3 = m.lookup("3ds", file("mk7.3ds", cci("CTR-P-AMKJ", "マリオカート7")).path)
        assertEquals("マリオカート7", n3.title) // 「更新データ Ver. 1.2」を外す
        assertArrayEquals(jpeg(13), n3.cover)

        // 一覧は 1 回だけ取る（キャッシュ）
        m.lookup("wii", file("mkw2.iso", wiiIso("RMCE01", "MARIO KART Wii")).path)
        assertEquals(1, requests.count { it == "/tdb/wiitdb.txt" })
    }

    @Test
    fun offline_usesTitleAndIconFromRom() = runBlocking {
        server.close() // つながらない
        val ds = meta().lookup("nds", file("dm.nds", nds("ADMJ", "おいでよ どうぶつの森\nNintendo")).path)
        assertEquals("おいでよ どうぶつの森", ds.title)
        assertEquals("ROM のアイコン", ds.coverSource)
        assertEquals("日本", ds.region)
    }
}
