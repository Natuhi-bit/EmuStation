package com.emustation.emusync.data.storage

/**
 * フォルダ選択画面（ACTION_OPEN_DOCUMENT_TREE）で得た URI を実パスに変換する（設計書 §8.1）。
 *
 * - `content://com.android.externalstorage.documents/tree/primary:PSP` → `/storage/emulated/0/PSP`
 * - `.../tree/XXXX-XXXX:Emu/ARMSX2` → `/storage/XXXX-XXXX/Emu/ARMSX2`（SD カード）
 * - それ以外は null（手入力してもらう）
 */
object TreeUriPaths {
    private const val PREFIX = "content://com.android.externalstorage.documents/tree/"
    private val SD_VOLUME = Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}")

    fun toPath(uri: String, primaryRoot: String = "/storage/emulated/0"): String? {
        if (!uri.startsWith(PREFIX)) return null
        val docId = percentDecode(uri.substring(PREFIX.length).substringBefore('/')) ?: return null
        val colon = docId.indexOf(':')
        if (colon < 0) return null
        val volume = docId.substring(0, colon)
        val path = docId.substring(colon + 1).trim('/')
        val root = when {
            volume == "primary" -> primaryRoot.trimEnd('/')
            SD_VOLUME.matches(volume) -> "/storage/$volume"
            else -> return null
        }
        return if (path.isEmpty()) root else "$root/$path"
    }

    /** `Android/data/` の中か（Android 11 以降、他のアプリから読めない） */
    fun isInsideAndroidData(path: String): Boolean {
        val p = path.trimEnd('/') + "/"
        return Regex("/Android/(data|obb)/", RegexOption.IGNORE_CASE).containsMatchIn(p)
    }

    /** `%XX` を UTF-8 として戻す。`+` はそのまま（URI のパス部分なので） */
    private fun percentDecode(s: String): String? {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%') {
                if (i + 2 >= s.length) return null
                val v = s.substring(i + 1, i + 3).toIntOrNull(16) ?: return null
                out.write(v)
                i += 3
            } else {
                out.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }
}
