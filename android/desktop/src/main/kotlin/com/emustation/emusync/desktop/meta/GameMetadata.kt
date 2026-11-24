package com.emustation.emusync.desktop.meta

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** インターネットから取ってきた（または ROM から読んだ）ゲームの情報 */
data class GameMeta(
    val serial: String?,
    /** 日本語のタイトル（日本版なら日本語。海外版は英語） */
    val title: String?,
    /** 地域（日本・北米・欧州 など） */
    val region: String?,
    /** カバー画像（PNG・JPEG）。見つからなければ ROM の中のアイコン、それもなければ null */
    val cover: ByteArray?,
    /** カバーをどこから取ったか（表示用） */
    val coverSource: String?,
)

/** 取りに行く先。テストでは差し替える */
data class MetaSources(
    /** PCSX2 のゲームの一覧（PS2 の日本語のタイトル） */
    val pcsx2GameIndex: String = "https://raw.githubusercontent.com/PCSX2/pcsx2/master/bin/resources/GameIndex.yaml",
    /** libretro のデータベース（型番 → Redump・No-Intro の名前と地域） */
    val libretroDat: String = "https://raw.githubusercontent.com/libretro/libretro-database/master/metadat",
    /** PS2 のカバー（PCSX2 が使っているもの）。{serial} は SLPM-66168 の形 */
    val ps2Cover: String = "https://raw.githubusercontent.com/xlenore/ps2-covers/main/covers/default/{serial}.jpg",
    /** PS3 のカバー（GameTDB）。{lang} は JA・US・EN など、{id} は BLJM60051 の形 */
    val ps3Cover: String = "https://art.gametdb.com/ps3/cover/{lang}/{id}.jpg",
    /** libretro のサムネイル（名前で探す） */
    val libretroThumbs: String = "https://raw.githubusercontent.com/libretro-thumbnails",
    /** libretro のサムネイルの一覧（GitHub API） */
    val libretroTree: String = "https://api.github.com/repos/libretro-thumbnails/{repo}/git/trees/master?recursive=1",
)

/**
 * ゲームの情報とカバーを探す。
 * 1. ROM から型番・タイトル・アイコンを読む
 * 2. データベース（PCSX2・libretro。30 日ごとに取り直して [cacheDir] に置く）で、タイトルと地域を調べる
 * 3. カバーを探す（PS2：PCSX2 のカバー集、PS3：GameTDB、PSP・Vita：libretro）。なければ ROM の中のアイコン
 * つながらないときは、置いてあるデータベースと ROM だけで分かることを返す。
 */
class GameMetadata(
    private val cacheDir: File,
    private val http: OkHttpClient = defaultHttp,
    private val sources: MetaSources = MetaSources(),
    private val maxAgeMs: Long = 30L * 24 * 3600 * 1000,
) {
    private val dats = ConcurrentHashMap<String, DatIndex>()
    private val thumbs = ConcurrentHashMap<String, List<String>>()
    @Volatile private var gameIndex: Map<String, Pcsx2Entry>? = null

    suspend fun lookup(target: String, rom: String, savePath: String? = null, emulatorExe: String? = null): GameMeta =
        withContext(Dispatchers.IO) {
            val info = RomReader.read(target, rom, savePath, emulatorExe)
            val dat = datFor(target)
            // 型番が読めなかったときは、ファイル名を Redump・No-Intro の名前と照らし合わせる（"Ryuu ga Gotoku (Japan).chd" など）
            val byName = if (info.serial == null) dat?.byName(java.io.File(rom).nameWithoutExtension).orEmpty() else emptyList()
            val index = if (target == "ps2") pcsx2Index() else null
            // 名前で見つけたときは、同じ名前の版（廉価版など）が並ぶので、元の版（PCSX2 の名前に [ ] の付かないもの）を選ぶ
            val serial = info.serial ?: byName.flatMap { it.serials }.let { cands ->
                cands.firstOrNull { c -> index?.get(c)?.name?.contains('[') == false } ?: cands.firstOrNull()
            }
            val entries = serial?.let { dat?.bySerial(it) }.orEmpty().ifEmpty { byName }
            val pcsx2 = serial?.let { index?.get(it) }

            val title = when {
                pcsx2?.name != null -> pcsx2.name
                info.title != null -> info.title
                entries.isNotEmpty() -> baseTitle(entries.first().name)
                else -> null
            }
            val region = pcsx2?.region?.let(::regionOfPcsx2)
                ?: entries.firstOrNull()?.region?.let(::regionOfDat)
                ?: serial?.let(::regionOfSerial)

            val (cover, source) = findCover(target, serial, entries.map { it.name }, title)
                ?: (info.icon?.takeIf(::isImage)?.let { it to "ROM のアイコン" } ?: (null to null))
            GameMeta(serial, title, region, cover, source)
        }

    // ---------------------------------------------------------------- カバー
    private fun findCover(target: String, serial: String?, datNames: List<String>, title: String?): Pair<ByteArray, String>? {
        val tries = mutableListOf<Pair<String, String>>() // URL と、出どころ
        when (target) {
            "ps2" -> serial?.let { tries += sources.ps2Cover.replace("{serial}", it) to "PCSX2 のカバー集" }
            "ps3" -> serial?.let { s ->
                val id = s.replace("-", "")
                for (lang in ps3Langs(s)) tries += sources.ps3Cover.replace("{lang}", lang).replace("{id}", id) to "GameTDB"
            }
        }
        thumbRepo(target)?.let { repo ->
            for (name in thumbnailCandidates(repo, datNames, serial)) {
                tries += url(sources.libretroThumbs, repo, "master", "Named_Boxarts", libretroFileName(name) + ".png") to "libretro"
            }
        }
        for ((url, source) in tries.distinctBy { it.first }) {
            download(url)?.takeIf(::isImage)?.let { return it to source }
        }
        return null
    }

    /** libretro のサムネイルの名前の候補。データベースの名前そのもの → 同じゲームの別の版（地域・版違い） */
    private fun thumbnailCandidates(repo: String, datNames: List<String>, serial: String?): List<String> {
        if (datNames.isEmpty()) return emptyList()
        val exact = datNames.distinct()
        val list = thumbList(repo) ?: return exact
        val have = list.associateBy { normalizeName(it) }
        val out = exact.mapNotNull { have[normalizeName(libretroFileName(it))] }.toMutableList()
        val bases = exact.map { normalizeName(baseTitle(it)) }.toSet()
        val wantJapan = serial?.let(::regionOfSerial) == "日本"
        out += list.filter { normalizeName(baseTitle(it)) in bases }
            .sortedWith(compareByDescending<String> { wantJapan && "(Japan" in it }.thenBy { it.length })
        return out.distinct().take(6)
    }

    private fun thumbList(repo: String): List<String>? = thumbs[repo] ?: run {
        val f = File(cacheDir, "$repo.boxarts.txt")
        val body = cached(f, sources.libretroTree.replace("{repo}", repo)) { text ->
            Json.parseToJsonElement(text).jsonObject["tree"]!!.jsonArray
                .mapNotNull { it.jsonObject["path"]?.jsonPrimitive?.content }
                .filter { it.startsWith("Named_Boxarts/") && it.endsWith(".png") }
                .joinToString("\n") { it.removePrefix("Named_Boxarts/").removeSuffix(".png") }
        } ?: return null
        body.lines().filter { it.isNotBlank() }.also { thumbs[repo] = it }
    }

    // ---------------------------------------------------------------- データベース
    private fun datFor(target: String): DatIndex? {
        val path = when (target) {
            "ps2" -> listOf("redump", "Sony - PlayStation 2.dat")
            "psp" -> listOf("redump", "Sony - PlayStation Portable.dat")
            "ps3" -> listOf("redump", "Sony - PlayStation 3.dat")
            "vita" -> listOf("no-intro", "Sony - PlayStation Vita.dat")
            else -> return null
        }
        dats[target]?.let { return it }
        val text = cached(File(cacheDir, "$target.dat"), url(sources.libretroDat, *path.toTypedArray())) ?: return null
        return DatIndex.parse(text).also { dats[target] = it }
    }

    private fun pcsx2Index(): Map<String, Pcsx2Entry>? = gameIndex ?: run {
        val text = cached(File(cacheDir, "GameIndex.yaml"), sources.pcsx2GameIndex) ?: return null
        Pcsx2GameIndex.parse(text).also { gameIndex = it }
    }

    /**
     * 置いてあるファイルが新しければそれを使い、古ければ取り直す（取れなければ古いものを使う）。
     * [transform] は取ってきた中身を、置く形に直す
     */
    private fun cached(f: File, url: String, transform: (String) -> String = { it }): String? {
        val fresh = f.isFile && System.currentTimeMillis() - f.lastModified() < maxAgeMs
        if (!fresh) {
            download(url)?.let { bytes ->
                runCatching {
                    val text = transform(String(bytes, Charsets.UTF_8))
                    f.parentFile.mkdirs()
                    val tmp = File(f.parentFile, f.name + ".tmp")
                    tmp.writeText(text)
                    java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
        return f.takeIf { it.isFile }?.readText()
    }

    private fun download(url: String): ByteArray? = runCatching {
        val req = Request.Builder().url(url).header("User-Agent", "emusync-desktop").build()
        http.newCall(req).execute().use { r -> if (r.isSuccessful) r.body.bytes() else null }
    }.getOrNull()

    companion object {
        private val defaultHttp: OkHttpClient by lazy {
            OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
        }

        private fun thumbRepo(target: String) = when (target) {
            "ps2" -> "Sony_-_PlayStation_2"
            "psp" -> "Sony_-_PlayStation_Portable"
            "ps3" -> "Sony_-_PlayStation_3"
            "vita" -> "Sony_-_PlayStation_Vita"
            else -> null
        }

        /** base の後ろにパスの部分を足す（空白などは %20 に。括弧などはそのまま） */
        private fun url(base: String, vararg segments: String): String =
            base.trimEnd('/').toHttpUrl().newBuilder().apply { segments.forEach { addPathSegment(it) } }.build().toString()

        /** libretro のサムネイルのファイル名では &*\/:`<>?\| を _ にする */
        fun libretroFileName(name: String) = name.replace(Regex("[&*/:`<>?\\\\|]"), "_")

        private fun normalizeName(s: String) = s.lowercase().replace(Regex("\\s+"), " ").trim()

        /** "Ryuu ga Gotoku (Japan) (Rev 1)" → "Ryuu ga Gotoku" */
        fun baseTitle(name: String) = name.replace(Regex("\\s*[\\(\\[][^\\)\\]]*[\\)\\]]"), "").trim()

        fun isImage(b: ByteArray): Boolean = b.size > 8 && (
            (b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()) ||
                (b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte())
            )

        /** GameTDB の言語（地域）の順番 */
        fun ps3Langs(serial: String): List<String> = when (regionOfSerial(serial)) {
            "日本" -> listOf("JA", "US", "EN")
            "北米" -> listOf("US", "EN", "JA")
            "欧州" -> listOf("EN", "FR", "DE", "US")
            else -> listOf("US", "EN", "JA")
        }

        /** 型番の 3 文字目（PS2・PSP・PS3）や先頭（Vita）から、地域 */
        fun regionOfSerial(serial: String): String? {
            val p = serial.take(4).uppercase()
            if (p.startsWith("PCS")) return when (p[3]) {
                'G', 'C' -> "日本"; 'E', 'A' -> "北米"; 'B', 'F' -> "欧州"; 'H', 'D' -> "アジア"; else -> null
            }
            if (p.length < 4) return null
            return when (p[2]) {
                'P', 'J' -> "日本"
                'U' -> "北米"
                'E' -> "欧州"
                'A' -> "アジア"
                'K' -> "韓国"
                'C' -> "中国"
                else -> null
            }
        }

        fun regionOfPcsx2(r: String): String? = when {
            r.startsWith("NTSC-J") -> "日本"
            r.startsWith("NTSC-U") -> "北米"
            r.startsWith("PAL") -> "欧州"
            r.startsWith("NTSC-K") -> "韓国"
            r.startsWith("NTSC-C") -> "アジア"
            else -> null
        }

        fun regionOfDat(r: String): String = r.split('/', ',').map { it.trim() }.joinToString("・") {
            when (it) {
                "Japan" -> "日本"; "USA" -> "北米"; "Europe" -> "欧州"; "Asia" -> "アジア"; "Korea" -> "韓国"
                "China" -> "中国"; "Taiwan" -> "台湾"; "Hong Kong" -> "香港"; "World" -> "世界"
                else -> it
            }
        }
    }
}

/** libretro のデータベース（clrmamepro の DAT）。型番と名前で引けるようにする */
class DatIndex private constructor(private val serials: Map<String, List<DatEntry>>, private val names: Map<String, List<DatEntry>>) {
    fun bySerial(serial: String): List<DatEntry> = serials[key(serial)].orEmpty()

    /** 同じ名前で、型番ごとに並ぶことがある（通常版・廉価版など） */
    fun byName(name: String): List<DatEntry> = names[name.lowercase().trim()].orEmpty()

    companion object {
        private fun key(s: String) = s.uppercase().replace("-", "").replace("_", "").replace(".", "").trim()

        fun parse(text: String): DatIndex {
            val serials = HashMap<String, MutableList<DatEntry>>()
            val names = HashMap<String, MutableList<DatEntry>>()
            var name: String? = null
            var region: String? = null
            var ser: String? = null
            var depth = 0
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                when {
                    line.startsWith("game (") -> { depth = 1; name = null; region = null; ser = null }
                    depth == 1 && line == ")" -> {
                        depth = 0
                        val n = name ?: continue
                        val list = ser?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
                        val e = DatEntry(n, region, list)
                        list.forEach { serials.getOrPut(key(it)) { mutableListOf() } += e }
                        names.getOrPut(n.lowercase()) { mutableListOf() } += e
                    }
                    depth == 1 && line.startsWith("name ") -> name = quoted(line)
                    depth == 1 && line.startsWith("region ") -> region = quoted(line)
                    depth == 1 && line.startsWith("serial ") -> ser = quoted(line)
                }
            }
            return DatIndex(serials, names)
        }

        private fun quoted(line: String) = line.substringAfter('"').substringBeforeLast('"')
    }
}

data class DatEntry(val name: String, val region: String?, val serials: List<String>)

data class Pcsx2Entry(val name: String?, val nameEn: String?, val region: String?)

/** PCSX2 の GameIndex.yaml から、型番 → 名前（日本のゲームは日本語）と地域だけを読む */
object Pcsx2GameIndex {
    private val KEY = Regex("^([A-Z]{4}-\\d{5}):\\s*$")

    fun parse(text: String): Map<String, Pcsx2Entry> {
        val out = HashMap<String, Pcsx2Entry>(16_384)
        var cur: String? = null
        var name: String? = null
        var nameEn: String? = null
        var region: String? = null
        fun flush() {
            cur?.let { out[it] = Pcsx2Entry(name, nameEn, region) }
        }
        for (line in text.lineSequence()) {
            val m = KEY.find(line)
            if (m != null) {
                flush()
                cur = m.groupValues[1]; name = null; nameEn = null; region = null
                continue
            }
            if (cur == null || !line.startsWith("  ") || line.startsWith("   ")) continue
            val t = line.trim()
            when {
                t.startsWith("name:") -> name = value(t)
                t.startsWith("name-en:") -> nameEn = value(t)
                t.startsWith("region:") -> region = value(t)
            }
        }
        flush()
        return out
    }

    private fun value(t: String): String {
        val v = t.substringAfter(':').trim()
        return if (v.startsWith("\"") && v.endsWith("\"") && v.length >= 2) v.substring(1, v.length - 1).replace("\\\"", "\"") else v
    }
}
