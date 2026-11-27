package com.emustation.emusync.data.apps

import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.PowerManager
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class ForegroundTrackerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val usm = shadowOf(context.getSystemService(UsageStatsManager::class.java))
    private var now = 1_000_000L
    private val tracker = ForegroundTracker(context) { now }

    private fun event(pkg: String, type: Int) {
        usm.addEvent(pkg, now, type)
        now += 1_000
    }

    @Test
    fun followsResumedAndPaused() {
        event("com.emustation.emusync", UsageEvents.Event.ACTIVITY_RESUMED)
        event("com.emustation.emusync", UsageEvents.Event.ACTIVITY_PAUSED)
        event("xyz.armsx2", UsageEvents.Event.ACTIVITY_RESUMED)
        assertEquals("xyz.armsx2", tracker.foregroundPackage())

        // エミュを閉じてホームへ
        event("xyz.armsx2", UsageEvents.Event.ACTIVITY_PAUSED)
        assertNull(tracker.foregroundPackage())
        event("com.android.launcher", UsageEvents.Event.ACTIVITY_RESUMED)
        assertEquals("com.android.launcher", tracker.foregroundPackage())

        // 前回読んだところから続けて読む
        event("com.android.launcher", UsageEvents.Event.ACTIVITY_PAUSED)
        event("xyz.armsx2", UsageEvents.Event.ACTIVITY_RESUMED)
        assertEquals("xyz.armsx2", tracker.foregroundPackage())
    }

    @Test
    fun screenOnRequiresInteractiveAndUnlocked() {
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
        assertTrue(tracker.screenOn())
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        assertFalse(tracker.screenOn())
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(false)
        assertFalse(tracker.screenOn())
    }

    @Test
    fun usageAccessPermission() {
        val ops = shadowOf(context.getSystemService(AppOpsManager::class.java))
        ops.setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName, AppOpsManager.MODE_IGNORED)
        assertFalse(ForegroundTracker.hasUsageAccess(context))
        ops.setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName, AppOpsManager.MODE_ALLOWED)
        assertTrue(ForegroundTracker.hasUsageAccess(context))
    }
}
