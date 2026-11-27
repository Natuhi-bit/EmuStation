package com.emustation.emusync.work

import com.emustation.emusync.domain.play.FolderSignature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PlayWatchServiceTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun signature_changesWhenSavesAreWritten() {
        val root = tmp.newFolder("memcards")
        assertEquals(FolderSignature.EMPTY, PlayWatchService.signature(File(root, "nope")))
        val f = File(root, "Mcd001.ps2").apply { writeText("a"); setLastModified(1_000_000) }
        File(root, "sub").mkdirs()
        File(root, "sub/x").writeText("bb")
        File(root, "sub/x").setLastModified(2_000_000)
        val s1 = PlayWatchService.signature(root)
        assertEquals(FolderSignature(2, 3, 2_000_000), s1)

        // 同じサイズのまま書き換えても、更新日時で分かる（メモリーカードはサイズが変わらない）
        f.writeText("c")
        f.setLastModified(3_000_000)
        assertNotEquals(s1, PlayWatchService.signature(root))
    }
}
