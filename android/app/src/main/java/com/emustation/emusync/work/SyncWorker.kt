package com.emustation.emusync.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.emustation.emusync.EmusyncApp
import com.emustation.emusync.R
import com.emustation.emusync.data.SyncOutcome
import com.emustation.emusync.domain.sync.Prefer
import com.emustation.emusync.domain.sync.SyncMode

/** ターゲットごとの手動同期・送信（設計書 §8.4） */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val targetName get() = inputData.getString(KEY_TARGET).orEmpty()

    override suspend fun doWork(): Result {
        val container = (applicationContext as EmusyncApp).container
        val mode = SyncMode.valueOf(inputData.getString(KEY_MODE) ?: SyncMode.SYNC.name)
        val prefer = inputData.getString(KEY_PREFER)?.let(Prefer::valueOf)
        val target = container.db.targets().get(targetName)
            ?: return Result.failure(
                workDataOf(KEY_FATAL to applicationContext.getString(R.string.err_target_missing, targetName)),
            )
        return when (val o = container.syncRepository.run(target, mode, prefer)) {
            is SyncOutcome.Done -> Result.success(
                workDataOf(
                    KEY_UPLOADED to o.result.uploaded.size,
                    KEY_DOWNLOADED to o.result.downloaded.size,
                    KEY_CONFLICTS to o.result.conflicts.size,
                    KEY_ERRORS to o.result.errors.size,
                ),
            )
            is SyncOutcome.Failed -> Result.failure(
                workDataOf(KEY_FATAL to o.message.resolve(applicationContext)),
            )
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        ForegroundInfo(Notifications.ID_SYNC_PROGRESS, Notifications.syncProgress(applicationContext, targetName))

    companion object {
        const val TAG = "sync"
        private const val TARGET_TAG_PREFIX = "target:"
        const val KEY_TARGET = "target"
        const val KEY_MODE = "mode"
        const val KEY_PREFER = "prefer"
        const val KEY_UPLOADED = "uploaded"
        const val KEY_DOWNLOADED = "downloaded"
        const val KEY_CONFLICTS = "conflicts"
        const val KEY_ERRORS = "errors"
        const val KEY_FATAL = "fatal"

        fun uniqueName(target: String) = "sync-$target"

        /** ジョブのタグからターゲット名を取り出す */
        fun targetOf(info: WorkInfo): String? =
            info.tags.firstOrNull { it.startsWith(TARGET_TAG_PREFIX) }?.removePrefix(TARGET_TAG_PREFIX)

        /** 同じターゲットのジョブが既にあれば、何もしない（KEEP） */
        fun enqueue(context: Context, target: String, mode: SyncMode, prefer: Prefer? = null) {
            val data = Data.Builder()
                .putString(KEY_TARGET, target)
                .putString(KEY_MODE, mode.name)
                .apply { if (prefer != null) putString(KEY_PREFER, prefer.name) }
                .build()
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(data)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag(TAG)
                .addTag(TARGET_TAG_PREFIX + target)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(uniqueName(target), ExistingWorkPolicy.KEEP, req)
        }
    }
}
