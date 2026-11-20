package com.emustation.emusync.work

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.work.WorkManager
import com.emustation.emusync.EmusyncApp
import com.emustation.emusync.data.apps.ForegroundTracker
import com.emustation.emusync.data.toSyncTarget
import com.emustation.emusync.domain.Presets
import com.emustation.emusync.domain.play.FolderSignature
import com.emustation.emusync.domain.play.PlayWatcher
import com.emustation.emusync.domain.play.WatchItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * プレイ中だけ動く前面サービス。エミュが終わったら自動で送信する（[PlayWatcher]）。
 * 通知は「<機種> プレイ中」を兼ねる。見張るものがなくなったら止まる。
 *
 * 「使用状況へのアクセス」が許可されていないときや、設定で自動送信を切っているときは使わない
 * （そのときは今までどおり、通知の [送信して終了] か、アプリに戻ったときの確認で送る）。
 */
class PlayWatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 起動したら、すぐに前面に出す（Android の決まり）
        ServiceCompat.startForeground(
            this, Notifications.ID_PLAY_WATCH, Notifications.playWatch(this, emptyList(), 0),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        if (loop?.isActive != true) loop = scope.launch { watch() }
        return START_NOT_STICKY
    }

    private suspend fun watch() {
        val container = (application as EmusyncApp).container
        val db = container.db
        val tracker = ForegroundTracker(this)
        val wm = WorkManager.getInstance(this)
        var minutes = container.settingsStore.current().autoFinishMinutes
        val watcher = PlayWatcher(
            awayMs = { minutes * 60_000L },
            sessions = { db.playSessions().observeAll().first() },
            packageOf = { db.targets().get(it)?.emuPackage },
            signatureOf = { t -> db.targets().get(t)?.let { signature(File(it.toSyncTarget().root)) } ?: FolderSignature.EMPTY },
            finishRunning = { t ->
                wm.getWorkInfosForUniqueWorkFlow(SyncWorker.uniqueName(t)).first().any { !it.state.isFinished }
            },
            startFinish = { t -> SyncWorker.enqueueFinish(this, t) },
            resume = { t -> container.playSessionManager.resumeAfterAutoFinish(t) },
        )
        while (scope.isActive) {
            val s = container.settingsStore.current()
            minutes = s.autoFinishMinutes
            if (!s.autoFinishEnabled || !ForegroundTracker.hasUsageAccess(this)) break
            val items = watcher.tick(tracker.foregroundPackage(), tracker.screenOn())
            for (r in items.filterIsInstance<WatchItem.Resumed>()) {
                Notifications.showResumed(this, r.target, machine(r.target), r.lockedBy)
            }
            if (watcher.isIdle) break
            Notifications.updatePlayWatch(this, Notifications.playWatch(this, items, minutes))
            delay(TICK_MS)
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TICK_MS = 5_000L

        fun machine(target: String) = Presets.byName(target)?.machine ?: target

        /** セーブのフォルダの状態。書き込みがあったかを見るためだけなので、ハッシュは取らない */
        fun signature(root: File): FolderSignature {
            if (!root.isDirectory) return FolderSignature.EMPTY
            var files = 0
            var bytes = 0L
            var last = 0L
            root.walkTopDown().filter { it.isFile }.forEach {
                files++
                bytes += it.length()
                last = maxOf(last, it.lastModified())
            }
            return FolderSignature(files, bytes, last)
        }

        /** 自動送信を使える状態なら、見張りを始める。使えなければ false（今までどおり通知だけ） */
        suspend fun startIfEnabled(context: Context): Boolean {
            val container = (context.applicationContext as EmusyncApp).container
            val s = container.settingsStore.current()
            if (!s.autoFinishEnabled || !ForegroundTracker.hasUsageAccess(context)) return false
            ContextCompat.startForegroundService(context, Intent(context, PlayWatchService::class.java))
            return true
        }
    }
}
