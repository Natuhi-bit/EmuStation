package com.emustation.emusync.desktop

import com.emustation.emusync.desktop.TestRoms.iso
import com.emustation.emusync.desktop.TestRoms.png
import com.emustation.emusync.desktop.TestRoms.sfo
import com.emustation.emusync.desktop.data.AppConfig
import com.emustation.emusync.desktop.data.ConfigStore
import com.emustation.emusync.desktop.data.Game
import com.emustation.emusync.desktop.domain.AppController
import com.emustation.emusync.desktop.ui.buildCategories
import com.emustation.emusync.desktop.domain.ServerStatus
import com.emustation.emusync.desktop.data.SystemConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** ゲームを加えたら情報とカバーを調べて、ライブラリに入れる。自分で書き換えたものは上書きしない */
class GameInfoTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun app(config: AppConfig = AppConfig()): AppController {
        val store = ConfigStore(File(tmp.root, "config.json")).apply { save(config) }
        return AppController(store, File(tmp.root, "data"), CoroutineScope(Dispatchers.Default), metadata = offlineMetadata(File(tmp.root, "meta")))
    }

    private fun pspRom(name: String = "mh.iso") = File(tmp.root, name).apply {
        writeBytes(
            iso(
                mapOf(
                    "PSP_GAME/PARAM.SFO" to sfo(mapOf("DISC_ID" to "ULJM05800", "TITLE" to "モンスターハンターポータブル 3rd")),
                    "PSP_GAME/ICON0.PNG" to png(7),
                ),
            ),
        )
    }

    private fun AppController.waitInfo(id: String) = runBlocking {
        repeat(200) {
            val g = config.value.games.first { it.id == id }
            if (g.infoCheckedAt != null && id !in infoBusy.value) return@runBlocking
            kotlinx.coroutines.delay(25)
        }
        error("情報の取得が終わらない")
    }

    @Test
    fun addGame_fillsTitleSerialRegionAndCover() {
        val app = app()
        val g = Game(title = "mh", target = "psp", rom = pspRom().path)
        app.addGames(listOf(g))
        app.waitInfo(g.id)
        val got = app.config.value.games.single()
        assertEquals("モンスターハンターポータブル 3rd", got.title)
        assertEquals("ULJM-05800", got.serial)
        assertEquals("日本", got.region)
        assertArrayEquals(png(7), File(got.coverPath!!).readBytes())
        assertTrue(got.coverPath!!.startsWith(File(tmp.root, "data/covers").path))
        // 保存もされている
        assertEquals(got, ConfigStore(File(tmp.root, "config.json")).load().games.single())
    }

    @Test
    fun editedTitleAndCover_areKept_unlessFetchedAgain() {
        val myCover = File(tmp.root, "my.png").apply { writeBytes(png(1)) }
        val app = app()
        val g = Game(title = "モンハン", target = "psp", rom = pspRom().path, titleEdited = true, coverPath = myCover.path, coverEdited = true)
        app.addGames(listOf(g))
        app.waitInfo(g.id)
        var got = app.config.value.games.single()
        assertEquals("モンハン", got.title)
        assertEquals(myCover.path, got.coverPath)
        assertEquals("ULJM-05800", got.serial) // 型番は入る

        // 「取得し直す」は、自分で変えたものも置き換える
        app.fetchInfo(listOf(g.id), again = true)
        Thread.sleep(50)
        app.waitInfo(g.id)
        got = app.config.value.games.single()
        assertEquals("モンスターハンターポータブル 3rd", got.title)
        assertNotEquals(myCover.path, got.coverPath)
        assertFalse(got.titleEdited)
        assertTrue(myCover.isFile) // 自分の画像は消さない
    }

    @Test
    fun alreadyChecked_isNotFetchedAgain_andOldAutoCoverIsReplaced() {
        val app = app()
        val g = Game(title = "mh", target = "psp", rom = pspRom().path)
        app.addGames(listOf(g))
        app.waitInfo(g.id)
        val first = app.config.value.games.single()
        app.fetchInfo() // まだのものだけ：何もしない
        Thread.sleep(200)
        assertEquals(first, app.config.value.games.single())
        Thread.sleep(5)
        app.fetchInfo(again = true)
        Thread.sleep(50)
        app.waitInfo(g.id)
        val second = app.config.value.games.single()
        assertNotEquals(first.coverPath, second.coverPath)
        assertFalse("前のカバーは消す", File(first.coverPath!!).exists())
    }

    @Test
    fun hardwareColumns_onlyForHardwareWithGamesOrEmulator() {
        val c = AppConfig()
            .withSystem(SystemConfig("ps3", "C:\\RPCS3\\rpcs3.exe"))
            .withGame(Game(title = "a", target = "psp", rom = "a.iso"))
            .withGame(Game(title = "b", target = "ps2", rom = "b.iso"))
        val ids = buildCategories(c, ServerStatus.NotConfigured, emptyMap(), null) {}.map { it.id }
        assertEquals(listOf("settings", "hw-ps2", "hw-psp", "hw-ps3", "saves", "network"), ids)
        // 何もなければ「ゲーム」の列（追加の入口）だけ
        assertEquals(listOf("settings", "game", "saves", "network"), buildCategories(AppConfig(), ServerStatus.NotConfigured, emptyMap(), null) {}.map { it.id })
    }

    @Test
    fun concurrentUpdates_areNotLost() {
        // 画面の操作（ゲームの追加）と、裏の処理（情報の取得・プレイ時間の記録）が同時に設定を書き換えても、どちらも残る
        val app = app()
        val threads = (0 until 8).map { t ->
            Thread { repeat(100) { i -> app.saveGame(Game(id = "g$t-$i", title = "x", target = "psp", rom = "r")) } }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(800, app.config.value.games.size)
        assertEquals(800, ConfigStore(File(tmp.root, "config.json")).load().games.size)
    }
}
