package com.emustation.emusync.desktop.data

import com.emustation.emusync.desktop.platform.AppPaths
import com.emustation.emusync.desktop.platform.SecretBox
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** 機種（＝同期のターゲット）ごとの、エミュとセーブの場所 */
@Serializable
data class SystemConfig(
    /** ターゲット名（psp / vita / ps2 / ps3 など。Android と同じ名前） */
    val target: String,
    val emulatorExe: String = "",
    /** 起動の引数のひな形。{rom} をゲームのパスに置き換える */
    val args: String = "\"{rom}\"",
    /** セーブのフォルダ（同期するフォルダ） */
    val savePath: String = "",
    val excludes: List<String> = emptyList(),
    val enabled: Boolean = true,
)

/** ライブラリのゲーム */
@Serializable
data class Game(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val target: String,
    /** ROM のパス（Vita3K はタイトル ID） */
    val rom: String,
    val coverPath: String? = null,
    val lastPlayedAt: Long? = null,
    val playSeconds: Long = 0,
    val addedAt: Long = System.currentTimeMillis(),
    /** 型番（例 SLPM-66168）。ROM やファイル名から自動で調べる */
    val serial: String? = null,
    /** 地域（日本・北米・欧州 など） */
    val region: String? = null,
    /** タイトルを自分で書き換えた（自動で調べたタイトルで上書きしない） */
    val titleEdited: Boolean = false,
    /** カバーを自分で選んだ（自動で取ったカバーで上書きしない） */
    val coverEdited: Boolean = false,
    /** 情報とカバーを最後に調べた日時。null ならまだ */
    val infoCheckedAt: Long? = null,
)

@Serializable
data class AppConfig(
    val serverUrl: String = "",
    /** SecretBox で守ったトークン */
    val tokenProtected: String? = null,
    /** 取り込んだ CA 証明書のファイル名（データフォルダの中）。なければ OS の信頼ストア */
    val caFile: String? = null,
    val deviceName: String? = null,
    val periodicPush: Boolean = true,
    val startMinimized: Boolean = true,
    /** 背景の色（PS3 のテーマのように選べる） */
    val themeColor: String = "blue",
    /** コントローラー（ROG Ally・Xbox のコントローラーなど）で操作する */
    val gamepad: Boolean = true,
    /** 決定のボタン（"A"：下のボタン、"B"：右のボタン。PS3 の日本の並びは右で決定） */
    val confirmButton: String = "A",
    val systems: List<SystemConfig> = emptyList(),
    val games: List<Game> = emptyList(),
) {
    val token: String get() = tokenProtected?.let { SecretBox.unprotect(it) }.orEmpty()

    fun withToken(token: String) = copy(tokenProtected = if (token.isBlank()) null else SecretBox.protect(token.trim()))

    fun system(target: String) = systems.firstOrNull { it.target == target }

    fun withSystem(s: SystemConfig) = copy(systems = systems.filter { it.target != s.target } + s)

    fun withGame(g: Game) = copy(games = games.filter { it.id != g.id } + g)
}

/** 設定を JSON で保存する（書き込みは一時ファイル → 置き換え） */
class ConfigStore(private val file: File = File(AppPaths.root, "config.json")) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    fun load(): AppConfig = if (file.isFile) {
        runCatching { json.decodeFromString<AppConfig>(file.readText()) }.getOrElse { AppConfig() }
    } else {
        AppConfig()
    }

    @Synchronized
    fun save(c: AppConfig) {
        file.parentFile.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(AppConfig.serializer(), c))
        java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
}
