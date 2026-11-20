package com.emustation.emusync.ui.settings

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

/** 権限の確認と、許可画面を開く処理（設計書 §8.1, §8.4） */
object Permissions {
    fun hasAllFilesAccess(): Boolean = Environment.isExternalStorageManager()

    fun hasNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** 「すべてのファイルへのアクセス」の許可画面を開く */
    fun openAllFilesAccessSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            "package:${context.packageName}".toUri(),
        )
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    /** 「使用状況へのアクセス」（自動送信で、エミュが前面にいるかを調べるため） */
    fun hasUsageAccess(context: Context): Boolean =
        com.emustation.emusync.data.apps.ForegroundTracker.hasUsageAccess(context)

    fun openUsageAccessSettings(context: Context) {
        val withPackage = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, "package:${context.packageName}".toUri())
        try {
            context.startActivity(withPackage)
        } catch (_: ActivityNotFoundException) {
            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
    }

    /** 通知の設定画面を開く（実行時の許可を拒否されたあと用） */
    fun openNotificationSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        )
    }
}
