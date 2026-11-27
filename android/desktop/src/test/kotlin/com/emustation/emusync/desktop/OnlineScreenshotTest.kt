package com.emustation.emusync.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.emustation.emusync.desktop.TestRoms.iso
import com.emustation.emusync.desktop.TestRoms.png
import com.emustation.emusync.desktop.TestRoms.sfo
import com.emustation.emusync.desktop.data.AppConfig
import com.emustation.emusync.desktop.data.ConfigStore
import com.emustation.emusync.desktop.data.Game
import com.emustation.emusync.desktop.data.SystemConfig
import com.emustation.emusync.desktop.domain.AppController
import com.emustation.emusync.desktop.meta.GameMetadata
import com.emustation.emusync.desktop.ui.EmusyncTheme
import com.emustation.emusync.desktop.ui.XmbApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.imageio.ImageIO

/** 本物のサイトから情報とカバーを取って、画面を撮る（EMUSYNC_META_ONLINE=1 のときだけ）。画像は build/screenshots/ */
@OptIn(ExperimentalTestApi::class)
class OnlineScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun libraryWithFetchedCovers() {
        assumeTrue(System.getenv("EMUSYNC_META_ONLINE") == "1")
        fun rom(name: String, bytes: ByteArray) = File(tmp.root, name).apply { writeBytes(bytes) }.path
        fun ps2(serialFile: String) = iso(mapOf("SYSTEM.CNF" to TestRoms.ps2SystemCnf(serialFile)))
        val exe = File(tmp.root, "emu.exe").apply { writeText("") }.path
        val store = ConfigStore(File(tmp.root, "config.json"))
        store.save(
            AppConfig()
                .withSystem(SystemConfig("ps2", exe, savePath = tmp.newFolder("ps2").path))
                .withSystem(SystemConfig("psp", exe, savePath = tmp.newFolder("psp").path))
                .withSystem(SystemConfig("ps3", exe, savePath = tmp.newFolder("ps3").path)),
        )
        val app = AppController(store, File(tmp.root, "data"), CoroutineScope(Dispatchers.Default), metadata = GameMetadata(File(tmp.root, "meta")))
        val now = System.currentTimeMillis()
        app.addGames(
            listOf(
                Game(title = "x", target = "ps2", rom = rom("a.iso", ps2("SLPM_661.68")), lastPlayedAt = now - 3_600_000, playSeconds = 45_000),
                Game(title = "x", target = "ps2", rom = rom("Final Fantasy X (Japan).chd", ByteArray(10)), lastPlayedAt = now - 86_400_000),
                Game(title = "x", target = "ps2", rom = rom("b.iso", ps2("SLPM_664.45"))),
                Game(title = "x", target = "ps2", rom = rom("c.iso", ps2("SLPM_655.55"))),
                Game(
                    title = "x", target = "psp",
                    rom = rom("mh.iso", iso(mapOf("PSP_GAME/PARAM.SFO" to sfo(mapOf("DISC_ID" to "ULJM05800", "TITLE" to "モンスターハンターポータブル 3rd")), "PSP_GAME/ICON0.PNG" to png()))),
                ),
                Game(title = "x", target = "ps3", rom = rom("c3.iso", iso(mapOf("PS3_GAME/PARAM.SFO" to sfo(mapOf("TITLE_ID" to "BLJM60051", "TITLE" to "名探偵コナン")))))),
                Game(title = "x", target = "ps3", rom = rom("d3.iso", iso(mapOf("PS3_GAME/PARAM.SFO" to sfo(mapOf("TITLE_ID" to "BLJM60066", "TITLE" to "x")))))),
            ),
        )
        Thread.sleep(500)
        repeat(240) { if (app.infoBusy.value.isEmpty()) return@repeat; Thread.sleep(250) }
        app.config.value.games.forEach { println("  ${it.target} ${it.title} ${it.serial} ${it.region} ${it.coverPath?.let { p -> File(p).name }}") }

        compose.setContent { EmusyncTheme { Box(Modifier.fillMaxSize()) { XmbApp(app, initialCategory = 1, wavePhase = 0.3f) } } }
        compose.waitForIdle()
        val dir = File(System.getProperty("user.dir"), "build/screenshots").apply { mkdirs() }
        fun shot(name: String) = ImageIO.write(compose.onRoot().captureToImage().toAwtImage(), "png", File(dir, "$name.png"))
        shot("online-ps2")
        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight); pressKey(Key.DirectionRight) }
        compose.waitForIdle()
        shot("online-ps3")
        compose.onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
        compose.waitForIdle()
        shot("online-psp")
    }
}
