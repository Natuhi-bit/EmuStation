package com.emustation.emusync.testing

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import java.io.File

/** 画面の画像を app/build/screenshots/ に保存する（目で確かめる用） */
fun SemanticsNodeInteraction.saveScreenshot(name: String) {
    val dir = File(System.getProperty("user.dir"), "build/screenshots").apply { mkdirs() }
    File(dir, "$name.png").outputStream().use {
        captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
    }
}
