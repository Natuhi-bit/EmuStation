package com.emustation.emusync.ui.history

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emustation.emusync.AppContainer
import com.emustation.emusync.R
import com.emustation.emusync.data.SyncOutcome
import com.emustation.emusync.data.api.HistoryVersion
import com.emustation.emusync.data.storage.JavaLocalFs
import com.emustation.emusync.data.toSyncTarget
import com.emustation.emusync.domain.Presets
import com.emustation.emusync.domain.sync.RemoteFile
import com.emustation.emusync.domain.sync.SyncEngine
import com.emustation.emusync.domain.sync.SyncMode
import com.emustation.emusync.ui.common.UiText
import com.emustation.emusync.ui.common.connectionErrorText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

sealed interface Loadable<out T> {
    data object Loading : Loadable<Nothing>
    data class Loaded<T>(val value: T) : Loadable<T>
    data class Failed(val text: UiText) : Loadable<Nothing>
}

// ---------------------------------------------------------------- ファイルの一覧

data class HistoryFilesState(
    val target: String,
    val machine: String,
    val files: Loadable<List<RemoteFile>> = Loadable.Loading,
    val query: String = "",
)

/** 履歴（設計書 §6.5）：ターゲットの中のファイルを選ぶ */
class HistoryFilesViewModel(private val container: AppContainer, private val target: String) : ViewModel() {
    private val _state = MutableStateFlow(HistoryFilesState(target, Presets.byName(target)?.machine ?: target))
    val state: StateFlow<HistoryFilesState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(files = Loadable.Loading) }
        viewModelScope.launch {
            val r = try {
                val api = withContext(Dispatchers.IO) { container.api(container.settingsStore.current()) }
                Loadable.Loaded(api.manifest("$target/").values.sortedWith(compareBy(SyncEngine.CODE_POINT_ORDER) { it.rel }))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Loadable.Failed(connectionErrorText(e))
            }
            _state.update { it.copy(files = r) }
        }
    }

    fun setQuery(q: String) = _state.update { it.copy(query = q) }
}

// ---------------------------------------------------------------- 1つのファイルの版

data class FileHistoryState(
    val target: String,
    val rel: String,
    val versions: Loadable<List<HistoryVersion>> = Loadable.Loading,
    /** サーバーの最新の版 */
    val current: Int? = null,
    /** 手元のファイルの SHA-256（なければ null） */
    val localSha: String? = null,
    val busy: Boolean = false,
    /** [この版に戻す] の確認 */
    val confirmRestore: HistoryVersion? = null,
    /** 復元したあとの「取得しますか？」。値は新しくできた版 */
    val confirmPull: Int? = null,
)

class FileHistoryViewModel(
    private val app: Application,
    private val container: AppContainer,
    private val target: String,
    private val rel: String,
) : ViewModel() {
    private val _state = MutableStateFlow(FileHistoryState(target, rel))
    val state: StateFlow<FileHistoryState> = _state.asStateFlow()

    private val _messages = Channel<UiText>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private val path = "$target/$rel"

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            val localSha = withContext(Dispatchers.IO) {
                container.db.targets().get(target)?.toSyncTarget()?.root
                    ?.let { File(SyncEngine.joinPath(it, rel)) }
                    ?.takeIf { it.isFile }
                    ?.let { runCatching { JavaLocalFs.sha256(it) }.getOrNull() }
            }
            val versions = try {
                val api = withContext(Dispatchers.IO) { container.api(container.settingsStore.current()) }
                Loadable.Loaded(api.history(path))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Loadable.Failed(connectionErrorText(e))
            }
            _state.update {
                it.copy(
                    versions = versions,
                    current = (versions as? Loadable.Loaded)?.value?.maxOfOrNull { v -> v.version },
                    localSha = localSha,
                )
            }
        }
    }

    fun requestRestore(v: HistoryVersion) = _state.update { it.copy(confirmRestore = v) }

    fun cancel() = _state.update { it.copy(confirmRestore = null, confirmPull = null) }

    /** 過去の版の内容を「新しい版」として最新にする。そのあと、取得するかを確かめる */
    fun restore() {
        val v = _state.value.confirmRestore ?: return
        _state.update { it.copy(confirmRestore = null, busy = true) }
        viewModelScope.launch {
            try {
                val api = withContext(Dispatchers.IO) { container.api(container.settingsStore.current()) }
                val newVersion = api.restore(path, v.version)
                _messages.send(UiText(R.string.msg_restored, v.version, newVersion))
                _state.update { it.copy(confirmPull = newVersion) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send(connectionErrorText(e))
            } finally {
                _state.update { it.copy(busy = false) }
                load()
            }
        }
    }

    /** 復元した版を取得して、手元に反映する（このファイルだけを PULL） */
    fun pull() {
        _state.update { it.copy(confirmPull = null, busy = true) }
        viewModelScope.launch {
            try {
                val t = container.db.targets().get(target) ?: return@launch
                val o = container.syncRepository.run(t, SyncMode.PULL, only = setOf(rel))
                _messages.send(
                    when (o) {
                        is SyncOutcome.Failed -> o.message
                        is SyncOutcome.Done -> when {
                            o.result.conflicts.isNotEmpty() -> UiText(R.string.msg_pull_conflict)
                            o.result.errors.isNotEmpty() -> UiText(R.string.err_some_files_failed, o.result.errors.size)
                            o.result.downloaded.isNotEmpty() -> UiText(R.string.msg_pulled)
                            else -> UiText(R.string.sum_nothing)
                        }
                    },
                )
            } finally {
                _state.update { it.copy(busy = false) }
                load()
            }
        }
    }
}
