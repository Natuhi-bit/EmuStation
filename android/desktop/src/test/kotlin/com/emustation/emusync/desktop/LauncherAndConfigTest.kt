package com.emustation.emusync.desktop

import com.emustation.emusync.desktop.data.AppConfig
import com.emustation.emusync.desktop.data.ConfigStore
import com.emustation.emusync.desktop.data.Game
import com.emustation.emusync.desktop.data.JsonBaseStateStore
import com.emustation.emusync.desktop.data.SystemConfig
import com.emustation.emusync.desktop.domain.LibraryScanner
import com.emustation.emusync.desktop.domain.Presets
import com.emustation.emusync.desktop.platform.Launcher
import com.emustation.emusync.domain.sync.BaseEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LauncherAndConfigTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun buildCommand_substitutesRomAndKeepsSpaces() {
        assertEquals(
            listOf("C:\\Emu\\PCSX2\\pcsx2-qt.exe", "-batch", "--", "D:\\PS2\\Persona 3 FES.iso"),
            Launcher.buildCommand("C:\\Emu\\PCSX2\\pcsx2-qt.exe", "-batch -- \"{rom}\"", "D:\\PS2\\Persona 3 FES.iso"),
        )
        assertEquals(listOf("Vita3K.exe", "-r", "PCSG00001"), Launcher.buildCommand("Vita3K.exe", "-r {rom}", "PCSG00001"))
        // ゲームなしで起動するときは {rom} を含む引数を落とす
        assertEquals(listOf("rpcs3.exe", "--no-gui"), Launcher.buildCommand("rpcs3.exe", "--no-gui \"{rom}\"", null))
        assertEquals(listOf("a", "b c", "", "d"), Launcher.splitArgs("a \"b c\" \"\" d"))
    }

    @Test
    fun runAndWait_waitsForProcess() = runBlocking {
        var started = false
        val secs = Launcher.runAndWait(listOf("sh", "-c", "sleep 1")) { started = true }
        assertEquals(true, started)
        assertEquals(true, secs >= 1)
    }

    @Test
    fun config_roundTrip_tokenIsNotStoredInPlainText() {
        val f = File(tmp.root, "config.json")
        val store = ConfigStore(f)
        val c = AppConfig(serverUrl = "https://192.168.50.49/emusync")
            .withToken(" secret-token ")
            .withSystem(SystemConfig("ps2", "C:\\Emu\\pcsx2-qt.exe", "-batch -- \"{rom}\"", "C:\\memcards", listOf("*.bak")))
            .withGame(Game(id = "g1", title = "ペルソナ3", target = "ps2", rom = "D:\\p3.iso"))
        store.save(c)
        val loaded = store.load()
        assertEquals("secret-token", loaded.token)
        assertEquals(c.systems, loaded.systems)
        assertEquals("ペルソナ3", loaded.games.single().title)
        assertEquals(false, f.readText().contains("secret-token"))
    }

    @Test
    fun baseState_isCompatibleWithPythonClient() = runBlocking {
        // Python 版の state/<ターゲット>.json と同じ形
        val dir = tmp.newFolder("state-dir")
        File(dir, "state").mkdirs()
        File(dir, "state/psp.json").writeText("""{"ULJM05800/DATA.BIN": {"sha256": "ab", "version": 3}}""")
        val store = JsonBaseStateStore(dir)
        assertEquals(mapOf("ULJM05800/DATA.BIN" to BaseEntry("ab", 3)), store.load("psp"))
        store.save("psp", mapOf("x" to BaseEntry("cd", 1)))
        val raw = kotlinx.serialization.json.Json.parseToJsonElement(File(dir, "state/psp.json").readText())
        assertEquals("""{"x":{"sha256":"cd","version":1}}""", raw.toString())
    }

    @Test
    fun scanner_findsRomsAndCleansTitles() {
        val ps2 = tmp.newFolder("ps2")
        File(ps2, "Persona 3 FES (Japan) [SLPM-66445].iso").writeText("x")
        File(ps2, "sub").mkdirs()
        File(ps2, "sub/Okami.CHD").writeText("x")
        File(ps2, "readme.txt").writeText("x")
        val found = LibraryScanner.scan(Presets.byTarget("ps2")!!, ps2)
        assertEquals(listOf("Okami", "Persona 3 FES"), found.map { it.title })

        val ps3 = tmp.newFolder("ps3")
        File(ps3, "Demon's Souls [BLJS10001]/PS3_GAME/USRDIR").mkdirs()
        File(ps3, "Demon's Souls [BLJS10001]/PS3_GAME/USRDIR/EBOOT.BIN").writeText("x")
        val p3 = LibraryScanner.scan(Presets.byTarget("ps3")!!, ps3).single()
        assertEquals("Demon's Souls", p3.title)
        assertEquals(true, p3.rom.endsWith("EBOOT.BIN"))

        val vita = tmp.newFolder("vita")
        File(vita, "ux0/app/PCSG00001").mkdirs()
        File(vita, "ux0/app/NOTATITLE").mkdirs()
        assertEquals(listOf("PCSG00001"), LibraryScanner.scan(Presets.byTarget("vita")!!, vita).map { it.rom })
    }
}
