package com.emustation.emusync.ui.log

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.emustation.emusync.data.db.SyncLogEntity
import com.emustation.emusync.testing.saveScreenshot
import com.emustation.emusync.ui.theme.EmusyncTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h860dp-xxhdpi")
class SyncLogScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val t = 1_763_560_000_000L

    @Test
    fun showsEntries_andExpandsErrors() {
        val entries = listOf(
            SyncLogEntity(5, "ps2", "PUSH", null, false, t + 4, t + 4, 1, 0, 0, emptyList(), null),
            SyncLogEntity(4, "psp", "SYNC", "REMOTE", false, t + 3, t + 3, 0, 1, 0, emptyList(), null),
            SyncLogEntity(3, "ps3", "SYNC", null, false, t + 2, t + 2, 3, 0, 1, listOf("BLUS30443/PARAM.SFO: HTTP 413: 大きすぎます"), null),
            SyncLogEntity(2, "vita", "SYNC", null, true, t + 1, t + 1, 2, 2, 0, emptyList(), null),
            SyncLogEntity(1, "psp", "SYNC", null, false, t, t, 0, 0, 0, emptyList(), "サーバーにつながりません。VPN（WireGuard）を確認してください"),
        )
        compose.setContent { EmusyncTheme { SyncLogContent(entries) {} } }
        compose.onNodeWithText("送信のみ").assertIsDisplayed()
        compose.onNodeWithText("同期 / サーバー版を使う").assertIsDisplayed()
        compose.onNodeWithText("プレビュー").assertIsDisplayed()
        compose.onNodeWithText("サーバーにつながりません。VPN（WireGuard）を確認してください").assertIsDisplayed()
        compose.onNodeWithText("送信 3・競合 1・失敗 1").performClick()
        compose.onNodeWithText("× BLUS30443/PARAM.SFO: HTTP 413: 大きすぎます").assertIsDisplayed()
        compose.onRoot().saveScreenshot("sync-log")
    }

    @Test
    fun empty() {
        compose.setContent { EmusyncTheme { SyncLogContent(emptyList()) {} } }
        compose.onNodeWithText("まだ同期していません").assertIsDisplayed()
    }
}
