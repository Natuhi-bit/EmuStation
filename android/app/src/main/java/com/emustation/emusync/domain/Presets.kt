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
    val all = listOf(
        Preset("psp", "PSP", "PPSSPP", "SAVEDATA", emptyList(), "/storage/emulated/0/PSP"),
        Preset("vita", "PS Vita", "Vita3K", "ux0/user/00/savedata", emptyList(), "/storage/emulated/0/Vita3K"),
        Preset("ps2", "PS2", "ARMSX2", "memcards", listOf("*.bak"), "/storage/emulated/0/ARMSX2"),
        Preset("ps3", "PS3", "ARMSX3", "dev_hdd0/home/00000001/savedata", emptyList(), "/storage/emulated/0/ARMSX3"),
    )

    fun byName(name: String): Preset? = all.firstOrNull { it.name == name }

    private val NAME_RE = Regex("[A-Za-z0-9._-]+")

    /** ターゲット名に使える文字か（サーバーの規則と同じ） */
    fun isValidTargetName(name: String) = NAME_RE.matches(name)
}
