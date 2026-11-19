package com.emustation.emusync.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

data class AppSettings(
    /** 正規化済みのサーバー URL（末尾スラッシュなし）。未設定なら空 */
    val serverUrl: String = "",
    /** 復号したトークン。未設定・復号できないときは空 */
    val token: String = "",
    /** 取り込んだ CA 証明書のファイル名（アプリ内部）。なければ null */
    val caFileName: String? = null,
    /** 定期送信の有効・無効（段階 6 で使う） */
    val periodicPushEnabled: Boolean = true,
    /** トークンは保存されているが復号できなかった */
    val tokenUnreadable: Boolean = false,
    /** サーバーに登録されたこの端末の名前（最後に ping が成功したときの値） */
    val deviceName: String? = null,
)

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore("settings")

/** アプリ設定（設計書 §7）。トークンは Keystore の鍵で暗号化して保存する */
class SettingsStore(context: Context, private val cipher: TokenCipher) {
    private val store = context.applicationContext.settingsDataStore

    val settings: Flow<AppSettings> = store.data.map { p ->
        val enc = p[KEY_TOKEN_ENC]
        val token = enc?.let { cipher.decrypt(it) }
        AppSettings(
            serverUrl = p[KEY_SERVER_URL].orEmpty(),
            token = token.orEmpty(),
            caFileName = p[KEY_CA_FILE],
            periodicPushEnabled = p[KEY_PERIODIC] ?: true,
            tokenUnreadable = enc != null && token == null,
            deviceName = p[KEY_DEVICE_NAME],
        )
    }.flowOn(Dispatchers.Default)

    suspend fun current(): AppSettings = settings.first()

    suspend fun saveServer(serverUrl: String, token: String) {
        val enc = if (token.isEmpty()) null else cipher.encrypt(token)
        store.edit { p ->
            p[KEY_SERVER_URL] = serverUrl
            if (enc == null) p.remove(KEY_TOKEN_ENC) else p[KEY_TOKEN_ENC] = enc
        }
    }

    suspend fun setCaFileName(name: String?) {
        store.edit { p -> if (name == null) p.remove(KEY_CA_FILE) else p[KEY_CA_FILE] = name }
    }

    suspend fun setDeviceName(name: String) {
        store.edit { p -> p[KEY_DEVICE_NAME] = name }
    }

    suspend fun setPeriodicPushEnabled(enabled: Boolean) {
        store.edit { p -> p[KEY_PERIODIC] = enabled }
    }

    private companion object {
        val KEY_SERVER_URL = stringPreferencesKey("server_url")
        val KEY_TOKEN_ENC = stringPreferencesKey("token_enc")
        val KEY_CA_FILE = stringPreferencesKey("ca_file")
        val KEY_PERIODIC = booleanPreferencesKey("periodic_push")
        val KEY_DEVICE_NAME = stringPreferencesKey("device_name")
    }
}
