package com.emustation.emusync.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.StayCurrentLandscape
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.emustation.emusync.desktop.data.AppConfig
import com.emustation.emusync.desktop.data.Game
import com.emustation.emusync.desktop.domain.AppController
import com.emustation.emusync.desktop.domain.PlayPhase
import com.emustation.emusync.desktop.domain.Presets
import com.emustation.emusync.desktop.domain.ServerStatus
import com.emustation.emusync.desktop.domain.SystemStatus
import com.emustation.emusync.desktop.platform.Gamepad
import com.emustation.emusync.desktop.platform.PadButton
import com.emustation.emusync.desktop.platform.PadMode
import com.emustation.emusync.domain.sync.SyncMode
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date

/** XMB のカテゴリーと項目を、アプリの状態から組み立てる */
fun buildCategories(
    c: AppConfig,
    server: ServerStatus,
    systems: Map<String, SystemStatus>,
    app: AppController?,
    infoBusy: Set<String> = emptySet(),
    open: (Panel) -> Unit,
): List<XmbCategory> {
    // ---- 設定
    val settings = XmbCategory(
        "settings", "設定", Icons.Filled.Settings,
        listOf(
            XmbItem("server", "サーバー", c.serverUrl.ifBlank { "未設定" }, Icons.Filled.Dns, onActivate = { open(Panel.Server) }),
        ) + Presets.all.map { p ->
            val s = c.system(p.target)
            XmbItem(
                "emu-${p.target}", "${p.machine}（${p.emulator}）",
                if (s == null || s.emulatorExe.isBlank()) "エミュを設定してください" else s.emulatorExe,
                Icons.Filled.Memory,
                badge = if (s == null || s.emulatorExe.isBlank()) "未設定" else null, badgeColor = XmbText.warn,
                onActivate = { open(Panel.System(p.target)) },
            )
        } + listOf(
            XmbItem(
                "game-info", "ゲームの情報とカバー", "インターネットから日本語のタイトル・型番・カバーを取得します",
                Icons.Filled.Image,
                badge = if (infoBusy.isNotEmpty()) "取得中 ${infoBusy.size}" else null,
                onActivate = { app?.fetchInfo() },
                options = listOf(
                    XmbOption("まだ取得していないゲームを取得") { app?.fetchInfo() },
                    XmbOption("すべてのゲームを取得し直す") { app?.fetchInfo(again = true) },
                ),
            ),
            XmbItem(
                "controller", "コントローラー",
                if (c.gamepad) "使う・決定は ${c.confirmButton} ボタン" else "使わない",
                Icons.Filled.SportsEsports, onActivate = { open(Panel.Controller) },
            ),
            XmbItem("startup", "起動と常駐", "Windows の起動時に起動・タスクトレイ・定期送信", Icons.Filled.PowerSettingsNew, onActivate = { open(Panel.Startup) }),
            XmbItem("theme", "テーマ", XmbThemes.byName(c.themeColor).label, Icons.Filled.Brush, onActivate = { open(Panel.Theme) }),
            XmbItem("about", "emusync について", "データの場所・サーバーの管理画面", Icons.Filled.Info, onActivate = { open(Panel.About) }),
        ),
    )

    // ---- ゲーム：ハードごとの列（PS3 の XMB のように）。ゲームもエミュもないハードは出さない
    val hardware = HARDWARE_ORDER.mapNotNull { Presets.byTarget(it) }.filter { p ->
        c.games.any { it.target == p.target } || !c.system(p.target)?.emulatorExe.isNullOrBlank()
    }
    val gameCats = hardware.map { p ->
        val games = c.games.filter { it.target == p.target }
            .sortedWith(compareByDescending<Game> { it.lastPlayedAt ?: 0L }.thenBy { it.title })
        XmbCategory(
            "hw-${p.target}", p.machine, hardwareIcon(p.target),
            games.map { g -> gameItem(g, systems[g.target], g.id in infoBusy, app, open) } +
                addItems(p.target, open),
        )
    }.ifEmpty {
        // まだ何もない：ゲームを追加する入口だけ
        listOf(XmbCategory("game", "ゲーム", Icons.Filled.SportsEsports, addItems(null, open)))
    }

    // ---- セーブデータ（PS3 の「セーブデータ管理」のように）
    val saves = XmbCategory(
        "saves", "セーブデータ", Icons.Filled.Cloud,
        c.systems.filter { it.savePath.isNotBlank() }.sortedBy { Presets.all.indexOfFirst { p -> p.target == it.target } }.map { s ->
            val st = systems[s.target]
            val (badge, color) = cloudBadge(st)
            XmbItem(
                "save-${s.target}", Presets.machineOf(s.target),
                "最後の同期 ${formatTime(st?.lastSync)}・${s.savePath}",
                Icons.Filled.Storage, badge = badge, badgeColor = color,
                onActivate = { app?.syncNow(s.target) },
                options = listOf(
                    XmbOption("今すぐ同期") { app?.syncNow(s.target) },
                    XmbOption("取得のみ") { app?.syncNow(s.target, SyncMode.PULL) },
                    XmbOption("送信のみ") { app?.syncNow(s.target, SyncMode.PUSH) },
                    XmbOption("プレビュー") { open(Panel.Preview(s.target)) },
                    XmbOption("競合を見る", (st?.conflicts ?: 0) > 0) { open(Panel.Conflicts(s.target)) },
                    XmbOption("履歴") { open(Panel.History(s.target)) },
                ),
            )
        }.ifEmpty { listOf(XmbItem("no-saves", "機種がまだ設定されていません", "設定 → エミュレーターで、セーブのフォルダを選んでください", Icons.Filled.Info)) },
    )

    // ---- ネットワーク
    val (serverTitle, serverSub, serverIcon) = when (server) {
        ServerStatus.Checking -> Triple("サーバーに接続しています…", c.serverUrl, Icons.Filled.CloudSync)
        ServerStatus.NotConfigured -> Triple("サーバーが設定されていません", "設定 → サーバー", Icons.Filled.CloudOff)
        is ServerStatus.Online -> Triple("サーバーに接続しています", "この PC：${server.device}・${c.serverUrl}", Icons.Filled.CloudDone)
        is ServerStatus.Offline -> Triple("サーバーにつながりません", server.message, Icons.Filled.CloudOff)
    }
    val network = XmbCategory(
        "network", "ネットワーク", Icons.Filled.Public,
        listOf(
            XmbItem("server-status", serverTitle, serverSub, serverIcon, onActivate = { app?.refresh() },
                options = listOf(XmbOption("接続し直す") { app?.refresh() }, XmbOption("サーバーの設定") { open(Panel.Server) })),
            XmbItem("sync-all", "すべて同期", "設定したすべての機種を、今すぐ同期します", Icons.Filled.CloudSync, onActivate = { app?.syncAll() }),
            XmbItem("log", "同期ログ", "直近 100 回の同期の結果", Icons.Filled.ListAlt, onActivate = { open(Panel.Log) }),
        ),
    )
    return listOf(settings) + gameCats + listOf(saves, network)
}

/** ハードの列の順番 */
private val HARDWARE_ORDER = listOf("ps2", "psp", "vita", "ps3")

private fun hardwareIcon(target: String) = when (target) {
    "psp", "vita" -> Icons.Filled.StayCurrentLandscape // 携帯機
    else -> Icons.Filled.Album // ディスク
}

private fun gameItem(g: Game, st: SystemStatus?, fetching: Boolean, app: AppController?, open: (Panel) -> Unit): XmbItem {
    val (badge, color) = if (fetching) "情報を取得中" to XmbText.secondary else cloudBadge(st)
    return XmbItem(
        "game-${g.id}", g.title,
        listOfNotNull(
            g.serial,
            g.region,
            g.lastPlayedAt?.let { "最後のプレイ ${formatTime(it)}" },
            g.playSeconds.takeIf { it > 0 }?.let(::formatPlayTime),
        ).joinToString("・").ifEmpty { Presets.machineOf(g.target) },
        Icons.Filled.SportsEsports, image = Covers.load(g.coverPath), badge = badge, badgeColor = color,
        onActivate = { app?.playGame(g) },
        options = listOf(
            XmbOption("プレイ") { app?.playGame(g) },
            XmbOption("クラウドと同期") { app?.syncNow(g.target) },
            XmbOption("セーブデータ（${Presets.machineOf(g.target)}）の競合を見る", (st?.conflicts ?: 0) > 0) { open(Panel.Conflicts(g.target)) },
            XmbOption("情報とカバーを取得し直す", !fetching) { app?.fetchInfo(listOf(g.id), again = true) },
            XmbOption("プロパティ") { open(Panel.EditGame(g.id)) },
        ),
    )
}

private fun addItems(target: String?, open: (Panel) -> Unit): List<XmbItem> {
    val machine = target?.let(Presets::machineOf)
    return listOf(
        XmbItem(
            "add-game${target?.let { "-$it" }.orEmpty()}", "ゲームを追加",
            "ROM を選んで、ライブラリに並べます（タイトルとカバーは自動で取得）", Icons.Filled.Add,
            onActivate = { open(Panel.EditGame(null, target)) },
        ),
        XmbItem(
            "scan${target?.let { "-$it" }.orEmpty()}", "フォルダからゲームを探す",
            "${machine?.let { "$it の " }.orEmpty()}ROM のフォルダを選ぶと、まとめて追加できます", Icons.Filled.FolderOpen,
            onActivate = { open(Panel.Scan(target)) },
        ),
    )
}

private fun cloudBadge(st: SystemStatus?): Pair<String?, Color> = when {
    st == null -> null to XmbText.secondary
    st.syncing -> "同期中" to XmbText.secondary
    st.conflicts > 0 -> "競合 ${st.conflicts}" to XmbText.error
    st.unsent != null && st.unsent > 0 -> "未送信 ${st.unsent}" to XmbText.warn
    st.lastSync == null -> "未同期" to XmbText.secondary
    else -> "同期済み" to XmbText.ok
}

/** アプリの画面全体（XMB ＋ 右のパネル ＋ プレイ中の重ね表示） */
@Composable
fun XmbApp(app: AppController, initialCategory: Int? = null, now: (() -> Long)? = null, wavePhase: Float? = null) {
    val c by app.config.collectAsState()
    val server by app.server.collectAsState()
    val systems by app.systems.collectAsState()
    val phase by app.play.phase.collectAsState()
    var panel by remember { mutableStateOf<Panel?>(null) }
    val state = remember {
        // 最初は、最後に遊んだゲームのハードの列（なければ設定の次の列）
        val last = app.config.value.games.maxByOrNull { it.lastPlayedAt ?: 0L }?.takeIf { it.lastPlayedAt != null }
        val first = initialCategory ?: last?.let { g ->
            buildCategories(app.config.value, ServerStatus.Checking, emptyMap(), null) {}.indexOfFirst { it.id == "hw-${g.target}" }
        }?.takeIf { it >= 0 } ?: 1
        XmbState(first)
    }
    val infoBusy by app.infoBusy.collectAsState()
    val cats = buildCategories(c, server, systems, app, infoBusy) { panel = it }
    // 列（ハード）が増えたり減ったりしても、選んでいた列を選んだままにする
    state.follow(cats)
    // コントローラー：パネル・ダイアログでは上下でボタンや入力欄を移る
    SideEffect { Gamepad.mode = if (panel != null || phase != null) PadMode.Form else PadMode.Xmb }
    val focus = remember { FocusRequester() }
    LaunchedEffect(panel, phase) { if (panel == null && phase == null) runCatching { focus.requestFocus() } }

    Box(
        Modifier.fillMaxSize()
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown) Gamepad.markKeyboard()
                when {
                    phase != null -> if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { overlayBack(app, phase!!); true } else false
                    panel != null -> if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { panel = null; true } else false
                    else -> state.onKey(e, cats)
                }
            }
            .focusRequester(focus)
            // パネル・ダイアログを開いている間は、後ろの XMB にフォーカスが移らないように
            .focusProperties { canFocus = panel == null && phase == null }.focusable(),
    ) {
        WaveBackground(XmbThemes.byName(c.themeColor), Modifier.fillMaxSize(), wavePhase)
        Header(now, Modifier.align(Alignment.TopEnd).padding(24.dp), server)
        Xmb(cats, state)
        val pad by Gamepad.lastInputWasPad.collectAsState()
        val style by Gamepad.style.collectAsState()
        // 決定・戻るのボタン（位置）。記号はコントローラーの種類で（PlayStation なら × ○）
        val (okBtn, backBtn) = if (c.confirmButton == "B") PadButton.B to PadButton.A else PadButton.A to PadButton.B
        val ok = Gamepad.label(okBtn, style)
        val back = Gamepad.label(backBtn, style)
        KeyHints(
            when {
                !pad -> listOf("←→↑↓" to "選ぶ", "Enter" to "決定", "Tab" to "オプション", "Esc" to "戻る")
                panel != null || phase != null -> listOf("↑↓" to "選ぶ", ok to "決定", back to "戻る")
                else -> listOf(
                    "✚" to "選ぶ", "${Gamepad.label(PadButton.LB, style)} ${Gamepad.label(PadButton.RB, style)}" to "切り替え",
                    ok to "決定", Gamepad.label(PadButton.Y, style) to "オプション", back to "戻る",
                )
            },
            Modifier.align(Alignment.BottomEnd).padding(24.dp),
        )
        AnimatedVisibility(
            panel != null, Modifier.align(Alignment.CenterEnd),
            enter = slideInHorizontally { it } + fadeIn(), exit = slideOutHorizontally { it } + fadeOut(),
        ) {
            val close = { panel = null }
            when (val p = panel) {
                Panel.Server -> ServerPanel(app, close)
                is Panel.System -> SystemPanel(app, p.target, close)
                Panel.Startup -> StartupPanel(app, close)
                Panel.Controller -> ControllerPanel(app, close)
                Panel.Theme -> ThemePanel(app, close)
                Panel.About -> AboutPanel(app, close)
                is Panel.EditGame -> EditGamePanel(app, p.gameId, p.target, close)
                is Panel.Scan -> ScanPanel(app, p.target, close)
                is Panel.Preview -> PreviewPanel(app, p.target, close)
                is Panel.Conflicts -> ConflictsPanel(app, p.target, close)
                is Panel.History -> HistoryPanel(app, p.target, { rel -> panel = Panel.FileHistory(p.target, rel) }, close)
                is Panel.FileHistory -> FileHistoryPanel(app, p.target, p.rel) { panel = Panel.History(p.target) }
                Panel.Log -> LogPanel(app, close)
                null -> Unit
            }
        }
        phase?.let { PlayOverlay(app, it) { target -> panel = Panel.Conflicts(target) } }
    }
}

@Composable
private fun Header(now: (() -> Long)?, modifier: Modifier, server: ServerStatus) {
    var t by remember { mutableLongStateOf(now?.invoke() ?: System.currentTimeMillis()) }
    LaunchedEffect(now) {
        if (now == null) while (true) { t = System.currentTimeMillis(); delay(10_000) }
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        val (icon, tint) = when (server) {
            is ServerStatus.Online -> Icons.Filled.CloudDone to XmbText.primary
            ServerStatus.Checking -> Icons.Filled.CloudSync to XmbText.secondary
            else -> Icons.Filled.CloudOff to XmbText.error
        }
        androidx.compose.material3.Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(SimpleDateFormat("M/d  H:mm").format(Date(t)), color = XmbText.primary, fontSize = 18.sp)
    }
}

/** ダイアログで「戻る」（Esc・コントローラーの B）：やめる・閉じる。競合のときはロックも外す。準備中・プレイ中は何もしない */
private fun overlayBack(app: AppController, p: PlayPhase) {
    when (p) {
        is PlayPhase.Conflicts -> app.abortPlay(p.game)
        is PlayPhase.Working, is PlayPhase.Playing -> Unit
        else -> app.play.dismiss()
    }
}

/** Steam のように、プレイの進み具合を画面の上に重ねて出す */
@Composable
private fun PlayOverlay(app: AppController, p: PlayPhase, openConflicts: (String) -> Unit) {
    val g = p.game ?: return
    // コントローラー・キーボードですぐ押せるように、最初に選んでおくボタン（危なくない方）
    val first = remember { FocusRequester() }
    LaunchedEffect(p::class) {
        // 準備中・プレイ中はボタンがない
        if (p !is PlayPhase.Working && p !is PlayPhase.Playing) runCatching { first.requestFocus() }
    }
    Box(Modifier.fillMaxSize().background(Color(0xB0000000)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 560.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xF0111824)).padding(28.dp).testTag("play-overlay"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(g.title, color = XmbText.primary, fontSize = 22.sp)
            Text(Presets.machineOf(g.target), color = XmbText.secondary, fontSize = 14.sp)
            when (p) {
                is PlayPhase.Working -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(p.text, color = XmbText.primary)
                }
                is PlayPhase.Playing -> {
                    var elapsed by remember { mutableLongStateOf(0L) }
                    LaunchedEffect(p.since) { while (true) { elapsed = (System.currentTimeMillis() - p.since) / 1000; delay(1000) } }
                    Text("プレイ中  ${"%d:%02d:%02d".format(elapsed / 3600, elapsed / 60 % 60, elapsed % 60)}", color = XmbText.primary, fontSize = 18.sp)
                    Text(
                        if (p.online) "ゲーム（エミュ）を終了すると、自動でクラウドに保存します"
                        else "同期せずに起動しました。ゲームを終了したら、つながるときにクラウドに保存します",
                        color = XmbText.secondary,
                    )
                }
                is PlayPhase.LockedByOther -> {
                    Text("${p.device} がプレイ中です", color = XmbText.warn, fontSize = 17.sp)
                    Text("向こうで終わってから起動してください。奪って起動すると、向こうのセーブと競合するおそれがあります（ロックの期限 ${formatTime((p.expiresAt * 1000).toLong())}）", color = XmbText.secondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { app.play.dismiss(); app.playGame(g, force = true) }, modifier = Modifier.focusRing()) { Text("奪って起動") }
                        TextButton(onClick = { app.play.dismiss() }, modifier = Modifier.focusRequester(first).focusRing()) { Text("やめる") }
                    }
                }
                is PlayPhase.Unreachable -> {
                    Text(p.message, color = XmbText.error)
                    Text("同期せずに起動しますか？ 遊び終わったら、つながるときに自動で送ります", color = XmbText.secondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { app.playOffline(g) }, modifier = Modifier.focusRequester(first).focusRing()) { Text("同期せずに起動") }
                        TextButton(onClick = { app.play.dismiss() }, modifier = Modifier.focusRing()) { Text("やめる") }
                    }
                }
                is PlayPhase.Conflicts -> {
                    Text("競合が ${p.result.conflicts.size} 件あります", color = XmbText.warn, fontSize = 17.sp)
                    Text("手元とサーバーの両方で変わったセーブがあるので、起動を止めました（何も上書きしていません）", color = XmbText.secondary)
                    p.result.conflicts.take(5).forEach { Text("! ${it.rel}", color = XmbText.warn, fontSize = 13.sp) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { app.playIgnoringConflicts(g) }, modifier = Modifier.focusRing()) { Text("無視して起動") }
                        OutlinedButton(onClick = { app.abortPlay(g); openConflicts(g.target) }, modifier = Modifier.focusRequester(first).focusRing()) { Text("競合を見る") }
                        TextButton(onClick = { app.abortPlay(g) }, modifier = Modifier.focusRing()) { Text("やめる") }
                    }
                }
                is PlayPhase.Finished -> {
                    Text(p.text, color = if (p.conflicts > 0) XmbText.warn else XmbText.ok)
                    Button(onClick = { app.play.dismiss() }, modifier = Modifier.testTag("overlay-ok").focusRequester(first).focusRing()) { Text("OK") }
                }
                is PlayPhase.Failed -> {
                    Text(p.message, color = XmbText.error)
                    Button(onClick = { app.play.dismiss() }, modifier = Modifier.focusRequester(first).focusRing()) { Text("OK") }
                }
            }
        }
    }
}
