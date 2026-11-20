package com.emustation.emusync.ui.target

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.emustation.emusync.data.apps.LauncherApp
import com.emustation.emusync.domain.Presets
import com.emustation.emusync.domain.sync.SyncMode
import com.emustation.emusync.domain.sync.SyncResult
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
@Config(qualifiers = "w400dp-h1400dp-xxhdpi")
class TargetEditScreenTest {
    @get:Rule
    val compose = createComposeRule()

    /** ViewModel の代わり：状態を持ち、操作で書き換える */
    private inner class Harness(initial: TargetEditState) : TargetEditActions {
        var state by mutableStateOf(initial)
        val calls = mutableListOf<String>()
        override fun back() { calls += "back" }
        override fun selectPreset(name: String?) {
            val p = name?.let(Presets::byName)
            state = state.copy(
                form = if (p == null) state.form.copy(preset = null, name = "") else
                    state.form.copy(preset = p.name, name = p.name, relSubdir = p.relSubdir, excludesText = p.excludes.joinToString("\n")),
            )
        }
        override fun update(f: (TargetForm) -> TargetForm) { state = state.copy(form = f(state.form)) }
        override fun pickFolder() { calls += "pickFolder" }
        override fun openAppPicker() { state = state.copy(apps = listOf(LauncherApp("org.ppsspp.ppsspp", "PPSSPP"))) }
        override fun selectApp(app: LauncherApp?) { update { it.copy(emuPackage = app?.packageName, emuLabel = app?.label) } }
        override fun requestSave() {
            state = if (state.errors.any) state.copy(showErrors = true) else state.copy(
                check = SaveCheck.Done(SyncResult("psp", SyncMode.SYNC, null, true, listOf("ULJM05800/DATA.BIN"), emptyList(), emptyList(), emptyList())),
            )
        }
        override fun cancelSave() { state = state.copy(check = null) }
        override fun save() { calls += "save" }
        override fun delete() { calls += "delete" }
    }

    private fun show(h: Harness, existingDirs: Set<String> = emptySet()) = compose.setContent {
        EmusyncTheme { TargetEditContent(h.state, h, dirs = { it in existingDirs }) }
    }

    @Test
    fun newPreset_fillsFields_andShowsSyncDir() {
        val h = Harness(TargetEditState(isNew = true, loaded = true, existingNames = setOf("psp")))
        show(h, existingDirs = setOf("/storage/emulated/0/ARMSX2"))
        compose.onNodeWithText("PS2").performClick()
        compose.onNodeWithTag("name").assertIsNotEnabled()
        compose.onNodeWithTag("dataDir").performTextInput("/storage/emulated/0/ARMSX2")
        assertEquals("ps2", h.state.form.name)
        assertEquals("*.bak", h.state.form.excludesText)
        compose.onNodeWithText("/storage/emulated/0/ARMSX2/memcards").assertIsDisplayed()
        compose.onNodeWithText("同期するフォルダはまだありません。エミュでセーブするか、取得すると作られます").assertIsDisplayed()
        compose.onRoot().saveScreenshot("target-new-ps2")

        // 既に追加した psp は選べない
        compose.onNodeWithText("PSP").assertIsNotEnabled()
    }

    @Test
    fun custom_validatesName() {
        val h = Harness(TargetEditState(isNew = true, loaded = true, existingNames = setOf("gba")))
        show(h)
        compose.onNodeWithText("自由入力").performClick()
        compose.onNodeWithTag("name").assertIsEnabled()
        compose.onNodeWithTag("name").performTextInput("ゲーム")
        compose.onNodeWithTag("save").performScrollTo().performClick()
        compose.onNodeWithText("英数字と . _ - だけが使えます").assertIsDisplayed()
        compose.onNodeWithText("データフォルダを入力するか、選んでください").assertIsDisplayed()
        compose.onRoot().saveScreenshot("target-custom-errors")

        compose.onNodeWithTag("name").performTextClearance()
        compose.onNodeWithTag("name").performTextInput("gba")
        compose.onNodeWithText("同じ名前のターゲットがあります").assertIsDisplayed()
    }

    @Test
    fun androidDataWarning_andSaveCheckDialog() {
        val h = Harness(TargetEditState(isNew = true, loaded = true))
        show(h, existingDirs = setOf("/storage/emulated/0/Android/data/org.ppsspp.ppsspp/files/PSP"))
        compose.onNodeWithTag("dataDir").performTextInput("/storage/emulated/0/Android/data/org.ppsspp.ppsspp/files/PSP")
        compose.onNodeWithText("Android/data の中は、他のアプリから読めません。エミュの設定でデータフォルダを移してください")
            .assertIsDisplayed()

        // アプリ選択のダイアログは検索欄（テキスト入力）があり、Robolectric では待ちが終わらないので、
        // 選んだ結果だけを反映する
        h.selectApp(LauncherApp("org.ppsspp.ppsspp", "PPSSPP"))
        compose.onNodeWithText("org.ppsspp.ppsspp").assertIsDisplayed()

        compose.onNodeWithTag("save").performScrollTo().performClick()
        compose.onNodeWithText("この設定で同期すると").assertIsDisplayed()
        compose.onNodeWithText("送信 1").assertIsDisplayed()
        compose.onNode(isDialog()).saveScreenshot("target-save-check")
        compose.onNodeWithText("保存").performClick()
        assertEquals(listOf("save"), h.calls)
    }

    @Test
    fun edit_nameFixed_andDelete() {
        val h = Harness(
            TargetEditState(
                isNew = false, loaded = true,
                form = TargetForm(preset = "psp", name = "psp", dataDir = "/storage/emulated/0/PSP", relSubdir = "SAVEDATA"),
            ),
        )
        show(h, existingDirs = setOf("/storage/emulated/0/PSP", "/storage/emulated/0/PSP/SAVEDATA"))
        compose.onNodeWithText("ターゲットを編集").assertIsDisplayed()
        compose.onNodeWithTag("name").assertIsNotEnabled()
        compose.onRoot().saveScreenshot("target-edit-psp")
        compose.onNodeWithContentDescription("削除").performClick()
        compose.onNodeWithText("psp を削除しますか？").assertIsDisplayed()
        compose.onNode(isDialog()).saveScreenshot("target-delete")
    }
}
