package com.emustation.emusync

import android.content.Context
import com.emustation.emusync.data.SyncRepository
import com.emustation.emusync.data.api.CaStore
import com.emustation.emusync.data.api.EmusyncApi
import com.emustation.emusync.data.api.HttpClients
import com.emustation.emusync.data.apps.LauncherApps
import com.emustation.emusync.data.db.AppDatabase
import com.emustation.emusync.data.prefs.AppSettings
import com.emustation.emusync.data.prefs.KeystoreTokenCipher
import com.emustation.emusync.data.prefs.SettingsStore
import com.emustation.emusync.domain.play.PlaySessionManager
import com.emustation.emusync.ui.settings.Permissions
import java.io.File

/** 手動の DI コンテナ（設計書 §9） */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val settingsStore = SettingsStore(appContext, KeystoreTokenCipher())
    val caStore = CaStore(File(appContext.filesDir, "ca"))
    val db: AppDatabase by lazy { AppDatabase.create(appContext) }
    val launcherApps = LauncherApps(appContext)

    private val baseHttpClient = HttpClients.base()

    /** 指定した設定で API クライアントを作る。URL かトークンが不正なら IllegalArgumentException */
    fun api(serverUrl: String, token: String, caFileName: String?): EmusyncApi {
        val certs = caFileName?.let { caStore.load(it) }
        return EmusyncApi(serverUrl, token, HttpClients.withCa(baseHttpClient, certs))
    }

    fun api(settings: AppSettings): EmusyncApi =
        api(settings.serverUrl, settings.token, settings.caFileName)

    /** アプリ専用外部領域（conflicts/ と backups/ を置く） */
    fun appDir(): String = (appContext.getExternalFilesDir(null) ?: appContext.filesDir).path

    val syncRepository: SyncRepository by lazy {
        SyncRepository(
            db = db,
            settings = { settingsStore.current() },
            apiFactory = { api(it) },
            appDir = ::appDir,
            hasStorageAccess = Permissions::hasAllFilesAccess,
            resolve = { it.resolve(appContext) },
        )
    }

    val playSessionManager: PlaySessionManager by lazy {
        PlaySessionManager(
            targets = db.targets(),
            sessions = db.playSessions(),
            sync = syncRepository,
            locks = {
                val s = settingsStore.current()
                // URL やトークンが不正なときも「未設定」と同じに扱う
                if (s.serverUrl.isBlank() || s.token.isBlank()) null else runCatching { api(s) }.getOrNull()
            },
        )
    }
}
