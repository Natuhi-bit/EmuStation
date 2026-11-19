package com.emustation.emusync

import android.content.Context
import com.emustation.emusync.data.api.CaStore
import com.emustation.emusync.data.api.EmusyncApi
import com.emustation.emusync.data.api.HttpClients
import com.emustation.emusync.data.prefs.AppSettings
import com.emustation.emusync.data.prefs.KeystoreTokenCipher
import com.emustation.emusync.data.prefs.SettingsStore
import java.io.File

/** 手動の DI コンテナ（設計書 §9） */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val settingsStore = SettingsStore(appContext, KeystoreTokenCipher())
    val caStore = CaStore(File(appContext.filesDir, "ca"))

    private val baseHttpClient = HttpClients.base()

    /** 指定した設定で API クライアントを作る。URL かトークンが不正なら IllegalArgumentException */
    fun api(serverUrl: String, token: String, caFileName: String?): EmusyncApi {
        val certs = caFileName?.let { caStore.load(it) }
        return EmusyncApi(serverUrl, token, HttpClients.withCa(baseHttpClient, certs))
    }

    fun api(settings: AppSettings): EmusyncApi =
        api(settings.serverUrl, settings.token, settings.caFileName)
}
