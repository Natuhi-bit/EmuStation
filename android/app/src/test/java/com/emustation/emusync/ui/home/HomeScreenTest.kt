package com.emustation.emusync.ui.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.emustation.emusync.R
import com.emustation.emusync.domain.sync.SyncConflict
import com.emustation.emusync.domain.sync.SyncMode
import com.emustation.emusync.domain.sync.SyncResult
import com.emustation.emusync.testing.saveScreenshot
import com.emustation.emusync.ui.common.UiText
import com.emustation.emusync.ui.theme.EmusyncTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h860dp-xxhdpi")
class HomeScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val actions = object : HomeActions {
        override fun sync(target: String, mode: SyncMode) { calls += "sync $target $mode" }
        override fun preview(target: String) { calls += "preview $target" }
        override fun edit(target: String) { calls += "edit $target" }
        override fun add() { calls += "add" }
        override fun syncAll() { calls += "syncAll" }
        override fun openSettings() { calls += "settings" }
        override fun requestAllFilesAccess() { calls += "allFiles" }
        override fun requestNotificationPermission() { calls += "notifications" }
        override fun dismissPreview() { calls += "dismissPreview" }
        override fun play(target: String) { calls += "play $target" }
        override fun playForce(target: String) { calls += "playForce $target" }
        override fun playOffline(target: String) { calls += "playOffline $target" }
        override fun playIgnoringConflicts(target: String) { calls += "playIgnoringConflicts $target" }
        override fun abortPlay(target: String) { calls += "abortPlay $target" }
        override fun dismissPlayDialog() { calls += "dismissPlayDialog" }
        override fun finishPlay(target: String) { calls += "finishPlay $target" }
        override fun keepPlaying() { calls += "keepPlaying" }
        override fun openConflicts(target: String) { calls += "conflicts $target" }
        override fun openHistory(target: String) { calls += "history $target" }
    }

    private val time = 1_763_560_000_000L
    private val rows = listOf(
        TargetRow("psp", "PSP", "org.ppsspp.ppsspp", "PPSSPP", false, time, listOf(TargetStatus.Synced), false),
        TargetRow(
            "ps2", "PS2", "xyz.armsx2", "ARMSX2", false, time,
            listOf(TargetStatus.PlayingElsewhere("win-desktop"), TargetStatus.Unsent(2)), true,
        ),
        TargetRow("ps3", "PS3", null, null, false, time, listOf(TargetStatus.Conflicts(1)), false),
        TargetRow("vita", "PS Vita", "x.vita3k", null, true, null, listOf(TargetStatus.DataDirMissing, TargetStatus.NeverSynced), false),
    )

    private fun show(state: HomeUiState) = compose.setContent { EmusyncTheme { HomeContent(state, actions) } }

    @Test
    fun showsRowsAndStatuses() {
        show(HomeUiState(loaded = true, server = ServerState.Connected("android-phone"), rows = rows))
        compose.onNodeWithText("サーバーに接続しました（この端末: android-phone）").assertIsDisplayed()
        compose.onNodeWithText("同期済み").assertIsDisplayed()
        compose.onNodeWithText("win-desktop がプレイ中").assertIsDisplayed()
        compose.onNodeWithText("未送信の変更あり（2 件）").assertIsDisplayed()
        compose.onNodeWithText("同期中").assertIsDisplayed()
        compose.onNodeWithText("競合 1 件").assertIsDisplayed()
        compose.onNodeWithText("エミュ: 未設定").assertIsDisplayed()
        compose.onNodeWithText("エミュがアンインストールされています").assertIsDisplayed()
        compose.onNodeWithText("データフォルダが見つかりません").assertIsDisplayed()
        compose.onNodeWithText("まだ同期していません").assertIsDisplayed()
        compose.onRoot().saveScreenshot("home")
    }

    @Test
    fun rowMenu_callsActions() {
        show(HomeUiState(loaded = true, server = ServerState.Connected("p"), rows = rows))
        compose.onNodeWithTag("menu-psp").performClick()
        compose.onRoot().saveScreenshot("home-menu")
        compose.onNodeWithText("送信のみ").performClick()
        compose.onNodeWithTag("menu-psp").performClick()
        compose.onNodeWithText("プレビュー（dry-run）").performClick()
        compose.onNodeWithTag("menu-psp").performClick()
        compose.onNodeWithText("編集").performClick()
        compose.onNodeWithContentDescription("すべて同期").performClick()
        assertEquals(listOf("sync psp PUSH", "preview psp", "edit psp", "syncAll"), calls)
    }

    @Test
    fun offlineAndPermissionBanners() {
        show(
            HomeUiState(
                loaded = true,
                server = ServerState.Failed(UiText(R.string.conn_offline), offline = true),
                rows = rows.take(1),
                hasAllFilesAccess = false,
                hasNotificationPermission = false,
            ),
        )
        compose.onNodeWithText("サーバーにつながりません。VPN（WireGuard）を確認してください").assertIsDisplayed()
        compose.onNodeWithText("セーブフォルダを読み書きするため、「すべてのファイルへのアクセス」を許可してください").assertIsDisplayed()
        compose.onRoot().saveScreenshot("home-offline")
    }

    @Test
    fun emptyAndNotConfigured() {
        show(HomeUiState(loaded = true, server = ServerState.NotConfigured))
        compose.onNodeWithText("ターゲットがありません。右下の ＋ から追加してください").assertIsDisplayed()
        compose.onNodeWithText("設定").performClick()
        compose.onNodeWithContentDescription("ターゲットを追加").performClick()
        assertEquals(listOf("settings", "add"), calls)
    }

    @Test
    fun previewDialog() {
        val r = SyncResult(
            "ps2", SyncMode.SYNC, null, true,
            uploaded = listOf("Mcd001.ps2"), downloaded = listOf("Mcd002.ps2"),
            conflicts = listOf(SyncConflict("Mcd003.ps2", SyncConflict.Kind.BOTH_CHANGED, 4, "x", null)),
            errors = emptyList(),
        )
        show(HomeUiState(loaded = true, server = ServerState.Connected("p"), rows = rows, preview = PreviewState.Done("PS2", r)))
        compose.onNodeWithText("PS2 のプレビュー").assertIsDisplayed()
        compose.onNodeWithText("送信 1・取得 1・競合 1").assertIsDisplayed()
        compose.onNodeWithText("! Mcd003.ps2").assertIsDisplayed()
        compose.onNode(isDialog()).saveScreenshot("home-preview")
        compose.onNodeWithText("閉じる").performClick()
        assertEquals(listOf("dismissPreview"), calls)
    }

    // ---------------------------------------------------------------- 遊ぶ

    private val playingRows = listOf(
        rows[0].copy(statuses = listOf(TargetStatus.PlayingHere, TargetStatus.Unsent(1)), playingHere = true),
        rows[2],
    )

    @Test
    fun playButton_andFinishButtonWhilePlaying() {
        show(HomeUiState(loaded = true, server = ServerState.Connected("p"), rows = playingRows))
        compose.onNodeWithTag("play-ps3").performClick()
        compose.onNodeWithTag("finish-psp").performClick()
        // プレイ中は、取得するメニューを選べない
        compose.onNodeWithTag("menu-psp").performClick()
        compose.onNodeWithText("今すぐ同期").assertIsNotEnabled()
        compose.onNodeWithText("取得のみ").assertIsNotEnabled()
        compose.onNodeWithText("送信のみ").assertIsEnabled()
        compose.onRoot().saveScreenshot("home-playing-menu")
        assertEquals(listOf("play ps3", "finishPlay psp"), calls)
    }

    @Test
    fun lockedByOtherDialog() {
        show(
            HomeUiState(
                loaded = true, server = ServerState.Connected("p"), rows = rows,
                play = PlayDialog.LockedByOther("ps2", "PS2", "win-laptop", 1_763_600_000.0),
            ),
        )
        compose.onNodeWithText("win-laptop がプレイ中").assertIsDisplayed()
        compose.onNode(isDialog()).saveScreenshot("play-locked")
        compose.onNodeWithText("奪って起動").performClick()
        assertEquals(listOf("playForce ps2"), calls)
    }

    @Test
    fun unreachableDialog() {
        show(
            HomeUiState(
                loaded = true, server = ServerState.Connected("p"), rows = rows,
                play = PlayDialog.Unreachable("psp", "PSP", UiText(R.string.conn_offline)),
            ),
        )
        compose.onNodeWithText("サーバーにつながりません。VPN（WireGuard）を確認してください").assertIsDisplayed()
        compose.onNode(isDialog()).saveScreenshot("play-unreachable")
        compose.onNodeWithText("同期せずに起動").performClick()
        assertEquals(listOf("playOffline psp"), calls)
    }

    @Test
    fun conflictsDialog() {
        val r = SyncResult(
            "ps3", SyncMode.SYNC, null, false, emptyList(), listOf("BLUS30443/ICON0.PNG"),
            listOf(SyncConflict("BLUS30443/SAVE.DAT", SyncConflict.Kind.BOTH_CHANGED, 3, "x", "/c")), emptyList(),
        )
        show(HomeUiState(loaded = true, server = ServerState.Connected("p"), rows = rows, play = PlayDialog.Conflicts("ps3", "PS3", r)))
        compose.onNodeWithText("競合が 1 件あります").assertIsDisplayed()
        compose.onNode(isDialog()).saveScreenshot("play-conflicts")
        compose.onNodeWithText("やめる").performClick()
        compose.onNodeWithText("無視して起動").performClick()
        compose.onNodeWithText("競合を見る").performClick()
        assertEquals(
            listOf("abortPlay ps3", "playIgnoringConflicts ps3", "abortPlay ps3", "conflicts ps3"),
            calls,
        )
    }

    @Test
    fun progressAndErrorDialogs() {
        show(
            HomeUiState(
                loaded = true, server = ServerState.Connected("p"), rows = rows,
                play = PlayDialog.Progress("psp", "PSP", com.emustation.emusync.domain.play.PlayStep.SYNCING),
            ),
        )
        compose.mainClock.autoAdvance = false // 進み具合の丸が回り続けるので、時計を止めて確かめる
        compose.onNodeWithText("セーブを同期しています…").assertExists()
    }

    @Test
    fun resumePrompt() {
        show(
            HomeUiState(
                loaded = true, server = ServerState.Connected("p"), rows = playingRows,
                resumePrompt = ResumePrompt("psp", "PSP", 1_763_560_000_000L, online = true),
            ),
        )
        compose.onNodeWithText("PSP のセーブを送信しますか？").assertIsDisplayed()
        compose.onNode(isDialog()).saveScreenshot("resume-prompt")
        compose.onNodeWithText("まだ遊ぶ").performClick()
        compose.onNodeWithText("送信").performClick()
        assertEquals(listOf("keepPlaying", "finishPlay psp"), calls)
    }

    @Test
    fun conflictChipAndMenu_openScreens() {
        show(HomeUiState(loaded = true, server = ServerState.Connected("p"), rows = rows))
        compose.onNodeWithText("競合 1 件").performClick()
        compose.onNodeWithTag("menu-ps3").performClick()
        compose.onNodeWithText("競合を見る").performClick()
        compose.onNodeWithTag("menu-psp").performClick()
        compose.onNodeWithText("競合を見る").assertDoesNotExist() // 競合がなければ出さない
        compose.onNodeWithText("履歴").performClick()
        assertEquals(listOf("conflicts ps3", "conflicts ps3", "history psp"), calls)
    }
}
