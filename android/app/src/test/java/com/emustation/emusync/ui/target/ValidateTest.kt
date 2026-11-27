package com.emustation.emusync.ui.target

import com.emustation.emusync.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ValidateTest {
    private val ok = TargetForm(preset = null, name = "gba", dataDir = "/storage/emulated/0/GBA", relSubdir = "saves")

    @Test
    fun valid() = assertFalse(validate(ok, isNew = true, existingNames = setOf("psp")).any)

    @Test
    fun name() {
        assertEquals(R.string.err_target_name_empty, validate(ok.copy(name = " "), true, emptySet()).name?.id)
        assertEquals(R.string.err_target_name_chars, validate(ok.copy(name = "ゲーム"), true, emptySet()).name?.id)
        assertEquals(R.string.err_target_name_chars, validate(ok.copy(name = "a/b"), true, emptySet()).name?.id)
        assertEquals(R.string.err_target_name_dup, validate(ok, true, setOf("gba")).name?.id)
        // 編集中は名前を変えられないので検査しない
        assertNull(validate(ok.copy(name = "x/y"), false, emptySet()).name)
    }

    @Test
    fun dataDir() {
        assertEquals(R.string.err_data_dir_empty, validate(ok.copy(dataDir = ""), true, emptySet()).dataDir?.id)
        assertEquals(R.string.err_data_dir_absolute, validate(ok.copy(dataDir = "PSP"), true, emptySet()).dataDir?.id)
    }

    @Test
    fun relSubdir() {
        assertEquals(R.string.err_rel_subdir, validate(ok.copy(relSubdir = "../x"), true, emptySet()).relSubdir?.id)
        assertNull(validate(ok.copy(relSubdir = ""), true, emptySet()).relSubdir)
    }

    @Test
    fun entityAndSyncDir() {
        val f = ok.copy(dataDir = " /storage/emulated/0/GBA/ ", relSubdir = "/saves/", excludesText = " *.bak \n\n*.log")
        val e = f.toEntity(3)
        assertEquals("/storage/emulated/0/GBA", e.dataDir)
        assertEquals("saves", e.relSubdir)
        assertEquals(listOf("*.bak", "*.log"), e.excludes)
        assertEquals("/storage/emulated/0/GBA/saves", f.syncDir)
        assertEquals("/storage/emulated/0/GBA", f.copy(relSubdir = "").syncDir)
    }
}
