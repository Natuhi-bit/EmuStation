package com.emustation.emusync.desktop

import com.emustation.emusync.desktop.platform.Sfx
import com.emustation.emusync.desktop.platform.Sounds
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 操作音：MP3・WAV を読み、選んだファイルを sounds/ に写して使う（音のない環境でも落ちない） */
class SoundsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** テスト用の短い音（ffmpeg で作ったサイン波） */
    private fun res(name: String) =
        File(tmp.root, name).apply { writeBytes(File(System.getProperty("user.dir"), "src/test/resources/$name").readBytes()) }

    @After
    fun reset() {
        Sounds.listener = null
    }

    @Test
    fun decodeMp3AndWav() {
        val mp3 = Sounds.decode(res("beep.mp3"))
        assertEquals(48000f, mp3.format.sampleRate)
        assertEquals(2, mp3.format.channels)
        assertEquals(16, mp3.format.sampleSizeInBits)
        // 0.08 秒・2 チャンネル・16 ビット ≒ 15 KB（MP3 の前後の無音の分は多め）
        assertTrue("${mp3.data.size}", mp3.data.size in 10_000..40_000)
        val wav = Sounds.decode(res("beep.wav"))
        assertEquals(44100f, wav.format.sampleRate)
        assertEquals(1, wav.format.channels)
    }

    @Test
    fun install_copiesIntoSoundsFolder_andRejectsBadFiles() {
        val dir = File(tmp.root, "sounds")
        Sounds.install(dir, Sfx.Cursor, res("beep.mp3"))
        assertEquals("cursor.mp3", Sounds.fileOf(dir, Sfx.Cursor)?.name)
        // 入れ替え（形式が変わっても前のものは消す）
        Sounds.install(dir, Sfx.Cursor, res("beep.wav"))
        assertEquals(listOf("cursor.wav"), dir.list()!!.toList())
        Sounds.remove(dir, Sfx.Cursor)
        assertNull(Sounds.fileOf(dir, Sfx.Cursor))
        // 読めないもの
        val bad = File(tmp.root, "bad.mp3").apply { writeBytes(ByteArray(100) { 7 }) }
        assertTrue(runCatching { Sounds.install(dir, Sfx.Decide, bad) }.isFailure)
        assertTrue(runCatching { Sounds.install(dir, Sfx.Decide, File(tmp.root, "x.ogg").apply { writeText("x") }) }.isFailure)
        assertNull(Sounds.fileOf(dir, Sfx.Decide))
    }

    @Test
    fun play_neverThrows_evenWithoutAudioDevice() {
        val dir = File(tmp.root, "sounds")
        Sounds.install(dir, Sfx.Back, res("beep.mp3"))
        Sounds.load(dir)
        var heard: Sfx? = null
        Sounds.listener = { heard = it }
        Sounds.play(Sfx.Back)
        Sounds.play(Sfx.Options) // 音がなくても
        assertNotNull(heard)
    }

    /** 手元の音のファイルが読めるか（EMUSYNC_SOUND_SAMPLE にパスを入れたときだけ） */
    @Test
    fun decodeUserSample() {
        val p = System.getenv("EMUSYNC_SOUND_SAMPLE") ?: return
        val pcm = Sounds.decode(File(p))
        println("SAMPLE ${pcm.format} bytes=${pcm.data.size}")
        assertTrue(pcm.data.isNotEmpty())
    }
}
