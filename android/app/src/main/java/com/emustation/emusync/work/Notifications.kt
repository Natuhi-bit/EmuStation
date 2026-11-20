package com.emustation.emusync.work

import android.Manifest
import android.app.Notification
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
import com.emustation.emusync.domain.play.WatchItem

object Notifications {
    const val CHANNEL_SYNC = "sync"
    const val CHANNEL_PLAY = "play"
    const val CHANNEL_RESULT = "result"
    const val ID_SYNC_PROGRESS = 1
    const val ID_PLAY_WATCH = 4

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

    /**
     * 自動送信の見張り中の通知（前面サービスの通知。「プレイ中」を兼ねる）
     * @param minutes エミュが前面から消えてから送るまでの分数
     */
    fun playWatch(context: Context, items: List<WatchItem>, minutes: Int): Notification {
        val now = System.currentTimeMillis()
        val playing = items.filter {
            it is WatchItem.Playing || it is WatchItem.Resumed
        }
        val machine = { t: String -> PlayWatchService.machine(t) }
        val title = when {
            playing.isNotEmpty() -> context.getString(R.string.notif_playing, playing.joinToString("・") { machine(it.target) })
            else -> context.getString(R.string.notif_watch_title)
        }
        val lines = items.map {
            when (it) {
                is WatchItem.Playing ->
                    if (it.remainingMs == null) context.getString(R.string.notif_watch_playing, machine(it.target), minutes)
                    else context.getString(R.string.notif_watch_away, machine(it.target), formatMmSs(it.remainingMs))
                is WatchItem.Resumed ->
                    context.getString(R.string.notif_watch_playing, machine(it.target), minutes)
                is WatchItem.Finishing ->
                    context.getString(R.string.notif_watch_finishing, machine(it.target))
                is WatchItem.Grace ->
                    context.getString(R.string.notif_watch_grace, machine(it.target), ((it.untilMs - now) / 60_000 + 1).toInt())
            }
        }
        val text = lines.joinToString("\n").ifEmpty { context.getString(R.string.notif_watch_starting) }
        val b = NotificationCompat.Builder(context, CHANNEL_PLAY)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(context))
        playing.firstOrNull()?.let { b.addAction(finishAction(context, it.target, R.string.action_finish_play)) }
        return b.build()
    }

    fun updatePlayWatch(context: Context, n: Notification) = notify(context, ID_PLAY_WATCH, n)

    /** 自動で送ったあとでエミュに戻ってきたので、プレイ中に戻した */
    fun showResumed(context: Context, target: String, machine: String, lockedBy: String?) {
        if (lockedBy == null) {
            cancel(context, playId(target)) // 「送信しました」の通知は消す
            return
        }
        notify(
            context, playId(target),
            NotificationCompat.Builder(context, CHANNEL_RESULT)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(context.getString(R.string.notif_resumed_locked, machine, lockedBy))
                .setContentText(context.getString(R.string.notif_resumed_locked_text))
                .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.notif_resumed_locked_text)))
                .setAutoCancel(true)
                .setContentIntent(openApp(context))
                .build(),
        )
    }

    private fun formatMmSs(ms: Long): String {
        val s = (ms + 999) / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

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

    private const val ID_PERIODIC_ERROR = 3

    /** 定期送信で、新しい競合が見つかった */
    fun showPeriodicConflicts(context: Context, target: String, machine: String, count: Int) = notify(
        context, 200_000 + (target.hashCode() and 0x7fffffff) % 100_000,
        NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(context.getString(R.string.notif_periodic_conflicts, machine, count))
            .setContentText(context.getString(R.string.notif_periodic_conflicts_text))
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build(),
    )

    /** 定期送信が、つながらない以外の理由で失敗した（トークンが無効 など）。同じエラーは繰り返さない */
    fun showPeriodicError(context: Context, text: String) = notify(
        context, ID_PERIODIC_ERROR,
        NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(context.getString(R.string.notif_periodic_error))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build(),
    )

    /** 「送信を忘れていませんか」（設計書 §5.3）。プレイ中の通知を置き換える */
    fun showForgotten(context: Context, target: String, machine: String) = notify(
        context, playId(target),
        NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle(context.getString(R.string.notif_forgotten, machine))
            .setContentText(context.getString(R.string.notif_forgotten_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.notif_forgotten_text)))
            .setOngoing(true)
            .setContentIntent(openApp(context))
            .addAction(finishAction(context, target, R.string.action_finish_play))
            .build(),
    )

    fun cancel(context: Context, id: Int) = NotificationManagerCompat.from(context).cancel(id)

    private fun notify(context: Context, id: Int, n: Notification) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED && android.os.Build.VERSION.SDK_INT >= 33
        ) {
            return
        }
        NotificationManagerCompat.from(context).notify(id, n)
    }
}
