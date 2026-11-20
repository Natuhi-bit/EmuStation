package com.emustation.emusync.data.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TreeUriPathsTest {
    private val base = "content://com.android.externalstorage.documents/tree/"

    @Test
    fun primary() {
        assertEquals("/storage/emulated/0/PSP", TreeUriPaths.toPath(base + "primary%3APSP"))
        assertEquals("/storage/emulated/0/PSP", TreeUriPaths.toPath(base + "primary:PSP"))
        assertEquals("/storage/emulated/0", TreeUriPaths.toPath(base + "primary%3A"))
        assertEquals(
            "/storage/emulated/0/Emu/ARMSX2",
            TreeUriPaths.toPath(base + "primary%3AEmu%2FARMSX2/document/primary%3AEmu%2FARMSX2"),
        )
        assertEquals("/storage/emulated/0/エミュ/a+b", TreeUriPaths.toPath(base + "primary%3A%E3%82%A8%E3%83%9F%E3%83%A5%2Fa+b"))
        assertEquals("/sdcard/PSP", TreeUriPaths.toPath(base + "primary%3APSP", primaryRoot = "/sdcard/"))
    }

    @Test
    fun sdCard() {
        assertEquals("/storage/1A2B-3C4D/Emu/ARMSX2", TreeUriPaths.toPath(base + "1A2B-3C4D%3AEmu%2FARMSX2"))
    }

    @Test
    fun unsupported() {
        assertNull(TreeUriPaths.toPath("content://com.android.providers.downloads.documents/tree/downloads"))
        assertNull(TreeUriPaths.toPath(base + "home%3ADocuments"))
        assertNull(TreeUriPaths.toPath(base + "nocolon"))
        assertNull(TreeUriPaths.toPath(base + "primary%3"))
        assertNull(TreeUriPaths.toPath("file:///storage/emulated/0"))
    }

    @Test
    fun androidData() {
        assertTrue(TreeUriPaths.isInsideAndroidData("/storage/emulated/0/Android/data/org.ppsspp.ppsspp/files"))
        assertTrue(TreeUriPaths.isInsideAndroidData("/storage/emulated/0/Android/data"))
        assertTrue(TreeUriPaths.isInsideAndroidData("/storage/emulated/0/android/obb/x"))
        assertFalse(TreeUriPaths.isInsideAndroidData("/storage/emulated/0/Android/media/x"))
        assertFalse(TreeUriPaths.isInsideAndroidData("/storage/emulated/0/PSP"))
    }
}
