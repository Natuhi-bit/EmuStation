package com.emustation.emusync.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 通知の [送信して終了] / [再試行]（設計書 §5.2）。送信のジョブを投入する */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FINISH) return
        val target = intent.getStringExtra(EXTRA_TARGET) ?: return
        SyncWorker.enqueueFinish(context, target)
    }

    companion object {
        const val ACTION_FINISH = "com.emustation.emusync.action.FINISH_PLAY"
        const val EXTRA_TARGET = "target"

        fun finishIntent(context: Context, target: String): Intent =
            Intent(context, NotificationActionReceiver::class.java)
                .setAction(ACTION_FINISH)
                .putExtra(EXTRA_TARGET, target)
    }
}
