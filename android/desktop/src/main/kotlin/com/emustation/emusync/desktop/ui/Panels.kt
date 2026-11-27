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
import androidx.compose.runtime.key
import com.emustation.emusync.desktop.platform.Sfx
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
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
    data object Controller : Panel
    data object Sounds : Panel
    data object RemotePlay : Panel
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
    // 開いたらパネルにフォーカスを移す（Esc・コントローラーの B で閉じられ、下・Tab で中のボタンや入力欄へ進めるように）。
    // ボタンではなくパネルそのものに置くので、開いた直後に決定を押しても何も起きない
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(Modifier.fillMaxHeight().width(600.dp).background(Color(0xF00B111B)).focusRequester(focus).focusable()) {
        Column(Modifier.fillMaxHeight().padding(start = 32.dp, end = 24.dp, top = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = XmbText.primary, fontSize = 22.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose, modifier = Modifier.testTag("panel-close").focusRing()) {
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
            { IconButton(onClick = { show = !show }, modifier = Modifier.focusRing()) { Icon(if (show) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, null) } }
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
            Button(onClick = { app.saveServer(url, token); result = true to "保存しました" }, modifier = Modifier.focusRing()) { Text("保存") }
            OutlinedButton(enabled = !testing, onClick = {
                testing = true
                scope.launch {
                    result = app.testConnection(url, token).fold({ true to "接続できました（この PC: $it）" }, { false to (it.message ?: "失敗しました") })
                    testing = false
                }
            }, modifier = Modifier.focusRing()) { Text("接続テスト") }
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
            }, modifier = Modifier.focusRing()) { Text(if (fingerprint == null) "取り込む" else "取り込み直す") }
            if (fingerprint != null) TextButton(onClick = { app.removeCa(); fingerprint = null }, modifier = Modifier.focusRing()) { Text("削除") }
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
        preset?.note?.let { Note(it, XmbText.warn) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field(exe, { exe = it }, "エミュの実行ファイル（${preset?.exeNames?.first() ?: ".exe"}）", Modifier.weight(1f), tag = "emu-exe")
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { Pickers.file("エミュを選ぶ", setOf("exe"))?.let { exe = it.path } }, modifier = Modifier.focusRing()) { Text("選ぶ") }
        }
        Field(args, { args = it }, "起動の引数（{rom} がゲームのパスになります）")
        preset?.let { p ->
            if (args != p.args) TextButton(onClick = { args = p.args }, modifier = Modifier.focusRing()) { Text("既定に戻す（${p.args}）") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field(save, { save = it }, "セーブのフォルダ（同期するフォルダ）", Modifier.weight(1f), tag = "emu-save")
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { Pickers.folder("セーブのフォルダを選ぶ", save.ifBlank { null })?.let { save = it.path } }, modifier = Modifier.focusRing()) { Text("選ぶ") }
        }
        if (preset != null) {
            TextButton(onClick = {
                val found = Presets.findSavePath(preset, exe)
                if (found != null) save = found else msg = "見つかりませんでした。エミュの設定でセーブの場所を確かめて、選んでください"
            }, modifier = Modifier.focusRing()) { Text("セーブのフォルダを自動で探す") }
        }
        when {
            save.isNotBlank() && File(save).absoluteFile.parentFile?.isDirectory != true -> Note("このフォルダの親フォルダが見つかりません", XmbText.error)
            save.isNotBlank() && !File(save).isDirectory -> Note("フォルダはまだありません（取得すると作られます）", XmbText.warn)
        }
        Field(excludes, { excludes = it }, "除外パターン（1行に1つ。例 *.bak）", singleLine = false)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("この機種を同期する", color = XmbText.primary, modifier = Modifier.weight(1f))
            Switch(enabled, { enabled = it }, modifier = Modifier.focusRing())
        }
        Button(onClick = {
            app.saveSystem(SystemConfig(target, exe.trim(), args.trim(), save.trim(), excludes.lines().map { it.trim() }.filter { it.isNotEmpty() }, enabled))
            msg = "保存しました"
            app.refresh()
        }, modifier = Modifier.testTag("emu-save-button").focusRing()) { Text("保存") }
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
        if (!Autostart.supported) Note("インストールした EmuStation で設定できます（開発中の実行では使えません）")
        ToggleRow("起動時はタスクトレイに入れておく", "Windows の起動時は、ウィンドウを出さずにトレイに常駐します", c.startMinimized, true) { v ->
            app.update { it.copy(startMinimized = v) }
        }
        ToggleRow("1時間ごとに送信する", "手元の変更をサーバーに送ります（取得はしません。プレイ中の機種は送りません）", c.periodicPush, true) { v ->
            app.update { it.copy(periodicPush = v) }
        }
        Note("ウィンドウを閉じても、EmuStation はタスクトレイに残ります。終了するときは、トレイのアイコンのメニューから「終了」を選んでください")
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
        Switch(checked, onChange, enabled = enabled, modifier = Modifier.focusRing())
    }
}

// ---------------------------------------------------------------- 設定：コントローラー
@Composable
fun ControllerPanel(app: AppController, onClose: () -> Unit) {
    val c by app.config.collectAsState()
    PanelScaffold("コントローラー", onClose) {
        ToggleRow(
            "コントローラーで操作する",
            "PlayStation（DualShock 4・DualSense）・Xbox・ROG Ally・Switch のプロコン など",
            c.gamepad, true,
        ) { v -> app.update { it.copy(gamepad = v) } }
        Label("決定のボタン")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                c.confirmButton == "A", { app.update { it.copy(confirmButton = "A") } },
                { Text("下のボタンで決定（Xbox の A・PS の ×）") }, modifier = Modifier.focusRing(RoundedCornerShape(8.dp)),
            )
            FilterChip(
                c.confirmButton == "B", { app.update { it.copy(confirmButton = "B") } },
                { Text("右のボタンで決定（Xbox の B・PS の ○。PS4 までの日本の並び）") }, modifier = Modifier.focusRing(RoundedCornerShape(8.dp)),
            )
        }
        Label("ボタンの割り当て")
        Note("十字キー・左スティック：選ぶ　LB・RB（L1・R1）：列を切り替える　Y（△）・メニュー（OPTIONS）：オプション　ビュー（CREATE）：全画面の切り替え")
        Note("画面の下の説明は、最後に触ったコントローラーの記号（PlayStation なら × ○ △）で出ます")
        Note("パネルやダイアログでは、上下でボタンや入力欄を移り、決定で押します。文字の入力は画面のキーボード（タッチ）を使ってください")
        Note("ゲーム（エミュ）を遊んでいる間は、コントローラーの操作はエミュにだけ届きます。ゲームを終えると EmuStation が前に出ます")
    }
}

// ---------------------------------------------------------------- 設定：操作音
@Composable
fun SoundsPanel(app: AppController, onClose: () -> Unit) {
    val c by app.config.collectAsState()
    val dir = remember { File(app.root, "sounds") }
    var tick by remember { mutableStateOf(0) } // 音を入れ替えたら表示を読み直す
    var message by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    PanelScaffold("操作音", onClose) {
        ToggleRow("操作音を鳴らす", "PS3 の XMB のように、選ぶ・決定・戻る・オプションで音を鳴らします", c.sounds, true) { v ->
            app.update { it.copy(sounds = v) }
        }
        Label("音の大きさ")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(30 to "小", 50 to "やや小", 70 to "中", 90 to "大").forEach { (v, label) ->
                FilterChip(c.soundVolume == v, { app.update { it.copy(soundVolume = v) }; com.emustation.emusync.desktop.platform.Sounds.play(Sfx.Cursor) },
                    { Text(label) }, modifier = Modifier.focusRing(RoundedCornerShape(8.dp)))
            }
        }
        Label("音のファイル（MP3・WAV）")
        Note("音のファイルはアプリには入っていません。持っている音のファイルを選ぶと、この PC の EmuStation のフォルダに写して使います")
        key(tick) {
            Sfx.entries.forEach { sfx ->
                val file = com.emustation.emusync.desktop.platform.Sounds.fileOf(dir, sfx)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(sfx.label, color = XmbText.primary, fontSize = 15.sp)
                        Note(file?.name ?: "なし（鳴らさない）")
                    }
                    if (file != null) {
                        TextButton(onClick = { com.emustation.emusync.desktop.platform.Sounds.play(sfx) }, modifier = Modifier.focusRing()) { Text("試す") }
                        TextButton(onClick = {
                            com.emustation.emusync.desktop.platform.Sounds.remove(dir, sfx); tick++
                        }, modifier = Modifier.focusRing()) { Text("外す") }
                    }
                    OutlinedButton(onClick = {
                        Pickers.file("${sfx.label}の音を選ぶ", setOf("mp3", "wav"))?.let { f ->
                            message = runCatching { com.emustation.emusync.desktop.platform.Sounds.install(dir, sfx, f) }
                                .fold({ com.emustation.emusync.desktop.platform.Sounds.play(sfx); true to "${sfx.label}の音にしました" }, { false to "読めないファイルです（MP3 か WAV を選んでください）" })
                            tick++
                        }
                    }, modifier = Modifier.focusRing()) { Text("選ぶ") }
                }
            }
        }
        message?.let { (ok, m) -> Note(m, if (ok) XmbText.ok else XmbText.error) }
        Note("ファイルを直接置いてもかまいません：${dir.path} に cursor.mp3・decide.mp3・back.mp3・options.mp3（WAV も可）")
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

// ---------------------------------------------------------------- 設定：リモートプレイ
@Composable
fun RemotePlayPanel(app: AppController, onClose: () -> Unit) {
    val exe = remember { com.emustation.emusync.desktop.platform.RemotePlay.exePath() }
    val poster = remember { com.emustation.emusync.desktop.platform.RemotePlay.exportPoster(app.root) }
    val command = exe?.let { com.emustation.emusync.desktop.platform.RemotePlay.command(it) }
    val endCommand = exe?.let { com.emustation.emusync.desktop.platform.RemotePlay.endCommand(it) }
    val localAddresses = remember { com.emustation.emusync.desktop.platform.RemotePlay.localAddresses() }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    val scope = rememberCoroutineScope()
    PanelScaffold("リモートプレイ（Sunshine・Moonlight）", onClose) {
        Note("Android などの Moonlight から、この PC の EmuStation を遊べます。Moonlight で「EmuStation」を選ぶと全画面の EmuStation が映り、" +
            "手元のコントローラーでゲームを選んで遊べます（セーブの同期はこの PC の EmuStation が今までどおり行います）")

        Label("1. この PC に Sunshine を入れる")
        Note("Sunshine は、この PC の画面をスマホなどに送る無料のアプリです。入れたら、設定画面でユーザー名とパスワードを決めます")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { Pickers.browse("https://app.lizardbyte.dev/Sunshine/") }, modifier = Modifier.focusRing()) { Text("Sunshine のページを開く") }
            OutlinedButton(onClick = { Pickers.browse(com.emustation.emusync.desktop.platform.SunshineClient.DEFAULT_URL) }, modifier = Modifier.focusRing()) { Text("Sunshine の設定画面を開く") }
        }

        Label("2. Sunshine に EmuStation を登録する")
        if (command == null) {
            Note("インストールした EmuStation で行ってください（開発中の実行では登録できません）", XmbText.warn)
        } else {
            Note("Sunshine の設定画面のユーザー名とパスワードを入れて「登録する」を押します（パスワードは保存しません）")
            Field(user, { user = it }, "Sunshine のユーザー名", tag = "sunshine-user")
            Field(password, { password = it }, "Sunshine のパスワード", password = true, tag = "sunshine-password")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        busy = true
                        message = null
                        scope.launch {
                            val r = withContext(Dispatchers.IO) {
                                com.emustation.emusync.desktop.platform.SunshineClient(user = user, password = password)
                                    .register(command, endCommand!!, poster?.path)
                            }
                            message = when (r) {
                                is com.emustation.emusync.desktop.platform.SunshineClient.Result.Added ->
                                    true to (if (r.updated) "登録を新しくしました" else "登録しました") + "。Moonlight のアプリ一覧に「EmuStation」が出ます"
                                com.emustation.emusync.desktop.platform.SunshineClient.Result.BadLogin -> false to "ユーザー名かパスワードが違います"
                                com.emustation.emusync.desktop.platform.SunshineClient.Result.NotRunning -> false to "Sunshine が動いていません（入れて起動してから、もう一度押してください）"
                                is com.emustation.emusync.desktop.platform.SunshineClient.Result.Failed -> false to r.message
                            }
                            busy = false
                        }
                    },
                    enabled = !busy && user.isNotBlank() && password.isNotEmpty(),
                    modifier = Modifier.focusRing().testTag("sunshine-register"),
                ) { Text(if (busy) "登録しています…" else "登録する") }
                message?.let { (ok, text) -> Text(text, color = if (ok) XmbText.ok else XmbText.error, fontSize = 13.sp) }
            }
            Note("前の版で登録した場合は、もう一度「登録する」を押してください（Moonlight で「アプリを終了」したとき、EmuStation を最小化するようになります）")
            Note("自分で登録するときは、Sunshine の「Applications」→「Add New」で、名前を EmuStation、Command は空のまま、" +
                "「Detached Commands」に 1 つ目、「Command Preparations」の Undo に 2 つ目を入れます：")
            SelectionContainer {
                Column {
                    Text(command, color = XmbText.secondary, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    Text(endCommand!!, color = XmbText.secondary, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                }
            }
            poster?.let { f ->
                Note("画像（Image）：")
                SelectionContainer { Text(f.path, color = XmbText.secondary, fontSize = 13.sp, fontFamily = FontFamily.Monospace) }
            }
        }

        Label("3. Android に Moonlight を入れて、この PC とペアリングする")
        Note("同じ Wi-Fi で Moonlight を開くと、この PC が出ます。選ぶと 4 桁の PIN が出るので、Sunshine の設定画面の「PIN」に入れます。" +
            "Android の EmuStation のメニュー「リモートプレイ」からも Moonlight を開けます")

        Label("4. 家の外から遊ぶ（WireGuard）")
        Note("Android の WireGuard で自宅のネットワークにつないでから、Moonlight の「＋」で、この PC の自宅での IP アドレスを入れます" +
            "（家の中と同じアドレスでつながります）。ペアリングは家の中で済ませておきます")
        localAddresses.takeIf { it.isNotEmpty() }?.let { ips ->
            Note("この PC の IP アドレス：")
            SelectionContainer { Text(ips.joinToString("　"), color = XmbText.secondary, fontSize = 13.sp, fontFamily = FontFamily.Monospace) }
        }
        Note("WireGuard のサーバー（ルーターや自宅のサーバー）の AllowedIPs に、自宅のネットワーク（例 192.168.50.0/24）を含めてください。" +
            "セーブの同期のサーバーも同じアドレスのまま、家の外から使えます")
        Note("モバイル回線で映像が乱れるときは、Moonlight の設定でビットレートを下げます（10〜20 Mbps）")

        Label("遊び方")
        Note("Moonlight で「EmuStation」を選ぶ → コントローラーでゲームを選んで遊ぶ → ゲームを終える（エミュの画面で Alt+F4 か、プレイ中の画面の「エミュを終了」）" +
            " → セーブが自動で送られる → 遊び終わったら Moonlight で「アプリを終了」（EmuStation は全画面を解いて最小化します）")
    }
}

// ---------------------------------------------------------------- 設定：情報
@Composable
fun AboutPanel(app: AppController, onClose: () -> Unit) {
    val c by app.config.collectAsState()
    PanelScaffold("EmuStation について", onClose) {
        Text("EmuStation", color = XmbText.primary, fontSize = 20.sp)
        Note("エミュのセーブを自宅のサーバーと同期するランチャー。同期の判定は Python 版・Android 版と同じです")
        Label("データの場所")
        SelectionContainer { Text(app.root.path, color = XmbText.secondary, fontSize = 13.sp, fontFamily = FontFamily.Monospace) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { Pickers.open(app.root) }, modifier = Modifier.focusRing()) { Text("フォルダを開く") }
            if (c.serverUrl.isNotBlank()) OutlinedButton(onClick = { Pickers.browse(c.serverUrl.trimEnd('/') + "/admin/") }, modifier = Modifier.focusRing()) { Text("サーバーの管理画面を開く") }
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
            Presets.all.forEach { p -> FilterChip(target == p.target, { target = p.target }, { Text(p.machine) }, modifier = Modifier.focusRing(RoundedCornerShape(8.dp))) }
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
                }, modifier = Modifier.focusRing()) { Text("選ぶ") }
            }
        }
        Field(title, { title = it }, "タイトル", tag = "game-title")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field(cover, { cover = it }, "カバー画像（任意。PNG・JPG）", Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { Pickers.file("カバー画像を選ぶ", setOf("png", "jpg", "jpeg", "webp"))?.let { cover = it.path } }, modifier = Modifier.focusRing()) { Text("選ぶ") }
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
                modifier = Modifier.testTag("game-save").focusRing(),
            ) { Text(if (existing == null) "ライブラリに追加" else "保存") }
            if (existing != null) TextButton(onClick = { app.removeGame(existing.id); onClose() }, modifier = Modifier.focusRing()) { Text("ライブラリから外す", color = XmbText.error) }
        }
        if (existing != null) {
            Note("プレイ時間 ${formatPlayTime(existing.playSeconds)}・最後のプレイ ${formatTime(existing.lastPlayedAt)}")
            Note(listOfNotNull(existing.serial?.let { "型番 $it" }, existing.region?.let { "地域 $it" }).joinToString("・").ifEmpty { "型番：まだ分かりません" })
            OutlinedButton(onClick = { app.fetchInfo(listOf(existing.id), again = true); onClose() }, modifier = Modifier.focusRing()) { Text("情報とカバーを取得し直す") }
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
            Presets.all.forEach { p -> FilterChip(target == p.target, { target = p.target; found.clear() }, { Text(p.machine) }, modifier = Modifier.focusRing(RoundedCornerShape(8.dp))) }
        }
        Note(Presets.byTarget(target)?.romHint.orEmpty())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field(folder, { folder = it }, if (target == "vita") "Vita3K のフォルダ（ux0 がある場所）" else "ゲームのフォルダ", Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { Pickers.folder("フォルダを選ぶ", folder.ifBlank { null })?.let { folder = it.path } }, modifier = Modifier.focusRing()) { Text("選ぶ") }
        }
        Button(enabled = folder.isNotBlank() && !scanning, onClick = {
            scanning = true
            scope.launch {
                val r = withContext(Dispatchers.IO) { LibraryScanner.scan(Presets.byTarget(target)!!, File(folder)) }
                found.clear(); found.addAll(r)
                checked.clear(); checked.addAll(r.map { it.rom }.filter { it !in known })
                scanning = false
            }
        }, modifier = Modifier.focusRing()) { Text("探す") }
        if (scanning) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        if (found.isNotEmpty()) {
            Note("${found.size} 本見つかりました")
            found.forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(f.rom in checked, { on -> if (on) checked += f.rom else checked -= f.rom }, enabled = f.rom !in known, modifier = Modifier.focusRing(RoundedCornerShape(8.dp)))
                    Column {
                        Text(f.title + if (f.rom in known) "（追加済み）" else "", color = XmbText.primary, fontSize = 14.sp)
                        Text(f.rom, color = XmbText.dim, fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
            Button(enabled = checked.isNotEmpty(), onClick = {
                app.addGames(found.filter { it.rom in checked }.map { Game(title = it.title, target = target, rom = it.rom) })
                onClose()
            }, modifier = Modifier.focusRing()) { Text("${checked.size} 本をライブラリに追加") }
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
                OutlinedButton(onClick = { resolve(conflicts.map { it.rel }, Prefer.LOCAL) }, modifier = Modifier.focusRing()) { Text("すべて手元を残す") }
                OutlinedButton(onClick = { resolve(conflicts.map { it.rel }, Prefer.REMOTE) }, modifier = Modifier.focusRing()) { Text("すべてサーバー版を使う") }
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
                    TextButton(onClick = { resolve(listOf(cf.rel), Prefer.LOCAL) }, enabled = local.isFile, modifier = Modifier.focusRing()) { Text("手元を残す") }
                    TextButton(onClick = { resolve(listOf(cf.rel), Prefer.REMOTE) }, modifier = Modifier.focusRing()) { Text("サーバー版を使う") }
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
                    TextButton(onClick = { app.syncNow(target, SyncMode.PULL, only = setOf(rel)); askPull = null }, modifier = Modifier.focusRing()) { Text("取得する") }
                    TextButton(onClick = { askPull = null }, modifier = Modifier.focusRing()) { Text("あとで") }
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
                        }, modifier = Modifier.focusRing()) { Text("この版に戻す") }
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
