package com.emustation.emusync.work

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.emustation.emusync.EmusyncApp
import com.emustation.emusync.data.db.PlaySessionEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class PeriodicPushWorkerTest {
    private val app = ApplicationProvider.getApplicationContext<EmusyncApp>()

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
    }

    @Test
    fun schedule_hourlyWithNetwork_andCancel() {
        val wm = WorkManager.getInstance(app)
        PeriodicPushWorker.schedule(app, true)
        val info = wm.getWorkInfosForUniqueWork(PeriodicPushWorker.UNIQUE_NAME).get().single()
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        assertEquals(60 * 60 * 1000L, info.periodicityInfo!!.repeatIntervalMillis)
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)

        PeriodicPushWorker.schedule(app, false)
        assertEquals(
            WorkInfo.State.CANCELLED,
            wm.getWorkInfosForUniqueWork(PeriodicPushWorker.UNIQUE_NAME).get().single().state,
        )
    }

    @Test
    fun forgottenSession_postsReminderWithFinishButton() = runBlocking {
        app.container.db.playSessions().upsert(PlaySessionEntity("psp", 0L, online = true))
        val worker = TestListenableWorkerBuilder<PeriodicPushWorker>(app as Context).build()
        assertTrue(worker.doWork() is ListenableWorker.Result.Success)

        val nm = app.getSystemService(NotificationManager::class.java)
        val n = shadowOf(nm).getNotification(Notifications.playId("psp"))
        assertEquals("PSP のセーブを送信し忘れていませんか", n.extras.getString(NotificationCompat.EXTRA_TITLE))
        assertEquals("送信して終了", n.actions.single().title.toString())

        // 次の回は、もう知らせない
        nm.cancelAll()
        worker.doWork()
        assertEquals(null, shadowOf(nm).getNotification(Notifications.playId("psp")))
    }
}
