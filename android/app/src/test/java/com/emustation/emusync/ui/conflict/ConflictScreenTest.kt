package com.emustation.emusync.ui.conflict

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.emustation.emusync.data.db.ConflictEntity
import com.emustation.emusync.domain.sync.Prefer
import com.emustation.emusync.domain.sync.RemoteFile
import com.emustation.emusync.testing.saveScreenshot
import com.emustation.emusync.ui.theme.EmusyncTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h900dp-xxhdpi")
class ConflictScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val actions = object : ConflictActions {
        override fun back() { calls += "back" }
        override fun resolve(rels: List<String>, prefer: Prefer) { calls += "resolve $rels $prefer" }
        override fun confirm() { calls += "confirm" }
        override fun cancel() { calls += "cancel" }
    }

    private val t = 1_763_560_000_000L
    private fun item(rel: String, kind: String = "BOTH_CHANGED") = ConflictItem(
        rel,
        ConflictEntity("ps2", rel, kind, 4, "x", "/c/$rel.server-v4", t),
        LocalFileInfo(8_650_752, t),
        RemoteFile(rel, 4, "x", 8_650_752, t / 1000.0 + 60, "win-desktop"),
    )

    private val state = ConflictUiState(
        "ps2", "PS2", loaded = true,
        items = listOf(item("Mcd001.ps2"), item("Mcd002.ps2", "UPLOAD_REJECTED")),
        server = ServerView.Loaded(emptyMap()),
    )

    @Test
    fun showsBothSides_andResolvesPerFileOrAll() {
        compose.setContent { EmusyncTheme { ConflictContent(state, actions) } }
        compose.onNodeWithText("PS2 の競合").assertIsDisplayed()
        compose.onNodeWithText("2 件まとめて").assertIsDisplayed()
        compose.onNodeWithText("送信中に他の端末が更新しました").assertIsDisplayed()
        compose.onRoot().saveScreenshot("conflicts")

        compose.onNodeWithTag("keep-local-Mcd001.ps2").performClick()
        compose.onNodeWithTag("use-remote-Mcd002.ps2").performClick()
        compose.onNodeWithText("すべてサーバー版を使う").performClick()
        assertEquals(
            listOf(
                "resolve [Mcd001.ps2] LOCAL",
                "resolve [Mcd002.ps2] REMOTE",
                "resolve [Mcd001.ps2, Mcd002.ps2] REMOTE",
            ),
            calls,
        )
    }

    @Test
    fun confirmDialog() {
        compose.setContent {
            EmusyncTheme { ConflictContent(state.copy(confirm = ResolveRequest(listOf("Mcd001.ps2"), Prefer.REMOTE)), actions) }
        }
        compose.onNodeWithText("サーバー版にします（1 件）").assertIsDisplayed()
        compose.onNode(isDialog()).saveScreenshot("conflict-confirm")
        compose.onNodeWithText("OK").performClick()
        assertEquals(listOf("confirm"), calls)
    }

    @Test
    fun whilePlaying_serverVersionIsDisabled() {
        compose.setContent { EmusyncTheme { ConflictContent(state.copy(playing = true), actions) } }
        compose.onNodeWithTag("use-remote-Mcd001.ps2").assertIsNotEnabled()
        compose.onNodeWithTag("keep-local-Mcd001.ps2").assertIsEnabled()
    }

    @Test
    fun empty() {
        compose.setContent { EmusyncTheme { ConflictContent(ConflictUiState("psp", "PSP", loaded = true), actions) } }
        compose.onNodeWithText("競合はありません").assertIsDisplayed()
    }
}
