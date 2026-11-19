package com.emustation.emusync.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.emustation.emusync.MainActivity
import com.emustation.emusync.R

object Notifications {
    const val CHANNEL_SYNC = "sync"
    const val CHANNEL_PLAY = "play"
    const val CHANNEL_RESULT = "result"
    const val ID_SYNC_PROGRESS = 1

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_SYNC, context.getString(R.string.channel_sync), NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CHANNEL_PLAY, context.getString(R.string.channel_play), NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CHANNEL_RESULT, context.getString(R.string.channel_result), NotificationManager.IMPORTANCE_DEFAULT),
            ),
        )
    }

    /** ターゲットごとの通知 ID（プレイ中の通知と、その結果の通知で同じものを使う） */
    fun playId(target: String) = 1000 + (target.hashCode() and 0x7fffffff) % 100_000

    /** 同期中の通知（Android 11 で expedited のジョブを動かすときに使う） */
    fun syncProgress(context: Context, title: String) =
        NotificationCompat.Builder(context, CHANNEL_SYNC)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** [送信して終了] / [再試行] のボタン */
    private fun finishAction(context: Context, target: String, label: Int): NotificationCompat.Action {
        val pi = PendingIntent.getBroadcast(
            context, playId(target),
            NotificationActionReceiver.finishIntent(context, target),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Action(0, context.getString(label), pi)
    }

    /** 「<機種> プレイ中」（設計書 §5.1 の 6） */
    fun showPlaying(context: Context, target: String, machine: String, online: Boolean) = notify(
        context, playId(target),
        NotificationCompat.Builder(context, CHANNEL_PLAY)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(context.getString(R.string.notif_playing, machine))
            .setContentText(
                context.getString(if (online) R.string.notif_playing_text else R.string.notif_playing_offline_text),
            )
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    context.getString(if (online) R.string.notif_playing_text else R.string.notif_playing_offline_text),
                ),
            )
            .setOngoing(true)
            .setContentIntent(openApp(context))
            .addAction(finishAction(context, target, R.string.action_finish_play))
            .build(),
    )

    /** 送信の結果。プレイ中の通知を置き換える */
    fun showFinished(context: Context, target: String, title: String, text: String) = notify(
        context, playId(target),
        NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build(),
    )

    /** 送信に失敗した。プレイセッションは残っているので [再試行] を出す */
    fun showFinishFailed(context: Context, target: String, title: String, text: String) = notify(
        context, playId(target),
        NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setContentIntent(openApp(context))
            .addAction(finishAction(context, target, R.string.action_retry))
            .build(),
    )

    fun cancel(context: Context, id: Int) = NotificationManagerCompat.from(context).cancel(id)

    private fun notify(context: Context, id: Int, n: android.app.Notification) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED && android.os.Build.VERSION.SDK_INT >= 33
        ) {
            return
        }
        NotificationManagerCompat.from(context).notify(id, n)
    }
}
