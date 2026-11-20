package com.emustation.emusync.data.apps

import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.PowerManager
import android.os.Process

/**
 * いま前面にいるアプリを調べる（自動送信のため）。「使用状況へのアクセス」の許可が要る。
 *
 * UsageStatsManager のイベント（ACTIVITY_RESUMED / ACTIVITY_PAUSED）を少しずつ読み、
 * 最後に前面に来て、まだ離れていないアプリを「前面のアプリ」とする。
 */
class ForegroundTracker(context: Context, private val clock: () -> Long = System::currentTimeMillis) {
    private val app = context.applicationContext
    private val usm = app.getSystemService(UsageStatsManager::class.java)
    private val power = app.getSystemService(PowerManager::class.java)
    private val keyguard = app.getSystemService(KeyguardManager::class.java)

    private var readUntil = clock() - LOOKBACK_MS
    private var foreground: String? = null

    /** いま前面にいるアプリのパッケージ名（分からなければ null） */
    fun foregroundPackage(): String? {
        val now = clock()
        val events = usm.queryEvents(readUntil, now)
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> foreground = e.packageName
                UsageEvents.Event.ACTIVITY_PAUSED -> if (foreground == e.packageName) foreground = null
            }
        }
        readUntil = now
        return foreground
    }

    /** 画面が点いていて、ロックも解除されているか */
    fun screenOn(): Boolean = power.isInteractive && !keyguard.isKeyguardLocked

    companion object {
        /** 見張りを始めたときに、さかのぼって読む時間（その間に起動したエミュを拾う） */
        private const val LOOKBACK_MS = 60_000L

        /** 「使用状況へのアクセス」が許可されているか */
        fun hasUsageAccess(context: Context): Boolean {
            val ops = context.getSystemService(AppOpsManager::class.java)
            return ops.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName,
            ) == AppOpsManager.MODE_ALLOWED
        }
    }
}
