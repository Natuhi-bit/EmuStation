package com.emustation.emusync.data.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

data class LauncherApp(val packageName: String, val label: String)

/** ランチャーに出ているアプリ（設計書 §8.3。マニフェストの <queries> が必要） */
class LauncherApps(context: Context) {
    private val pm: PackageManager = context.applicationContext.packageManager
    private val selfPackage = context.packageName

    fun list(): List<LauncherApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != selfPackage }
            .distinctBy { it.first }
            .map { LauncherApp(it.first, it.second) }
            .sortedBy { it.label.lowercase() }
    }

    /** インストールされていなければ null */
    fun label(packageName: String): String? = try {
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    fun icon(packageName: String): Drawable? = try {
        pm.getApplicationIcon(packageName)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    /** 起動用の Intent。アンインストールされていれば null */
    fun launchIntent(packageName: String): Intent? = pm.getLaunchIntentForPackage(packageName)
}
