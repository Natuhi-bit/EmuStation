package com.emustation.emusync

import android.app.Application
import com.emustation.emusync.work.Notifications
import com.emustation.emusync.work.PeriodicPushWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class EmusyncApp : Application() {
    lateinit var container: AppContainer
        private set

    /** アプリが動いている間ずっと使うスコープ */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannels(this)
        // 定期送信を設定どおりに登録する（既にあれば設定だけ更新）
        appScope.launch {
            runCatching { PeriodicPushWorker.schedule(this@EmusyncApp, container.settingsStore.current().periodicPushEnabled) }
        }
    }
}
