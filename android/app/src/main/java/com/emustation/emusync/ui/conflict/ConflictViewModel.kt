package com.emustation.emusync.ui.conflict

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emustation.emusync.AppContainer
import com.emustation.emusync.R
import com.emustation.emusync.data.SyncOutcome
import com.emustation.emusync.data.db.ConflictEntity
import com.emustation.emusync.data.toSyncTarget
import com.emustation.emusync.domain.Presets
import com.emustation.emusync.domain.sync.Prefer
import com.emustation.emusync.domain.sync.RemoteFile
import com.emustation.emusync.domain.sync.SyncEngine
import com.emustation.emusync.domain.sync.SyncMode
import com.emustation.emusync.ui.common.UiText
import com.emustation.emusync.ui.common.connectionErrorText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

data class LocalFileInfo(val size: Long, val lastModified: Long)

/** 競合の1件（設計書 §6.4）。手元とサーバーを並べて見せる */
data class ConflictItem(
    val rel: String,
    val entity: ConflictEntity,
    /** 手元のファイル。消えていれば null */
    val local: LocalFileInfo?,
    /** サーバーの今の版。取得できなければ null */
    val server: RemoteFile?,
)

/** [手元を残す] / [サーバー版を使う] の確認 */
data class ResolveRequest(val rels: List<String>, val prefer: Prefer)

/** サーバーの今の状態（manifest） */
sealed interface ServerView {
    data object Loading : ServerView
    data class Loaded(val files: Map<String, RemoteFile>) : ServerView
    data class Failed(val text: UiText) : ServerView
}

data class ConflictUiState(
    val target: String,
    val machine: String,
    val loaded: Boolean = false,
    val items: List<ConflictItem> = emptyList(),
    val server: ServerView = ServerView.Loading,
    val playing: Boolean = false,
    val busy: Boolean = false,
    val confirm: ResolveRequest? = null,
)

class ConflictViewModel(
    private val app: Application,
    private val container: AppContainer,
    private val target: String,
) : ViewModel() {
    private val db = container.db
    private val machine = Presets.byName(target)?.machine ?: target
    private val server = MutableStateFlow<ServerView>(ServerView.Loading)
    private val busy = MutableStateFlow(false)
    private val confirm = MutableStateFlow<ResolveRequest?>(null)

    private val _messages = Channel<UiText>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private val items = combine(db.conflicts().observe(target), server) { rows, srv ->
        val t = db.targets().get(target)
        val root = t?.toSyncTarget()?.root
        val files = (srv as? ServerView.Loaded)?.files.orEmpty()
        withContext(Dispatchers.IO) {
            rows.map { c ->
                val f = root?.let { File(SyncEngine.joinPath(it, c.rel)) }
                ConflictItem(
                    c.rel, c,
                    f?.takeIf { it.isFile }?.let { LocalFileInfo(it.length(), it.lastModified()) },
                    files[c.rel],
                )
            }
        }
    }

    val state: StateFlow<ConflictUiState> = combine(
        items, server, busy, confirm, db.playSessions().observeAll().map { s -> s.any { it.target == target } },
    ) { it, srv, b, c, playing ->
        ConflictUiState(target, machine, loaded = true, items = it, server = srv, playing = playing, busy = b, confirm = c)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConflictUiState(target, machine))

    init {
        loadServer()
    }

    fun loadServer() {
        viewModelScope.launch {
            server.value = try {
                val api = withContext(Dispatchers.IO) { container.api(container.settingsStore.current()) }
                ServerView.Loaded(api.manifest("$target/"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ServerView.Failed(connectionErrorText(e))
            }
        }
    }

    fun requestResolve(rels: List<String>, prefer: Prefer) {
        confirm.value = ResolveRequest(rels, prefer)
    }

    fun cancelResolve() {
        confirm.value = null
    }

    /**
     * 解決する。手元を残すときは送信だけ（PUSH）、サーバー版を使うときは取得だけ（PULL）で、
     * そのファイルだけを prefer 付きで同期する。サーバー版を使うと、手元の版は backups/ に退避される
     */
    fun resolve() {
        val req = confirm.value ?: return
        confirm.value = null
        viewModelScope.launch {
            busy.value = true
            try {
                val t = db.targets().get(target) ?: return@launch
                val mode = if (req.prefer == Prefer.LOCAL) SyncMode.PUSH else SyncMode.PULL
                val o = container.syncRepository.run(t, mode, req.prefer, only = req.rels.toSet())
                _messages.send(
                    when (o) {
                        is SyncOutcome.Failed -> o.message
                        is SyncOutcome.Done -> when {
                            o.result.conflicts.isNotEmpty() -> UiText(R.string.msg_resolve_still_conflicts, o.result.conflicts.size)
                            o.result.errors.isNotEmpty() -> UiText(R.string.err_some_files_failed, o.result.errors.size)
                            req.prefer == Prefer.LOCAL -> UiText(R.string.msg_resolved_local, o.result.uploaded.size)
                            else -> UiText(R.string.msg_resolved_remote, o.result.downloaded.size)
                        }
                    },
                )
            } finally {
                busy.value = false
                loadServer()
            }
        }
    }
}
