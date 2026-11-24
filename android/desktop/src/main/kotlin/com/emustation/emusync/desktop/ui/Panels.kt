package com.emustation.emusync.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.emustation.emusync.data.api.HistoryApi
import com.emustation.emusync.data.api.HistoryVersion
import com.emustation.emusync.desktop.data.Game
import com.emustation.emusync.desktop.data.SystemConfig
import com.emustation.emusync.desktop.domain.AppController
import com.emustation.emusync.desktop.domain.LibraryScanner
import com.emustation.emusync.desktop.domain.Messages
import com.emustation.emusync.desktop.domain.Presets
import com.emustation.emusync.desktop.domain.ServerStatus
import com.emustation.emusync.desktop.domain.SyncOutcome
import com.emustation.emusync.desktop.platform.Autostart
import com.emustation.emusync.domain.sync.Prefer
import com.emustation.emusync.domain.sync.RemoteFile
import com.emustation.emusync.domain.sync.SyncMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/** 右から出る設定・詳細の画面 */
sealed interface Panel {
    data object Server : Panel
    data class System(val target: String) : Panel
    data object Startup : Panel
    data object Theme : Panel
    data object About : Panel
    data class EditGame(val gameId: String?, val target: String? = null) : Panel
    data class Scan(val target: String? = null) : Panel
    data class Preview(val target: String) : Panel
    data class Conflicts(val target: String) : Panel
    data class History(val target: String) : Panel
    data class FileHistory(val target: String, val rel: String) : Panel
    data object Log : Panel
}

private val timeFmt = SimpleDateFormat("yyyy/MM/dd HH:mm")
fun formatTime(ms: Long?): String = ms?.let { timeFmt.format(Date(it)) } ?: "—"

fun formatSize(n: Long): String = when {
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> "%.1f KB".format(n / 1024.0)
    else -> "%.1f MB".format(n / (1024.0 * 1024.0))
}

fun formatPlayTime(sec: Long): String = when {
    sec < 60 -> "1分未満"
    sec < 3600 -> "${sec / 60}分"
    else -> "${sec / 3600}時間${(sec % 3600) / 60}分"
}

@Composable
fun PanelScaffold(title: String, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxHeight().width(600.dp).background(Color(0xF00B111B))) {
        Column(Modifier.fillMaxHeight().padding(start = 32.dp, end = 24.dp, top = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = XmbText.primary, fontSize = 22.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose, modifier = Modifier.testTag("panel-close")) {
                    Icon(Icons.Filled.Close, "閉じる", tint = XmbText.secondary)
                }
            }
            Spacer(Modifier.size(12.dp))
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                content = content,
            )
        }
    }
}

@Composable
fun Label(text: String) = Text(text, color = XmbText.secondary, fontSize = 13.sp)

@Composable
fun Note(text: String, color: Color = XmbText.dim) = Text(text, color = color, fontSize = 13.sp)

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, password: Boolean = false, singleLine: Boolean = true, tag: String? = null) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = singleLine,
        visualTransformation = if (password && !show) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (password) {
            { IconButton(onClick = { show = !show }) { Icon(if (show) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, null) } }
        } else null,
        modifier = modifier.fillMaxWidth().let { if (tag != null) it.testTag(tag) else it },
    )
}

// ---------------------------------------------------------------- 設定：サーバー
@Composable
fun ServerPanel(app: AppController, onClose: () -> Unit) {
    val c by app.config.collectAsState()
    val server by app.server.collectAsState()
    var url by remember { mutableStateOf(c.serverUrl) }
    var token by remember { mutableStateOf(c.token) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var fingerprint by remember(c.caFile) { mutableStateOf(app.caFingerprint()) }
    val scope = rememberCoroutineScope()
    PanelScaffold("サーバー", onClose) {
        Field(url, { url = it; result = null }, "サーバー URL（例 https://192.168.50.49/emusync）", tag = "server-url")
        Field(token, { token = it; result = null }, "トークン（管理画面の「端末」で発行）", password = true, tag = "server-token")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { app.saveServer(url, token); result = true to "保存しました" }) { Text("保存") }
            OutlinedButton(enabled = !testing, onClick = {
                testing = true
                scope.launch {
                    result = app.testConnection(url, token).fold({ true to "接続できました（この PC: $it）" }, { false to (it.message ?: "失敗しました") })
                    testing = false
                }
            }) { Text("接続テスト") }
        }
        if (testing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        result?.let { (ok, msg) -> Note(msg, if (ok) XmbText.ok else XmbText.error) }
        if (server is ServerStatus.Online) Note("この PC の端末名：${(server as ServerStatus.Online).device}")
        HorizontalDivider(color = Color(0x33FFFFFF))
        Label("CA 証明書（自己署名の証明書のとき）")
        if (fingerprint == null) {
            Note("取り込んでいません。OS の信頼ストアを使います")
        } else {
            SelectionContainer { Text(fingerprint!!, color = XmbText.secondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                Pickers.file("CA 証明書を選ぶ", setOf("crt", "pem", "cer"))?.let { f ->
                    result = runCatching { fingerprint = app.importCa(f); true to "取り込みました" }
                        .getOrElse { false to "証明書として読み込めませんでした" }
                }
            }) { Text(if (fingerprint == null) "取り込む" else "取り込み直す") }
            if (fingerprint != null) TextButton(onClick = { app.removeCa(); fingerprint = null }) { Text("削除") }
        }
    }
}

// ---------------------------------------------------------------- 設定：エミュレーター
@Composable
fun SystemPanel(app: AppController, target: String, onClose: () -> Unit) {
    val preset = Presets.byTarget(target)
    val c by app.config.collectAsState()
    val cur = c.system(target) ?: SystemConfig(target, args = preset?.args ?: "\"{rom}\"", excludes = preset?.excludes.orEmpty())
    var exe by remember { mutableStateOf(cur.emulatorExe) }
    var args by remember { mutableStateOf(cur.args) }
    var save by remember { mutableStateOf(cur.savePath) }
    var excludes by remember { mutableStateOf(cur.excludes.joinToString("\n")) }
    var enabled by remember { mutableStateOf(cur.enabled) }
    var msg by remember { mutableStateOf<String?>(null) }
    PanelScaffold("${Presets.machineOf(target)}（${preset?.emulator ?: "エミュ"}）", onClose) {
        Note("ターゲット名：$target（Android と同じ名前でセーブを共有します）")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field(exe, { exe = it }, "エミュの実行ファイル（${preset?.exeNames?.first() ?: ".exe"}）", Modifier.weight(1f), tag = "emu-exe")
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { Pickers.file("エミュを選ぶ", setOf("exe"))?.let { exe = it.path } }) { Text("選ぶ") }
        }
        Field(args, { args = it }, "起動の引数（{rom} がゲームのパスになります）")
        preset?.let { p ->
            if (args != p.args) TextButton(onClick = { args = p.args }) { Text("既定に戻す（${p.args}）") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field(save, { save = it }, "セーブのフォルダ（同期するフォルダ）", Modifier.weight(1f), tag = "emu-save")
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { Pickers.folder("セーブのフォルダを選ぶ", save.ifBlank { null })?.let { save = it.path } }) { Text("選ぶ") }
        }
        if (preset != null) {
            TextButton(onClick = {
                val found = Presets.findSavePath(preset, exe)
                if (found != null) save = found else msg = "見つかりませんでした。エミュの設定でセーブの場所を確かめて、選んでください"
            }) { Text("セーブのフォルダを自動で探す") }
        }
        when {
            save.isNotBlank() && File(save).absoluteFile.parentFile?.isDirectory != true -> Note("このフォルダの親フォルダが見つかりません", XmbText.error)
            save.isNotBlank() && !File(save).isDirectory -> Note("フォルダはまだありません（取得すると作られます）", XmbText.warn)
        }
        Field(excludes, { excludes = it }, "除外パターン（1行に1つ。例 *.bak）", singleLine = false)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("この機種を同期する", color = XmbText.primary, modifier = Modifier.weight(1f))
            Switch(enabled, { enabled = it })
        }
        Button(onClick = {
            app.saveSystem(SystemConfig(target, exe.trim(), args.trim(), save.trim(), excludes.lines().map { it.trim() }.filter { it.isNotEmpty() }, enabled))
            msg = "保存しました"
            app.refresh()
        }, modifier = Modifier.testTag("emu-save-button")) { Text("保存") }
        msg?.let { Note(it, XmbText.ok) }
    }
}

// ---------------------------------------------------------------- 設定：起動と常駐
@Composable
fun StartupPanel(app: AppController, onClose: () -> Unit) {
    val c by app.config.collectAsState()
    var autostart by remember { mutableStateOf(Autostart.isEnabled()) }
    PanelScaffold("起動と常駐", onClose) {
        ToggleRow("Windows の起動時に起動する", "サインインしたときに、タスクトレイに常駐します", autostart, Autostart.supported) {
            autostart = app.setAutostart(it) && it
        }
        if (!Autostart.supported) Note("インストールした emusync で設定できます（開発中の実行では使えません）")
        ToggleRow("起動時はタスクトレイに入れておく", "Windows の起動時は、ウィンドウを出さずにトレイに常駐します", c.startMinimized, true) { v ->
            app.update { it.copy(startMinimized = v) }
        }
        ToggleRow("1時間ごとに送信する", "手元の変更をサーバーに送ります（取得はしません。プレイ中の機種は送りません）", c.periodicPush, true) { v ->
            app.update { it.copy(periodicPush = v) }
        }
        Note("ウィンドウを閉じても、emusync はタスクトレイに残ります。終了するときは、トレイのアイコンのメニューから「終了」を選んでください")
    }
}

@Composable
private fun ToggleRow(title: String, sub: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = XmbText.primary, fontSize = 16.sp)
            Note(sub)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked, onChange, enabled = enabled)
    }
}

// ---------------------------------------------------------------- 設定：テーマ
@Composable
fun ThemePanel(app: AppController, onClose: () -> Unit) {
    val c by app.config.collectAsState()
    PanelScaffold("テーマ", onClose) {
        Note("背景の色を選びます")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            XmbThemes.all.forEach { t ->
                Column(
                    Modifier.clip(RoundedCornerShape(10.dp)).clickable { app.update { it.copy(themeColor = t.name) } }
                        .background(if (c.themeColor == t.name) Color(0x33FFFFFF) else Color.Transparent).padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.size(72.dp, 48.dp).clip(RoundedCornerShape(6.dp)).background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(t.top, t.bottom))))
                    Spacer(Modifier.size(4.dp))
                    Text(t.label, color = XmbText.primary, fontSize = 13.sp)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 設定：情報
@Composable
fun AboutPanel(app: AppController, onClose: () -> Unit) {
    val c by app.config.collectAsState()
    PanelScaffold("emusync について", onClose) {
        Text("emusync", color = XmbText.primary, fontSize = 20.sp)
        Note("エミュのセーブを自宅のサーバーと同期するランチャー。同期の判定は Python 版・Android 版と同じです")
        Label("データの場所")
        SelectionContainer { Text(app.root.path, color = XmbText.secondary, fontSize = 13.sp, fontFamily = FontFamily.Monospace) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { Pickers.open(app.root) }) { Text("フォルダを開く") }
            if (c.serverUrl.isNotBlank()) OutlinedButton(onClick = { Pickers.browse(c.serverUrl.trimEnd('/') + "/admin/") }) { Text("サーバーの管理画面を開く") }
        }
    }
}

// ---------------------------------------------------------------- ゲームの追加・編集
@Composable
fun EditGamePanel(app: AppController, gameId: String?, presetTarget: String?, onClose: () -> Unit) {
    val c by app.config.collectAsState()
    val existing = c.games.firstOrNull { it.id == gameId }
    var target by remember { mutableStateOf(existing?.target ?: presetTarget ?: c.systems.firstOrNull()?.target ?: "psp") }
    var title by remember { mutableStateOf(existing?.title.orEmpty()) }
    var rom by remember { mutableStateOf(existing?.rom.orEmpty()) }
    var cover by remember { mutableStateOf(existing?.coverPath.orEmpty()) }
    val preset = Presets.byTarget(target)
    PanelScaffold(if (existing == null) "ゲームを追加" else "プロパティ", onClose) {
        Label("機種")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Presets.all.forEach { p -> FilterChip(target == p.target, { target = p.target }, { Text(p.machine) }) }
        }
        if (c.system(target)?.emulatorExe.isNullOrBlank()) Note("${Presets.machineOf(target)} のエミュがまだ設定されていません（設定 → エミュレーター）", XmbText.warn)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field(rom, { rom = it }, preset?.romHint ?: "ROM のパス", Modifier.weight(1f), tag = "game-rom")
            if (target != "vita") {
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = {
                    Pickers.file("ゲームを選ぶ", preset?.romExtensions?.takeIf { it.isNotEmpty() })?.let {
                        rom = it.path
                        if (title.isBlank()) title = LibraryScanner.cleanTitle(it.nameWithoutExtension)
                    }
                }) { Text("選ぶ") }
            }
        }
        Field(title, { title = it }, "タイトル", tag = "game-title")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field(cover, { cover = it }, "カバー画像（任意。PNG・JPG）", Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { Pickers.file("カバー画像を選ぶ", setOf("png", "jpg", "jpeg", "webp"))?.let { cover = it.path } }) { Text("選ぶ") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = title.isNotBlank() && rom.isNotBlank(),
                onClick = {
                    val newCover = cover.trim().ifEmpty { null }
                    if (existing == null) {
                        // ファイル名から作ったタイトルのままなら、あとで調べたタイトルに置き換える
                        val auto = LibraryScanner.cleanTitle(File(rom.trim()).nameWithoutExtension)
                        app.addGames(listOf(Game(
                            title = title.trim(), target = target, rom = rom.trim(), coverPath = newCover,
                            titleEdited = title.trim() != auto, coverEdited = newCover != null,
                        )))
                    } else {
                        val g = existing.copy(
                            title = title.trim(), target = target, rom = rom.trim(), coverPath = newCover,
                            titleEdited = existing.titleEdited || title.trim() != existing.title,
                            coverEdited = existing.coverEdited || newCover != existing.coverPath,
                        )
                        if (g.rom != existing.rom || g.target != existing.target) {
                            // ROM や機種を変えたら、調べ直す
                            app.saveGame(g.copy(serial = null, region = null, infoCheckedAt = null))
                            app.fetchInfo(listOf(g.id))
                        } else {
                            app.saveGame(g)
                        }
                    }
                    onClose()
                },
                modifier = Modifier.testTag("game-save"),
            ) { Text(if (existing == null) "ライブラリに追加" else "保存") }
            if (existing != null) TextButton(onClick = { app.removeGame(existing.id); onClose() }) { Text("ライブラリから外す", color = XmbText.error) }
        }
        if (existing != null) {
            Note("プレイ時間 ${formatPlayTime(existing.playSeconds)}・最後のプレイ ${formatTime(existing.lastPlayedAt)}")
            Note(listOfNotNull(existing.serial?.let { "型番 $it" }, existing.region?.let { "地域 $it" }).joinToString("・").ifEmpty { "型番：まだ分かりません" })
            OutlinedButton(onClick = { app.fetchInfo(listOf(existing.id), again = true); onClose() }) { Text("情報とカバーを取得し直す") }
        } else {
            Note("追加すると、日本語のタイトル・型番・カバーをインターネットから自動で取得します（自分で入れたタイトル・カバーはそのまま）")
        }
    }
}

// ---------------------------------------------------------------- フォルダからゲームを探す
@Composable
fun ScanPanel(app: AppController, presetTarget: String?, onClose: () -> Unit) {
    val c by app.config.collectAsState()
    var target by remember { mutableStateOf(presetTarget ?: "ps2") }
    var folder by remember { mutableStateOf("") }
    val found = remember { mutableStateListOf<LibraryScanner.Found>() }
    val checked = remember { mutableStateListOf<String>() }
    var scanning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val known = c.games.map { it.rom }.toSet()
    PanelScaffold("フォルダからゲームを探す", onClose) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Presets.all.forEach { p -> FilterChip(target == p.target, { target = p.target; found.clear() }, { Text(p.machine) }) }
        }
        Note(Presets.byTarget(target)?.romHint.orEmpty())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field(folder, { folder = it }, if (target == "vita") "Vita3K のフォルダ（ux0 がある場所）" else "ゲームのフォルダ", Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { Pickers.folder("フォルダを選ぶ", folder.ifBlank { null })?.let { folder = it.path } }) { Text("選ぶ") }
        }
        Button(enabled = folder.isNotBlank() && !scanning, onClick = {
            scanning = true
            scope.launch {
                val r = withContext(Dispatchers.IO) { LibraryScanner.scan(Presets.byTarget(target)!!, File(folder)) }
                found.clear(); found.addAll(r)
                checked.clear(); checked.addAll(r.map { it.rom }.filter { it !in known })
                scanning = false
            }
        }) { Text("探す") }
        if (scanning) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        if (found.isNotEmpty()) {
            Note("${found.size} 本見つかりました")
            found.forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(f.rom in checked, { on -> if (on) checked += f.rom else checked -= f.rom }, enabled = f.rom !in known)
                    Column {
                        Text(f.title + if (f.rom in known) "（追加済み）" else "", color = XmbText.primary, fontSize = 14.sp)
                        Text(f.rom, color = XmbText.dim, fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
            Button(enabled = checked.isNotEmpty(), onClick = {
                app.addGames(found.filter { it.rom in checked }.map { Game(title = it.title, target = target, rom = it.rom) })
                onClose()
            }) { Text("${checked.size} 本をライブラリに追加") }
            Note("追加したあと、日本語のタイトル・型番・カバーをインターネットから自動で取得します")
        } else if (!scanning && folder.isNotBlank()) {
            Note("「探す」を押すと、フォルダの中のゲームを一覧にします")
        }
    }
}

// ---------------------------------------------------------------- セーブデータ：プレビュー
@Composable
fun PreviewPanel(app: AppController, target: String, onClose: () -> Unit) {
    var outcome by remember { mutableStateOf<SyncOutcome?>(null) }
    LaunchedEffect(target) { outcome = app.sync.run(target, SyncMode.SYNC, dryRun = true) }
    PanelScaffold("${Presets.machineOf(target)} のプレビュー", onClose) {
        Note("同期すると、次のようになります（まだ何も変えていません）")
        when (val o = outcome) {
            null -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            is SyncOutcome.Failed -> Note(o.message, XmbText.error)
            is SyncOutcome.Done -> {
                val r = o.result
                Text(Messages.summary(r.uploaded.size, r.downloaded.size, r.conflicts.size, r.errors.size), color = XmbText.primary, fontSize = 16.sp)
                r.uploaded.forEach { Text("↑ $it", color = XmbText.secondary, fontSize = 13.sp) }
                r.downloaded.forEach { Text("↓ $it", color = XmbText.secondary, fontSize = 13.sp) }
                r.conflicts.forEach { Text("! ${it.rel}", color = XmbText.warn, fontSize = 13.sp) }
                r.errors.forEach { Text("× ${it.rel}: ${it.message}", color = XmbText.error, fontSize = 13.sp) }
            }
        }
    }
}

// ---------------------------------------------------------------- セーブデータ：競合
@Composable
fun ConflictsPanel(app: AppController, target: String, onClose: () -> Unit) {
    val rev by app.revision.collectAsState()
    val conflicts = remember(rev) { app.records.conflicts(target) }
    var server by remember { mutableStateOf<Map<String, RemoteFile>>(emptyMap()) }
    LaunchedEffect(target, rev) { server = runCatching { app.sync.api()?.manifest("$target/") }.getOrNull().orEmpty() }
    val root = app.sync.syncTarget(target).root
    fun resolve(rels: List<String>, prefer: Prefer) =
        app.syncNow(target, if (prefer == Prefer.LOCAL) SyncMode.PUSH else SyncMode.PULL, prefer, rels.toSet())
    PanelScaffold("${Presets.machineOf(target)} の競合", onClose) {
        if (conflicts.isEmpty()) {
            Note("競合はありません")
            return@PanelScaffold
        }
        Note("手元とサーバーの両方で変わったファイルです。どちらを残すか選んでください（サーバーの版は履歴に残ります。サーバー版を使うと、手元の版は backups に退避します）")
        if (conflicts.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { resolve(conflicts.map { it.rel }, Prefer.LOCAL) }) { Text("すべて手元を残す") }
                OutlinedButton(onClick = { resolve(conflicts.map { it.rel }, Prefer.REMOTE) }) { Text("すべてサーバー版を使う") }
            }
        }
        conflicts.forEach { cf ->
            val local = File(root, cf.rel)
            val s = server[cf.rel]
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0x22FFFFFF)).padding(12.dp)) {
                Text(cf.rel, color = XmbText.primary, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                Spacer(Modifier.size(6.dp))
                Row {
                    Column(Modifier.weight(1f)) {
                        Label("手元")
                        Note(if (local.isFile) "${formatSize(local.length())}・${formatTime(local.lastModified())}" else "ありません")
                    }
                    Column(Modifier.weight(1f)) {
                        Label("サーバー")
                        Note(s?.let { "v${it.version}・${it.device}・${formatTime((it.updatedAt * 1000).toLong())}・${formatSize(it.size)}" } ?: "v${cf.serverVersion}")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { resolve(listOf(cf.rel), Prefer.LOCAL) }, enabled = local.isFile) { Text("手元を残す") }
                    TextButton(onClick = { resolve(listOf(cf.rel), Prefer.REMOTE) }) { Text("サーバー版を使う") }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- セーブデータ：履歴
@Composable
fun HistoryPanel(app: AppController, target: String, onOpen: (String) -> Unit, onClose: () -> Unit) {
    var files by remember { mutableStateOf<Result<List<RemoteFile>>?>(null) }
    LaunchedEffect(target) {
        files = runCatching { app.sync.api()?.manifest("$target/")?.values?.sortedBy { it.rel }.orEmpty() }
    }
    PanelScaffold("${Presets.machineOf(target)} の履歴", onClose) {
        Note("サーバー上のファイルです。選ぶと、残っている版の一覧を出します")
        val r = files
        when {
            r == null -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            r.isFailure -> Note(Messages.connection(r.exceptionOrNull()!!), XmbText.error)
            r.getOrThrow().isEmpty() -> Note("サーバーにファイルがありません")
            else -> r.getOrThrow().forEach { f ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { onOpen(f.rel) }.padding(8.dp)) {
                    Text(f.rel, color = XmbText.primary, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                    Note("v${f.version}・${formatSize(f.size)}・${f.device}・${formatTime((f.updatedAt * 1000).toLong())}")
                }
            }
        }
    }
}

@Composable
fun FileHistoryPanel(app: AppController, target: String, rel: String, onClose: () -> Unit) {
    var versions by remember { mutableStateOf<Result<List<HistoryVersion>>?>(null) }
    var msg by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var askPull by remember { mutableStateOf<Int?>(null) }
    var reload by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val path = "$target/$rel"
    LaunchedEffect(path, reload) {
        versions = runCatching { (app.sync.api() as? HistoryApi)?.history(path).orEmpty() }
    }
    PanelScaffold("版の履歴", onClose) {
        Text(rel, color = XmbText.primary, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
        msg?.let { (ok, m) -> Note(m, if (ok) XmbText.ok else XmbText.error) }
        askPull?.let { v ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0x33FFD58A)).padding(12.dp)) {
                Text("v$v を取得して、手元に反映しますか？", color = XmbText.primary)
                Note("このファイルだけを取得します。手元も変えていた場合は、上書きせずに競合として知らせます")
                Row {
                    TextButton(onClick = { app.syncNow(target, SyncMode.PULL, only = setOf(rel)); askPull = null }) { Text("取得する") }
                    TextButton(onClick = { askPull = null }) { Text("あとで") }
                }
            }
        }
        val r = versions
        when {
            r == null -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            r.isFailure -> Note(Messages.connection(r.exceptionOrNull()!!), XmbText.error)
            else -> {
                val list = r.getOrThrow()
                val latest = list.maxOfOrNull { it.version }
                list.forEach { v ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("v${v.version}" + if (v.version == latest) "（最新）" else "", color = XmbText.primary)
                            Note("${formatTime((v.createdAt * 1000).toLong())}・${formatSize(v.size)}・${v.device}")
                        }
                        if (v.version != latest) OutlinedButton(onClick = {
                            scope.launch {
                                runCatching { (app.sync.api() as HistoryApi).restore(path, v.version) }
                                    .onSuccess { n -> msg = true to "v${v.version} の内容を v$n として最新にしました"; askPull = n; reload++ }
                                    .onFailure { e -> msg = false to Messages.connection(e) }
                            }
                        }) { Text("この版に戻す") }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 同期ログ
@Composable
fun LogPanel(app: AppController, onClose: () -> Unit) {
    val rev by app.revision.collectAsState()
    val log = remember(rev) { app.records.log() }
    PanelScaffold("同期ログ", onClose) {
        if (log.isEmpty()) Note("まだ同期していません")
        log.forEach { e ->
            val mode = when {
                e.dryRun -> "プレビュー"
                e.trigger == "play" -> "プレイ前"
                e.trigger == "finish" -> "プレイ後"
                e.trigger == "periodic" -> "定期送信"
                e.mode == "PULL" -> "取得のみ"
                e.mode == "PUSH" -> "送信のみ"
                else -> "同期"
            }
            Column {
                Row {
                    Text(Presets.machineOf(e.target), color = XmbText.primary, fontSize = 14.sp)
                    Spacer(Modifier.width(8.dp))
                    Note(mode + (e.prefer?.let { if (it == "LOCAL") " / 手元を残す" else " / サーバー版を使う" } ?: ""))
                    Spacer(Modifier.weight(1f))
                    Note(formatTime(e.startedAt))
                }
                Text(
                    e.fatal ?: Messages.summary(e.uploaded, e.downloaded, e.conflicts, e.errors.size),
                    color = if (e.fatal != null || e.errors.isNotEmpty()) XmbText.error else XmbText.secondary, fontSize = 13.sp,
                )
                e.errors.forEach { Note("× $it", XmbText.error) }
            }
        }
    }
}
