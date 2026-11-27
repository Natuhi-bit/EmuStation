package com.emustation.emusync.data.storage

import com.emustation.emusync.domain.sync.AtomicWrite
import com.emustation.emusync.domain.sync.LocalFs
import com.emustation.emusync.domain.sync.SyncTarget
import com.emustation.emusync.domain.sync.UploadContent
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/**
 * `java.io` / `java.nio` によるファイル操作（設計書 §8.1。SAF は使わない）。
 * Android に依存しないので、JVM の単体テストでもそのまま使う。
 */
class JavaLocalFs(
    private val hashCache: HashCache = HashCache.NONE,
    private val clock: () -> Long = System::currentTimeMillis,
) : LocalFs {

    override fun scan(target: SyncTarget): Map<String, String> {
        val root = File(target.root)
        if (!root.isDirectory) return emptyMap()
        val exclude = ExcludeMatcher(target.excludes)
        val out = mutableMapOf<String, String>()
        for ((rel, file) in listFiles(root.toPath())) {
            if (file.fileName.toString().endsWith(TMP_SUFFIX)) continue
            if (exclude.isExcluded(rel)) continue
            out[rel] = hashWithCache(target.name, rel, file.toFile())
        }
        hashCache.retain(target.name, out.keys)
        return out
    }

    /**
     * フォルダ以下の通常のファイルを `/` 区切りの相対パスで列挙する。
     * Python の `os.walk` と同じく、フォルダへのシンボリックリンクはたどらず、
     * 読めないフォルダは黙って飛ばす。
     */
    private fun listFiles(root: Path): List<Pair<String, Path>> {
        val out = mutableListOf<Pair<String, Path>>()
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                // attrs はリンクをたどらない。ファイルへのリンクは os.walk と同じく含める
                if (attrs.isRegularFile || (attrs.isSymbolicLink && Files.isRegularFile(file))) {
                    out += root.relativize(file).joinToString("/") to file
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                FileVisitResult.CONTINUE
        })
        return out
    }

    private fun hashWithCache(target: String, rel: String, f: File): String {
        val size = f.length()
        val mtime = f.lastModified()
        hashCache.get(target, rel)?.let {
            if (it.size == size && it.lastModified == mtime) return it.sha256
        }
        val sha = sha256(f)
        // ハッシュ計算中に書き換えられていたら記録しない。
        // 更新されたばかり（RACY_MS 以内）のファイルも記録しない：更新日時の細かさ（1〜2 秒）の中で
        // さらに書き換えられると、大きさも更新日時も同じまま中身だけ変わり、古いハッシュを使い続けてしまう
        // （エミュが書き込んでいる最中に調べたとき。メモリーカードは大きさがいつも同じ）。Git の racy-clean と同じ考え方
        if (f.length() == size && f.lastModified() == mtime && clock() - mtime > RACY_MS) {
            hashCache.put(target, rel, CachedHash(size, mtime, sha))
        }
        return sha
    }

    override fun forgetHash(target: SyncTarget, rel: String) = hashCache.remove(target.name, rel)

    override fun prepareUpload(path: String): UploadContent {
        val f = File(path)
        val size = f.length()
        val sha = sha256(f)
        return UploadContent(size, sha) { FileInputStream(f) }
    }

    override fun beginAtomicWrite(path: String): AtomicWrite {
        val target = File(path)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + TMP_SUFFIX)
        return FileAtomicWrite(target, tmp)
    }

    override fun copy(src: String, dst: String) {
        val d = File(dst)
        d.parentFile?.mkdirs()
        Files.copy(
            File(src).toPath(), d.toPath(),
            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES,
        )
    }

    override fun move(src: String, dst: String) {
        val d = File(dst)
        d.parentFile?.mkdirs()
        Files.move(File(src).toPath(), d.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private class FileAtomicWrite(private val target: File, private val tmp: File) : AtomicWrite {
        private val stream = FileOutputStream(tmp)
        private var done = false
        override val output: OutputStream = stream

        override fun commit() {
            check(!done)
            stream.flush()
            stream.fd.sync()
            stream.close()
            try {
                Files.move(
                    tmp.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (e: IOException) {
                tmp.delete()
                throw e
            }
            done = true
        }

        override fun abort() {
            if (done) return
            done = true
            runCatching { stream.close() }
            tmp.delete()
        }
    }

    companion object {
        const val TMP_SUFFIX = ".emusync-tmp"

        /** これより新しく更新されたファイルのハッシュはキャッシュしない（ミリ秒。FAT の更新日時は 2 秒刻み） */
        const val RACY_MS = 3_000L

        fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            FileInputStream(f).use { input ->
                val buf = ByteArray(1 shl 20)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().toHex()
        }
    }
}

fun ByteArray.toHex(): String {
    val hex = "0123456789abcdef"
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xff
        sb.append(hex[v shr 4]).append(hex[v and 0x0f])
    }
    return sb.toString()
}
