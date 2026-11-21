package com.emustation.emusync.data.storage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FnmatchTest {
    /** 期待値は scripts/gen_fnmatch_cases.py が Python の fnmatchcase で作ったもの */
    @Test
    fun matchesPythonFnmatch() {
        val doc = Json.parseToJsonElement(
            javaClass.classLoader!!.getResource("fnmatch_cases.json")!!.readText(),
        ).jsonObject
        val cases = doc["cases"]!!.jsonArray
        assertTrue(cases.size > 1000)
        val wrong = cases.mapNotNull { c ->
            val (p, n, e) = c.jsonArray
            val pattern = p.jsonPrimitive.content
            val name = n.jsonPrimitive.content
            val expected = e.jsonPrimitive.boolean
            if (GlobPattern(pattern).matches(name) != expected) "$pattern vs $name: expected $expected" else null
        }
        assertEquals(wrong.joinToString("\n"), 0, wrong.size)
    }

    @Test
    fun excludeMatcher_pathOrFileName() {
        val m = ExcludeMatcher(listOf("*.bak", "BASLUS-*/ICON?.PNG"))
        assertTrue(m.isExcluded("Mcd001.ps2.bak"))
        assertTrue(m.isExcluded("sub/dir/Mcd001.ps2.bak")) // ファイル名で一致
        assertTrue(m.isExcluded("BASLUS-12345/ICON0.PNG")) // 相対パス全体で一致
        assertFalse(m.isExcluded("Mcd001.ps2"))
        assertFalse(m.isExcluded("x/BASLUS-12345/icon0.png")) // 大文字と小文字は区別する
        assertFalse(ExcludeMatcher(listOf("")).isExcluded("a"))
    }
}
