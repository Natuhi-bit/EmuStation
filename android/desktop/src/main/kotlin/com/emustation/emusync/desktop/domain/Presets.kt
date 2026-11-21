package com.emustation.emusync.desktop.domain

import java.io.File

/** Windows のエミュのプリセット。ターゲット名は Android と同じ（同じセーブを共有するため） */
data class SystemPreset(
    val target: String,
    val machine: String,
    val emulator: String,
    val exeNames: List<String>,
    /** 起動の引数のひな形。{rom} をゲームのパスに置き換える */
    val args: String,
    val romExtensions: Set<String>,
    val excludes: List<String>,
    /** ROM の欄の説明 */
    val romHint: String,
    /** セーブのフォルダの候補（エミュの exe の場所から探すものを含む） */
    val saveCandidates: (exeDir: File?) -> List<File>,
)

object Presets {
    private val home = File(System.getProperty("user.home"))
    private val appData = File(System.getenv("APPDATA") ?: home.path)
    private val documents = File(home, "Documents")

    val all = listOf(
        SystemPreset(
            "psp", "PSP", "PPSSPP", listOf("PPSSPPWindows64.exe", "PPSSPPWindows.exe"),
            "\"{rom}\"", setOf("iso", "cso", "chd", "pbp", "zso", "elf"), emptyList(),
            "ISO / CSO / CHD のファイル",
        ) { dir -> listOfNotNull(dir?.let { File(it, "memstick/PSP/SAVEDATA") }, File(documents, "PPSSPP/PSP/SAVEDATA")) },
        SystemPreset(
            "vita", "PS Vita", "Vita3K", listOf("Vita3K.exe"),
            "-r {rom}", emptySet(), emptyList(),
            "タイトル ID（例 PCSG00001）。Vita3K にインストール済みのもの",
        ) { dir -> listOfNotNull(File(appData, "Vita3K/Vita3K/ux0/user/00/savedata"), dir?.let { File(it, "ux0/user/00/savedata") }) },
        SystemPreset(
            "ps2", "PS2", "PCSX2", listOf("pcsx2-qt.exe", "pcsx2.exe"),
            // -batch：ゲームを閉じたら PCSX2 も終わる（終わったら送信するため）
            "-batch -- \"{rom}\"", setOf("iso", "chd", "cso", "bin", "gz", "zso", "mdf", "nrg"), listOf("*.bak"),
            "ISO / CHD などのファイル",
        ) { dir -> listOfNotNull(File(documents, "PCSX2/memcards"), dir?.let { File(it, "memcards") }) },
        SystemPreset(
            "ps3", "PS3", "RPCS3", listOf("rpcs3.exe"),
            // --no-gui：ゲームを閉じたら RPCS3 も終わる
            "--no-gui \"{rom}\"", setOf("bin", "iso"), emptyList(),
            "EBOOT.BIN（PS3_GAME\\USRDIR の中）または ISO",
        ) { dir -> listOfNotNull(dir?.let { File(it, "dev_hdd0/home/00000001/savedata") }) },
    )

    fun byTarget(target: String) = all.firstOrNull { it.target == target }

    fun machineOf(target: String) = byTarget(target)?.machine ?: target

    /** セーブのフォルダの候補のうち、実際にあるもの */
    fun findSavePath(preset: SystemPreset, exe: String): String? =
        preset.saveCandidates(File(exe).parentFile).firstOrNull { it.isDirectory }?.path

    private val NAME_RE = Regex("[A-Za-z0-9._-]+")
    fun isValidTarget(name: String) = NAME_RE.matches(name)
}

/** フォルダの中のゲームを探す（ROM の拡張子で。PS3 は EBOOT.BIN、Vita は ux0/app のタイトル ID） */
object LibraryScanner {
    data class Found(val title: String, val rom: String)

    fun scan(preset: SystemPreset, folder: File, maxDepth: Int = 4): List<Found> {
        if (!folder.isDirectory) return emptyList()
        return when (preset.target) {
            "vita" -> {
                // ux0/app/<タイトルID> または、そのフォルダそのもの
                val app = listOf(File(folder, "ux0/app"), File(folder, "app"), folder).first { it.isDirectory }
                app.listFiles().orEmpty().filter { it.isDirectory && TITLE_ID.matches(it.name) }
                    .map { Found(it.name, it.name) }
            }
            "ps3" -> folder.walkTopDown().maxDepth(maxDepth + 2)
                .filter { it.isFile && (it.name.equals("EBOOT.BIN", true) && it.parentFile?.name.equals("USRDIR", true) || it.extension.equals("iso", true)) }
                .map { f ->
                    val gameDir = if (f.extension.equals("iso", true)) f else f.parentFile.parentFile.parentFile
                    Found(cleanTitle(gameDir.nameWithoutExtension), f.path)
                }.toList()
            else -> folder.walkTopDown().maxDepth(maxDepth)
                .filter { it.isFile && it.extension.lowercase() in preset.romExtensions }
                // .bin は .cue と組のときは .cue だけにしたいが、PS2 は .bin 単体もあるので両方出す
                .map { Found(cleanTitle(it.nameWithoutExtension), it.path) }.toList()
        }.sortedBy { it.title.lowercase() }
    }

    /** "Persona 3 FES (Japan) [SLPM-66445]" → "Persona 3 FES" */
    fun cleanTitle(name: String): String =
        name.replace(Regex("\\s*[\\(\\[][^\\)\\]]*[\\)\\]]"), "").replace('_', ' ').trim().ifEmpty { name }

    private val TITLE_ID = Regex("[A-Z]{4}\\d{5}")
}
