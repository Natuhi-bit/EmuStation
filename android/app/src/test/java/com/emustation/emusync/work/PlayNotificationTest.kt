package com.emustation.emusync.work

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.emustation.emusync.EmusyncApp
import com.emustation.emusync.data.db.PlaySessionEntity
import com.emustation.emusync.data.db.TargetEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** 通知の [送信して終了] → 送信のジョブ → 結果の通知、のつながり（設計書 §5.2） */
@RunWith(AndroidJUnit4::class)
class PlayNotificationTest {
    private val app = ApplicationProvider.getApplicationContext<EmusyncApp>()
    private val nm get() = app.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
    }

    private fun posted(target: String) = shadowOf(nm).getNotification(Notifications.playId(target))

    @Test
    fun playingNotification_finishButton_enqueuesFinishJob() {
        Notifications.showPlaying(app, "psp", "PSP", online = true)
        val n = posted("psp")
        assertNotNull(n)
        assertEquals("PSP プレイ中", n.extras.getString(NotificationCompat.EXTRA_TITLE))
        assertTrue(n.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
        val action = n.actions.single()
        assertEquals("送信して終了", action.title.toString())

        // ボタンを押したときに飛ぶ Intent を、受け手に渡す
        val intent = shadowOf(action.actionIntent).savedIntent
        NotificationActionReceiver().onReceive(app, intent)

        val infos = WorkManager.getInstance(app).getWorkInfosForUniqueWork(SyncWorker.uniqueName("psp")).get()
        assertEquals(1, infos.size)
        // テスト用の WorkManager はすぐに実行するので、状態は問わない
        assertTrue("target:psp" in infos.single().tags)
    }

    @Test
    fun finishJob_failure_keepsSession_andShowsRetry() = runBlocking {
        // サーバー未設定のまま送信すると失敗する。プレイセッションは残し、[再試行] を出す
        val db = app.container.db
        db.targets().upsert(TargetEntity("ps2", "x", "/storage/emulated/0/ARMSX2", "memcards", emptyList()))
        db.playSessions().upsert(PlaySessionEntity("ps2", 1L, online = true))

        val worker = TestListenableWorkerBuilder<SyncWorker>(app as Context)
            .setInputData(workDataOf(SyncWorker.KEY_TARGET to "ps2", SyncWorker.KEY_MODE to "PUSH", SyncWorker.KEY_FINISH to true))
            .build()
        val result = worker.doWork()
        assertTrue(result is ListenableWorker.Result.Failure)
        assertTrue(result.outputData.getString(SyncWorker.KEY_FATAL)!!.contains("サーバーが設定されていません"))
        assertNotNull(db.playSessions().get("ps2"))

        val n = posted("ps2")
        assertEquals("PS2: 送信できませんでした", n.extras.getString(NotificationCompat.EXTRA_TITLE))
        assertEquals("再試行", n.actions.single().title.toString())
    }

    @Test
    fun finishJob_withoutSession_clearsNotification() = runBlocking {
        Notifications.showPlaying(app, "vita", "PS Vita", online = false)
        val worker = TestListenableWorkerBuilder<SyncWorker>(app as Context)
            .setInputData(workDataOf(SyncWorker.KEY_TARGET to "vita", SyncWorker.KEY_FINISH to true))
            .build()
        assertTrue(worker.doWork() is ListenableWorker.Result.Success)
        assertEquals(null, posted("vita"))
    }
}
