package com.emustation.emusync.ui.history

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.emustation.emusync.R
import com.emustation.emusync.data.api.HistoryVersion
import com.emustation.emusync.domain.sync.RemoteFile
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
class HistoryScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val t = 1_763_560_000.0
    private val calls = mutableListOf<String>()

    @Test
    fun filesList_opensFile() {
        val files = listOf(
            RemoteFile("ULJM05800/DATA.BIN", 4, "a", 12345, t, "win-desktop"),
            RemoteFile("ULJM05800/ICON0.PNG", 1, "b", 3000, t, "android-phone"),
        )
        compose.setContent {
            EmusyncTheme {
                HistoryFilesContent(
                    HistoryFilesState("psp", "PSP", Loadable.Loaded(files)),
                    onBack = {}, onQuery = {}, onOpen = { calls += it }, onRetry = {},
                )
            }
        }
        compose.onNodeWithText("PSP の履歴").assertIsDisplayed()
        compose.onRoot().saveScreenshot("history-files")
        compose.onNodeWithText("ULJM05800/DATA.BIN").performClick()
        assertEquals(listOf("ULJM05800/DATA.BIN"), calls)
    }

    @Test
    fun filesList_offline() {
        compose.setContent {
            EmusyncTheme {
                HistoryFilesContent(
                    HistoryFilesState("psp", "PSP", Loadable.Failed(UiText(R.string.conn_offline))),
                    onBack = {}, onQuery = {}, onOpen = {}, onRetry = { calls += "retry" },
                )
            }
        }
        compose.onNodeWithText("サーバーにつながりません。VPN（WireGuard）を確認してください").assertIsDisplayed()
        compose.onNodeWithText("再試行").performClick()
        assertEquals(listOf("retry"), calls)
    }

    private val actions = object : FileHistoryActions {
        override fun back() { calls += "back" }
        override fun retry() { calls += "retry" }
        override fun requestRestore(v: HistoryVersion) { calls += "request v${v.version}" }
        override fun restore() { calls += "restore" }
        override fun pull() { calls += "pull" }
        override fun cancel() { calls += "cancel" }
    }

    private val versions = listOf(
        HistoryVersion(4, "s4", 12345, t + 300, "android-phone(restore v2)"),
        HistoryVersion(3, "s3", 12000, t + 200, "win-desktop"),
        HistoryVersion(2, "s4", 12345, t + 100, "android-phone"),
        HistoryVersion(1, "s1", 11000, t, "win-desktop"),
    )

    @Test
    fun versions_marksLatestAndLocal_andRestores() {
        compose.setContent {
            EmusyncTheme {
                FileHistoryContent(
                    FileHistoryState("psp", "ULJM05800/DATA.BIN", Loadable.Loaded(versions), current = 4, localSha = "s4"),
                    actions,
                )
            }
        }
        compose.onNodeWithText("最新").assertIsDisplayed()
        compose.onRoot().saveScreenshot("file-history")
        // 最新の版には [この版に戻す] を出さない
        compose.onNode(hasText("この版に戻す").and(androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag("version-4"))))
            .assertDoesNotExist()
        compose.onNodeWithText("v3").assertIsDisplayed()
        compose.onNode(hasText("この版に戻す").and(androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag("version-3"))))
            .performClick()
        assertEquals(listOf("request v3"), calls)
    }

    @Test
    fun restoreAndPullDialogs() {
        compose.setContent {
            EmusyncTheme {
                FileHistoryContent(
                    FileHistoryState("psp", "ULJM05800/DATA.BIN", Loadable.Loaded(versions), current = 4, confirmRestore = versions[2]),
                    actions,
                )
            }
        }
        compose.onNodeWithText("v2 に戻しますか？").assertIsDisplayed()
        compose.onNodeWithText("v2 の内容を、新しい版（v5）として最新にします。今までの版は履歴に残ります").assertIsDisplayed()
        compose.onNode(isDialog()).saveScreenshot("restore-confirm")
        compose.onNodeWithText("戻す").performClick()
        assertEquals(listOf("restore"), calls)
    }

    @Test
    fun pullDialog() {
        compose.setContent {
            EmusyncTheme {
                FileHistoryContent(
                    FileHistoryState("psp", "ULJM05800/DATA.BIN", Loadable.Loaded(versions), current = 5, confirmPull = 5),
                    actions,
                )
            }
        }
        compose.onNodeWithText("v5 を取得しますか？").assertIsDisplayed()
        compose.onNode(isDialog()).saveScreenshot("pull-after-restore")
        compose.onNodeWithText("取得する").performClick()
        assertEquals(listOf("pull"), calls)
    }
}
