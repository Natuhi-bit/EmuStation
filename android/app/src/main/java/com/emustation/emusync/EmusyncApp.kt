package com.emustation.emusync

import android.app.Application
import com.emustation.emusync.work.Notifications

class EmusyncApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannels(this)
    }
}
