package com.emustation.emusync.desktop

import com.emustation.emusync.desktop.TestRoms.iso
import com.emustation.emusync.desktop.TestRoms.png
import com.emustation.emusync.desktop.TestRoms.sfo
import com.emustation.emusync.desktop.meta.DatIndex
import com.emustation.emusync.desktop.meta.GameMetadata
import com.emustation.emusync.desktop.meta.MetaSources
import com.emustation.emusync.desktop.meta.Pcsx2GameIndex
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections

/** ROM から型番・タイトル・アイコンを読む／データベースとカバーを取りに行く */
class GameMetadataTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(name: String, bytes: ByteArray) = File(tmp.root, name).apply { parentFile.mkdirs(); writeBytes(bytes) }

    private val pspIso by lazy {
        iso(
            mapOf(
                "PSP_GAME/PARAM.SFO" to sfo(mapOf("DISC_ID" to "ULJM05800", "TITLE" to "モンスターハンターポータブル 3rd", "CATEGORY" to "UG")),
                "PSP_GAME/ICON0.PNG" to png(7),
                "UMD_DATA.BIN" to "ULJM-05800|0001|G".toByteArray(),
            ),
        )
    }

    // ---------------------------------------------------------------- ROM を読む
    @Test
    fun ps2_iso_bin_cso_readSerialFromSystemCnf() {
        val img = iso(mapOf("SYSTEM.CNF" to TestRoms.ps2SystemCnf("SLPM_661.68"), "SLPM_661.68" to ByteArray(10)))
        for ((name, bytes) in listOf("a.iso" to img, "b.bin" to TestRoms.rawBin(img), "c.cso" to TestRoms.cso(img))) {
            assertEquals(name, "SLPM-66168", RomReader.read("ps2", file(name, bytes).path).serial)
        }
    }

    @Test
    fun psp_iso_cso_pbp_readParamSfo() {
        for ((name, bytes) in listOf("mh.iso" to pspIso, "mh.cso" to TestRoms.cso(pspIso))) {
            val r = RomReader.read("psp", file(name, bytes).path)
            assertEquals(name, "ULJM-05800", r.serial)
            assertEquals("モンスターハンターポータブル 3rd", r.title)
            assertArrayEquals(png(7), r.icon)
        }
        val pbp = TestRoms.pbp(sfo(mapOf("DISC_ID" to "NPJH50465", "TITLE" to "ダウンロード版\nサブタイトル")), png(3))
        val r = RomReader.read("psp", file("GAME/EBOOT.PBP", pbp).path)
        assertEquals("NPJH-50465", r.serial)
        assertEquals("ダウンロード版 サブタイトル", r.title)
        assertArrayEquals(png(3), r.icon)
    }

    @Test
    fun ps3_folderAndIso() {
        val dir = tmp.newFolder("Conan", "PS3_GAME", "USRDIR")
        File(dir.parentFile, "PARAM.SFO").writeBytes(sfo(mapOf("TITLE_ID" to "BLJM60051", "TITLE" to "名探偵コナン")))
        File(dir.parentFile, "ICON0.PNG").writeBytes(png(5))
        val eboot = File(dir, "EBOOT.BIN").apply { writeBytes(ByteArray(4)) }
        val r = RomReader.read("ps3", eboot.path)
        assertEquals("BLJM-60051", r.serial)
        assertEquals("名探偵コナン", r.title)
        assertArrayEquals(png(5), r.icon)

        val isoFile = file("game.iso", iso(mapOf("PS3_GAME/PARAM.SFO" to sfo(mapOf("TITLE_ID" to "BLUS30443", "TITLE" to "Demon's Souls")))))
        assertEquals("BLUS-30443", RomReader.read("ps3", isoFile.path).serial)
    }

    @Test
    fun vita_installedInVita3K() {
        val sys = tmp.newFolder("Vita3K", "ux0", "app", "PCSG00001", "sce_sys")
        File(sys, "param.sfo").writeBytes(sfo(mapOf("TITLE_ID" to "PCSG00001", "TITLE" to "アンチャーテッド")))
        File(sys, "icon0.png").writeBytes(png(9))
        val saves = tmp.newFolder("Vita3K", "ux0", "user", "00", "savedata")
        val r = RomReader.read("vita", "pcsg00001", savePath = saves.path)
        assertEquals("PCSG-00001", r.serial)
        assertEquals("アンチャーテッド", r.title)
        assertArrayEquals(png(9), r.icon)
        // インストールしていなくても、型番は分かる
        assertEquals("PCSG-00002", RomReader.read("vita", "PCSG00002").serial)
    }

    @Test
    fun serialFromFileName_andUnreadableFormats() {
        assertEquals("SLPM-66445", RomReader.serialFromName("Persona 3 [SLPM-66445].chd"))
        assertEquals("SLUS-21621", RomReader.serialFromName("SLUS_216.21.Persona 3 FES.iso"))
        assertEquals("ULJM-05800", RomReader.serialFromName("ULJM05800 - MH3rd.cso"))
        assertNull(RomReader.serialFromName("Final Fantasy X (Japan).iso"))
        assertNull(RomReader.serialFromName("ABCD-12345.iso")) // 型番らしくない
        // CHD は中を読めないので、ファイル名から
        assertEquals("SLPM-66168", RomReader.read("ps2", file("龍が如く [SLPM-66168].chd", ByteArray(100)).path).serial)
        // 壊れたファイル・ないファイルでも落ちない
        assertNull(RomReader.read("psp", file("broken.iso", ByteArray(40_000) { 7 }).path).serial)
        assertNull(RomReader.read("ps2", File(tmp.root, "none.iso").path).serial)
        assertNull(RomReader.read("psp", file("bad.cso", "CISO".toByteArray() + ByteArray(30)).path).serial)
    }

    // ---------------------------------------------------------------- データベースとカバー
    private val datPs2 = """
        clrmamepro (
        	name "Sony - PlayStation 2"
        )

        game (
        	name "Ryuu ga Gotoku (Japan)"
        	region "Japan"
        	serial "SLPM-66168"
        	rom ( name "Ryuu ga Gotoku (Japan).iso" size 1 crc 0 serial "SLPM-66168" )
        )
        game (
        	name "Final Fantasy X (Japan)"
        	region "Japan"
        	serial "SLPM-66124"
        )
        game (
        	name "Final Fantasy X (Japan)"
        	region "Japan"
        	serial "SLPS-25050, SLPS-73203"
        	rom ( name "Final Fantasy X (Japan).iso" size 1 crc 0 )
        )
    """.trimIndent()

    private val datPsp = """
        game (
        	name "Monster Hunter Portable 3rd (Japan, Asia)"
        	region "Japan / Asia"
        	serial "ULJM-05800"
        )
    """.trimIndent()

    private val gameIndex = """
        SLPM-66168:
          name: "龍が如く"
          name-sort: "りゅうがごとく"
          name-en: "Ryu Ga Gotoku"
          region: "NTSC-J"
          gameFixes:
            - EETimingHack
        SLPM-66124:
          name: "ファイナルファンタジーX [Ultimate Hits]"
          region: "NTSC-J"
        SLPS-25050:
          name: "ファイナルファンタジーX"
          region: "NTSC-J"
        SLUS-21621:
          name: "Shin Megami Tensei - Persona 3 FES"
          region: "NTSC-U"
    """.trimIndent()

    private lateinit var server: MockWebServer
    private val requests = Collections.synchronizedList(mutableListOf<String>())
    private var covers = mutableMapOf<String, ByteArray>()

    @Before
    fun startServer() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                requests += path
                fun ok(b: ByteArray) = MockResponse.Builder().code(200).body(Buffer().write(b)).build()
                return when {
                    path == "/GameIndex.yaml" -> ok(gameIndex.toByteArray())
                    path == "/dat/redump/Sony%20-%20PlayStation%202.dat" -> ok(datPs2.toByteArray())
                    path == "/dat/redump/Sony%20-%20PlayStation%20Portable.dat" -> ok(datPsp.toByteArray())
                    path == "/tree/Sony_-_PlayStation_Portable" -> ok(
                        """{"tree":[{"path":"Named_Boxarts/Monster Hunter Portable 3rd (Japan) (v1.02).png"},
                           {"path":"Named_Boxarts/Monster Hunter Portable 3rd (Japan, Korea).png"},
                           {"path":"Named_Snaps/x.png"}]}""".toByteArray(),
                    )
                    covers.containsKey(path) -> ok(covers.getValue(path))
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        server.start()
    }

    @After
    fun stopServer() = server.close()

    private fun meta(dir: File = File(tmp.root, "cache")) = GameMetadata(
        dir,
        sources = MetaSources(
            pcsx2GameIndex = server.url("/GameIndex.yaml").toString(),
            libretroDat = server.url("/dat").toString().trimEnd('/'),
            ps2Cover = server.url("/ps2/").toString() + "{serial}.jpg",
            ps3Cover = server.url("/ps3/").toString() + "{lang}/{id}.jpg",
            libretroThumbs = server.url("/thumbs").toString().trimEnd('/'),
            libretroTree = server.url("/tree/").toString() + "{repo}",
        ),
    )

    @Test
    fun ps2_japaneseTitleFromPcsx2_coverBySerial() = runBlocking {
        covers["/ps2/SLPM-66168.jpg"] = TestRoms.jpeg(1)
        val rom = file("x.iso", iso(mapOf("SYSTEM.CNF" to TestRoms.ps2SystemCnf("SLPM_661.68"))))
        val m = meta().lookup("ps2", rom.path)
        assertEquals("SLPM-66168", m.serial)
        assertEquals("龍が如く", m.title)
        assertEquals("日本", m.region)
        assertArrayEquals(TestRoms.jpeg(1), m.cover)
        assertEquals("PCSX2 のカバー集", m.coverSource)
    }

    @Test
    fun ps2_chdNamedLikeRedump_isFoundByName_andCoverFallsBackToLibretro() = runBlocking {
        covers["/thumbs/Sony_-_PlayStation_2/master/Named_Boxarts/Final%20Fantasy%20X%20(Japan).png"] = png(2)
        val rom = file("Final Fantasy X (Japan).chd", ByteArray(10))
        val m = meta().lookup("ps2", rom.path)
        assertEquals("SLPS-25050", m.serial)
        assertEquals("ファイナルファンタジーX", m.title)
        assertArrayEquals(png(2), m.cover)
        assertEquals("libretro", m.coverSource)
    }

    @Test
    fun psp_titleFromRom_coverFromLibretroOtherVariant_preferJapan() = runBlocking {
        covers["/thumbs/Sony_-_PlayStation_Portable/master/Named_Boxarts/Monster%20Hunter%20Portable%203rd%20(Japan)%20(v1.02).png"] = png(4)
        val m = meta().lookup("psp", file("mh.iso", pspIso).path)
        assertEquals("ULJM-05800", m.serial)
        assertEquals("モンスターハンターポータブル 3rd", m.title)
        assertEquals("日本・アジア", m.region)
        assertArrayEquals(png(4), m.cover)
    }

    @Test
    fun ps3_coverFromGameTdb_inJapaneseFirst() = runBlocking {
        covers["/ps3/JA/BLJM60051.jpg"] = TestRoms.jpeg(6)
        covers["/ps3/US/BLJM60051.jpg"] = TestRoms.jpeg(7)
        val dir = tmp.newFolder("Conan", "PS3_GAME", "USRDIR")
        File(dir.parentFile, "PARAM.SFO").writeBytes(sfo(mapOf("TITLE_ID" to "BLJM60051", "TITLE" to "名探偵コナン")))
        val m = meta().lookup("ps3", File(dir, "EBOOT.BIN").apply { writeBytes(ByteArray(1)) }.path)
        assertEquals("名探偵コナン", m.title)
        assertArrayEquals(TestRoms.jpeg(6), m.cover)
        assertEquals("GameTDB", m.coverSource)
    }

    @Test
    fun noCoverOnline_usesIconInRom() = runBlocking {
        val pbp = TestRoms.pbp(sfo(mapOf("DISC_ID" to "NPJH50465", "TITLE" to "自作")), png(8))
        val m = meta().lookup("psp", file("EBOOT.PBP", pbp).path)
        assertArrayEquals(png(8), m.cover)
        assertEquals("ROM のアイコン", m.coverSource)
    }

    @Test
    fun offline_usesCachedDatabases_andRomOnly() = runBlocking {
        val cache = File(tmp.root, "cache")
        val rom = file("x.iso", iso(mapOf("SYSTEM.CNF" to TestRoms.ps2SystemCnf("SLPM_661.68"))))
        meta(cache).lookup("ps2", rom.path) // データベースを置いておく
        server.close()
        val m = meta(cache).lookup("ps2", rom.path)
        assertEquals("龍が如く", m.title) // 置いてあるデータベースから
        assertNull(m.cover)

        // データベースもないとき（初めてでつながらない）は、ROM だけで分かることを返す
        val m2 = offlineMetadata(File(tmp.root, "empty")).lookup("psp", file("mh2.iso", pspIso).path)
        assertEquals("モンスターハンターポータブル 3rd", m2.title)
        assertEquals("日本", m2.region) // 型番から
        assertArrayEquals(png(7), m2.cover)
    }

    @Test
    fun databasesAreCached_notDownloadedEveryTime() = runBlocking {
        val cache = File(tmp.root, "cache")
        val rom = file("x.iso", iso(mapOf("SYSTEM.CNF" to TestRoms.ps2SystemCnf("SLPM_661.68"))))
        meta(cache).lookup("ps2", rom.path)
        meta(cache).lookup("ps2", rom.path)
        assertEquals(1, requests.count { it == "/GameIndex.yaml" })
        assertEquals(1, requests.count { it.endsWith("PlayStation%202.dat") })
    }

    @Test
    fun parsers() {
        val d = DatIndex.parse(datPs2)
        assertEquals("Final Fantasy X (Japan)", d.bySerial("SLPS-73203").single().name)
        assertEquals("Ryuu ga Gotoku (Japan)", d.bySerial("SLPM66168").single().name)
        assertEquals(listOf("SLPM-66124", "SLPS-25050", "SLPS-73203"), d.byName("final fantasy x (japan)").flatMap { it.serials })
        val g = Pcsx2GameIndex.parse(gameIndex)
        assertEquals("龍が如く", g["SLPM-66168"]?.name)
        assertEquals("Ryu Ga Gotoku", g["SLPM-66168"]?.nameEn)
        assertEquals("NTSC-U", g["SLUS-21621"]?.region)
        assertEquals("Monster Hunter Portable 3rd", GameMetadata.baseTitle("Monster Hunter Portable 3rd (Japan, Asia) (v1.02)"))
        assertEquals("A_B_ C", GameMetadata.libretroFileName("A:B? C"))
        assertEquals("日本", GameMetadata.regionOfSerial("PCSG-00001"))
        assertEquals("北米", GameMetadata.regionOfSerial("BLUS-30443"))
        assertEquals("欧州", GameMetadata.regionOfSerial("SLES-50000"))
    }

    /** 本物のサイトで確かめる（EMUSYNC_META_ONLINE=1 のときだけ） */
    @Test
    fun online_realSources() = runBlocking {
        assumeTrue(System.getenv("EMUSYNC_META_ONLINE") == "1")
        val m = GameMetadata(File(tmp.root, "real"))
        val ps2 = m.lookup("ps2", file("a.iso", iso(mapOf("SYSTEM.CNF" to TestRoms.ps2SystemCnf("SLPM_661.68")))).path)
        println("PS2: ${ps2.title} ${ps2.serial} ${ps2.region} ${ps2.coverSource} ${ps2.cover?.size}")
        assertEquals("龍が如く", ps2.title)
        assertTrue(ps2.cover != null)
        val psp = m.lookup("psp", file("mh.iso", pspIso).path)
        println("PSP: ${psp.title} ${psp.serial} ${psp.region} ${psp.coverSource} ${psp.cover?.size}")
        // libretro のカバーの一覧は GitHub API から取る。使えない環境では ROM のアイコンになる
        assertTrue(psp.coverSource == "libretro" || psp.coverSource == "ROM のアイコン")
        val ps3 = m.lookup("ps3", file("c.iso", iso(mapOf("PS3_GAME/PARAM.SFO" to sfo(mapOf("TITLE_ID" to "BLJM60051", "TITLE" to "名探偵コナン"))))).path)
        println("PS3: ${ps3.title} ${ps3.serial} ${ps3.region} ${ps3.coverSource} ${ps3.cover?.size}")
        assertEquals("GameTDB", ps3.coverSource)
        val chd = m.lookup("ps2", file("Final Fantasy X (Japan).chd", ByteArray(10)).path)
        println("CHD: ${chd.title} ${chd.serial} ${chd.region} ${chd.coverSource} ${chd.cover?.size}")
        assertEquals("ファイナルファンタジーⅩ", chd.title) // 廉価版（[Ultimate Hits]）ではなく元の版
    }
}
