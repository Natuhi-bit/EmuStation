package com.emustation.emusync.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.emustation.emusync.EmusyncApp
import com.emustation.emusync.domain.Presets
import java.util.concurrent.TimeUnit

/** 定期送信（設計書 §5.3）。1時間ごと、ネットワークにつながっているときだけ */
class PeriodicPushWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val container = (ctx as EmusyncApp).container
        if (!container.settingsStore.current().periodicPushEnabled) return Result.success()
        val report = container.periodicPushRunner.run()

        for ((target, n) in report.newConflicts) {
            Notifications.showPeriodicConflicts(ctx, target, machine(target), n)
        }
        report.error?.let { Notifications.showPeriodicError(ctx, it.resolve(ctx)) }
        for (s in report.reminders) {
            Notifications.showForgotten(ctx, s.target, machine(s.target))
        }
        // 失敗しても、次の回（1時間後）にまた試す。WorkManager の再試行は使わない
        return Result.success()
    }

    private fun machine(target: String) = Presets.byName(target)?.machine ?: target

    companion object {
        const val UNIQUE_NAME = "periodic-push"

        /** 有効なら登録（既にあれば設定だけ更新）、無効なら取り消す */
        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) {
                wm.cancelUniqueWork(UNIQUE_NAME)
                return
            }
            val req = PeriodicWorkRequestBuilder<PeriodicPushWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

    }
}
