package com.emustation.emusync.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.emustation.emusync.R

object Notifications {
    const val CHANNEL_SYNC = "sync"
    const val ID_SYNC_PROGRESS = 1

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SYNC,
                context.getString(R.string.channel_sync),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    /** 同期中の通知（Android 11 で expedited のジョブを動かすときに使う） */
    fun syncProgress(context: Context, target: String) =
        NotificationCompat.Builder(context, CHANNEL_SYNC)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(context.getString(R.string.notif_syncing, target))
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()
}
