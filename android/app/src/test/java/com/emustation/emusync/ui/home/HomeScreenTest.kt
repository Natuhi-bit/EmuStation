package com.emustation.emusync.ui.home

import androidx.compose.ui.test.assertIsDisplayed
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
        compose.onNodeWithText("サーバーにつながりません。VPN（Tailscale）を確認してください").assertIsDisplayed()
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
}
