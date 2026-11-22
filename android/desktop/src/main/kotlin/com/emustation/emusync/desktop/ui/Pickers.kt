package com.emustation.emusync.desktop.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.emustation.emusync.desktop.platform.isWindows
import org.jetbrains.skia.Image
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import javax.swing.JFileChooser

/** ファイル・フォルダを選ぶ画面（Windows の標準の画面） */
object Pickers {
    fun file(title: String, extensions: Set<String>?): File? {
        val d = FileDialog(null as Frame?, title, FileDialog.LOAD)
        if (extensions != null) {
            // Windows の FileDialog は FilenameFilter が効かないので、ファイル名の欄にパターンを入れる
            if (isWindows) d.file = extensions.joinToString(";") { "*.$it" }
            else d.setFilenameFilter { _, name -> name.substringAfterLast('.').lowercase() in extensions }
        }
        d.isVisible = true
        return d.file?.let { File(d.directory, it) }
    }

    fun folder(title: String, start: String?): File? {
        val c = JFileChooser(start?.let { File(it) }).apply {
            dialogTitle = title
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        }
        return if (c.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) c.selectedFile else null
    }

    fun open(dir: File) = runCatching { Desktop.getDesktop().open(dir) }

    fun browse(url: String) = runCatching { Desktop.getDesktop().browse(URI(url)) }
}

/** カバー画像を読む（読めなければ null）。同じパスは使い回す */
object Covers {
    private val cache = ConcurrentHashMap<String, ImageBitmap>()

    fun load(path: String?): ImageBitmap? {
        if (path.isNullOrBlank()) return null
        cache[path]?.let { return it }
        val img = runCatching { Image.makeFromEncoded(File(path).readBytes()).toComposeImageBitmap() }.getOrNull() ?: return null
        cache[path] = img
        return img
    }
}
