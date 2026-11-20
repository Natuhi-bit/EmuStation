package com.emustation.emusync.ui.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emustation.emusync.AppContainer
import com.emustation.emusync.R
import com.emustation.emusync.data.api.CaCertificates
import com.emustation.emusync.data.api.EmusyncApi
import com.emustation.emusync.ui.common.UiText
import com.emustation.emusync.ui.common.connectionErrorText
import com.emustation.emusync.ui.common.technicalDetail
import com.emustation.emusync.work.PeriodicPushWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.cert.X509Certificate
import kotlin.coroutines.cancellation.CancellationException

data class CaInfo(
    val subject: String,
    val issuer: String,
    val notBefore: Long,
    val notAfter: Long,
    val fingerprint: String,
    /** 同じファイルに入っていた2つ目以降の証明書の数 */
    val extraCount: Int,
)

sealed interface TestState {
    data object Idle : TestState
    data object Running : TestState
    class Success(val text: UiText) : TestState
    /** [detail]：原因を調べるための詳細（接続先と、例外の種類・メッセージ） */
    class Failure(val text: UiText, val detail: String? = null) : TestState
}

data class SettingsUiState(
    val loaded: Boolean = false,
    val serverUrl: String = "",
    val token: String = "",
    val savedServerUrl: String = "",
    val savedToken: String = "",
    val tokenUnreadable: Boolean = false,
    val caFileName: String? = null,
    val ca: CaInfo? = null,
    val test: TestState = TestState.Idle,
    val periodicPush: Boolean = true,
    val autoFinish: Boolean = true,
    val autoFinishMinutes: Int = 2,
) {
    val dirty: Boolean get() = serverUrl.trim() != savedServerUrl || token.trim() != savedToken
}

class SettingsViewModel(
    private val app: Application,
    private val container: AppContainer,
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private val _messages = Channel<UiText>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private var testJob: Job? = null

    init {
        viewModelScope.launch {
            val s = container.settingsStore.current()
            _state.update {
                it.copy(
                    loaded = true,
                    serverUrl = s.serverUrl,
                    token = s.token,
                    savedServerUrl = s.serverUrl,
                    savedToken = s.token,
                    tokenUnreadable = s.tokenUnreadable,
                    caFileName = s.caFileName,
                    periodicPush = s.periodicPushEnabled,
                    autoFinish = s.autoFinishEnabled,
                    autoFinishMinutes = s.autoFinishMinutes,
                    ca = s.caFileName?.let { name ->
                        withContext(Dispatchers.IO) { container.caStore.load(name) }?.toInfo()
                    },
                )
            }
        }
    }

    /** 定期送信（1時間ごと）の有効・無効（設計書 §5.3） */
    fun setPeriodicPush(enabled: Boolean) {
        _state.update { it.copy(periodicPush = enabled) }
        viewModelScope.launch {
            container.settingsStore.setPeriodicPushEnabled(enabled)
            PeriodicPushWorker.schedule(app, enabled)
        }
    }

    /** エミュを閉じたら自動で送信する（有効・無効と、待ち時間） */
    fun setAutoFinish(enabled: Boolean, minutes: Int) {
        _state.update { it.copy(autoFinish = enabled, autoFinishMinutes = minutes) }
        viewModelScope.launch { container.settingsStore.setAutoFinish(enabled, minutes) }
    }

    fun onServerUrlChange(v: String) = _state.update { it.copy(serverUrl = v, test = TestState.Idle) }

    fun onTokenChange(v: String) = _state.update { it.copy(token = v, test = TestState.Idle) }

    /** 入力を検証する。問題があれば文言を返す */
    private fun validate(url: String, token: String): UiText? = when {
        EmusyncApi.normalizeBaseUrl(url) == null -> UiText(R.string.err_url_invalid)
        token.isBlank() -> UiText(R.string.err_token_empty)
        !EmusyncApi.isValidToken(token) -> UiText(R.string.err_token_chars)
        else -> null
    }

    fun save() {
        val s = _state.value
        validate(s.serverUrl, s.token)?.let { _messages.trySend(it); return }
        val url = EmusyncApi.normalizeBaseUrl(s.serverUrl)!!
        val token = s.token.trim()
        viewModelScope.launch {
            container.settingsStore.saveServer(url, token)
            _state.update {
                it.copy(
                    serverUrl = url, token = token,
                    savedServerUrl = url, savedToken = token, tokenUnreadable = false,
                )
            }
            _messages.send(UiText(R.string.msg_saved))
        }
    }

    /** 入力中の URL とトークン、取り込み済みの CA で ping する */
    fun testConnection() {
        val s = _state.value
        validate(s.serverUrl, s.token)?.let {
            _state.update { st -> st.copy(test = TestState.Failure(it)) }
            return
        }
        testJob?.cancel()
        _state.update { it.copy(test = TestState.Running) }
        testJob = viewModelScope.launch {
            val result = try {
                val api = withContext(Dispatchers.IO) {
                    container.api(s.serverUrl, s.token, s.caFileName)
                }
                val pong = api.ping()
                TestState.Success(UiText(R.string.conn_ok, pong.device))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val url = EmusyncApi.normalizeBaseUrl(s.serverUrl)?.let { "$it/api/ping" }
                TestState.Failure(
                    connectionErrorText(e),
                    listOfNotNull(url?.let { app.getString(R.string.test_detail_url, it) }, technicalDetail(e)).joinToString("\n").ifEmpty { null },
                )
            }
            _state.update { it.copy(test = result) }
        }
    }

    fun importCa(uri: Uri) {
        viewModelScope.launch {
            try {
                val (name, certs) = withContext(Dispatchers.IO) {
                    val bytes = app.contentResolver.openInputStream(uri)?.use {
                        it.readNBytes(MAX_CA_BYTES + 1)
                    } ?: throw java.io.IOException("open failed")
                    if (bytes.size > MAX_CA_BYTES) throw IllegalArgumentException("too large")
                    container.caStore.import(bytes)
                }
                container.settingsStore.setCaFileName(name)
                _state.update {
                    it.copy(caFileName = name, ca = certs.toInfo(), test = TestState.Idle)
                }
                _messages.send(UiText(R.string.msg_ca_imported))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send(UiText(R.string.err_ca_invalid))
            }
        }
    }

    fun deleteCa() {
        val name = _state.value.caFileName ?: return
        viewModelScope.launch {
            container.settingsStore.setCaFileName(null)
            withContext(Dispatchers.IO) { container.caStore.delete(name) }
            _state.update { it.copy(caFileName = null, ca = null, test = TestState.Idle) }
            _messages.send(UiText(R.string.msg_ca_deleted))
        }
    }

    private fun List<X509Certificate>.toInfo(): CaInfo? {
        val c = firstOrNull() ?: return null
        return CaInfo(
            subject = c.subjectX500Principal.name,
            issuer = c.issuerX500Principal.name,
            notBefore = c.notBefore.time,
            notAfter = c.notAfter.time,
            fingerprint = CaCertificates.sha256Fingerprint(c),
            extraCount = size - 1,
        )
    }

    private companion object {
        const val MAX_CA_BYTES = 1 shl 20
    }
}
