package com.emustation.emusync.domain

/** 設計書 §1.1 のプリセット */
data class Preset(
    /** ターゲット名（サーバー上のフォルダ名。Windows 側と共通） */
    val name: String,
    /** 機種名 */
    val machine: String,
    /** Android のエミュ */
    val emulator: String,
    /** データフォルダからの相対パス */
    val relSubdir: String,
    val excludes: List<String>,
    /** データフォルダの例（案内用） */
    val exampleDataDir: String,
)

object Presets {
    /** DS のゲーム本体・アーカイブ（セーブと同じフォルダにあっても送らない）。all より前に置く（初期化の順番） */
    val NDS_EXCLUDES = listOf("*.nds", "*.dsi", "*.srl", "*.zip", "*.7z", "*.rar")

    val all = listOf(
        Preset("psp", "PSP", "PPSSPP", "SAVEDATA", emptyList(), "/storage/emulated/0/PSP"),
        Preset("vita", "PS Vita", "Vita3K", "ux0/user/00/savedata", emptyList(), "/storage/emulated/0/Vita3K"),
        Preset("ps2", "PS2", "ARMSX2", "memcards", listOf("*.bak"), "/storage/emulated/0/ARMSX2"),
        Preset("ps3", "PS3", "ARMSX3", "dev_hdd0/home/00000001/savedata", emptyList(), "/storage/emulated/0/ARMSX3"),
        // melonDS：設定の「セーブファイルの場所」に決めたフォルダ（.sav の名前はゲームのファイル名。Windows とそろえる）。
        // ゲームと同じフォルダにしている場合に備えて、ゲーム本体は送らない
        Preset("nds", "DS", "melonDS", "", NDS_EXCLUDES, "/storage/emulated/0/melonDS/saves"),
        // Azahar：ユーザーフォルダの中。ゲーム本体（インストールしたゲームの content）は送らない
        Preset("3ds", "3DS", "Azahar", "sdmc/Nintendo 3DS/00000000000000000000000000000000/00000000000000000000000000000000", listOf("*/content/*"), "/storage/emulated/0/Azahar"),
        // Dolphin：ユーザーフォルダの中の、ディスクのゲームのセーブ。
        // 今の Dolphin は既定で Android/data の中に置く（ほかのアプリからは読めない）ので、外のフォルダにある必要がある
        Preset("wii", "Wii", "Dolphin", "Wii/title/00010000", emptyList(), "/storage/emulated/0/dolphin-emu"),
    )

    fun byName(name: String): Preset? = all.firstOrNull { it.name == name }

    private val NAME_RE = Regex("[A-Za-z0-9._-]+")

    /** ターゲット名に使える文字か（サーバーの規則と同じ） */
    fun isValidTargetName(name: String) = NAME_RE.matches(name)
}
