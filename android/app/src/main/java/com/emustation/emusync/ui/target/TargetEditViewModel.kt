package com.emustation.emusync.ui.target

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emustation.emusync.AppContainer
import com.emustation.emusync.R
import com.emustation.emusync.data.SyncOutcome
import com.emustation.emusync.data.apps.LauncherApp
import com.emustation.emusync.data.db.TargetEntity
import com.emustation.emusync.data.storage.TreeUriPaths
import com.emustation.emusync.data.toSyncTarget
import com.emustation.emusync.domain.Presets
import com.emustation.emusync.domain.sync.SyncMode
import com.emustation.emusync.domain.sync.SyncResult
import com.emustation.emusync.ui.common.UiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 保存前の dry-run の状態 */
sealed interface SaveCheck {
    data object Running : SaveCheck
    data class Done(val result: SyncResult) : SaveCheck
    data class Failed(val text: UiText) : SaveCheck
}

data class TargetForm(
    /** プリセット名。自由入力なら null */
    val preset: String? = Presets.all.first().name,
    val name: String = Presets.all.first().name,
    val emuPackage: String? = null,
    val emuLabel: String? = null,
    val dataDir: String = "",
    val relSubdir: String = Presets.all.first().relSubdir,
    val excludesText: String = "",
    val enabled: Boolean = true,
) {
    val excludes: List<String> get() = excludesText.lines().map { it.trim() }.filter { it.isNotEmpty() }

    fun toEntity(sortOrder: Int) = TargetEntity(
        name = name.trim(),
        emuPackage = emuPackage,
        dataDir = dataDir.trim().trimEnd('/'),
        relSubdir = relSubdir.trim().trim('/'),
        excludes = excludes,
        enabled = enabled,
        sortOrder = sortOrder,
    )

    /** 同期するフォルダの実パス */
    val syncDir: String get() = toEntity(0).toSyncTarget().root
}

/** 入力の誤り（保存できない）。null なら問題なし */
data class FormErrors(val name: UiText? = null, val dataDir: UiText? = null, val relSubdir: UiText? = null) {
    val any get() = name != null || dataDir != null || relSubdir != null
}

fun validate(form: TargetForm, isNew: Boolean, existingNames: Set<String>): FormErrors {
    val name = form.name.trim()
    val dir = form.dataDir.trim()
    return FormErrors(
        name = when {
            !isNew -> null
            name.isEmpty() -> UiText(R.string.err_target_name_empty)
            !Presets.isValidTargetName(name) -> UiText(R.string.err_target_name_chars)
            name in existingNames -> UiText(R.string.err_target_name_dup)
            else -> null
        },
        dataDir = when {
            dir.isEmpty() -> UiText(R.string.err_data_dir_empty)
            !dir.startsWith("/") -> UiText(R.string.err_data_dir_absolute)
            else -> null
        },
        relSubdir = if (form.relSubdir.split('/').any { it == ".." || it == "." }) {
            UiText(R.string.err_rel_subdir)
        } else {
            null
        },
    )
}

data class TargetEditState(
    val isNew: Boolean,
    val loaded: Boolean = false,
    val form: TargetForm = TargetForm(),
    val existingNames: Set<String> = emptySet(),
    val showErrors: Boolean = false,
    val apps: List<LauncherApp>? = null,
    val check: SaveCheck? = null,
    /** 保存・削除が終わった（前の画面に戻る） */
    val finished: Boolean = false,
) {
    val errors: FormErrors get() = validate(form, isNew, existingNames)
}

class TargetEditViewModel(
    private val app: Application,
    private val container: AppContainer,
    /** 編集するターゲット名。新規なら null */
    private val editing: String?,
) : ViewModel() {
    private val _state = MutableStateFlow(TargetEditState(isNew = editing == null))
    val state: StateFlow<TargetEditState> = _state.asStateFlow()

    private val _messages = Channel<UiText>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private var sortOrder = 0

    init {
        viewModelScope.launch {
            val all = container.db.targets().getAll()
            val existing = all.map { it.name }.toSet()
            val form = if (editing != null) {
                val t = all.firstOrNull { it.name == editing }
                if (t == null) {
                    _state.update { it.copy(finished = true) }
                    return@launch
                }
                sortOrder = t.sortOrder
                TargetForm(
                    preset = Presets.byName(t.name)?.name,
                    name = t.name,
                    emuPackage = t.emuPackage,
                    emuLabel = t.emuPackage?.let { withContext(Dispatchers.IO) { container.launcherApps.label(it) } },
                    dataDir = t.dataDir,
                    relSubdir = t.relSubdir,
                    excludesText = t.excludes.joinToString("\n"),
                    enabled = t.enabled,
                )
            } else {
                sortOrder = container.db.targets().nextSortOrder()
                // まだ追加していない最初のプリセットを選んでおく
                val p = Presets.all.firstOrNull { it.name !in existing }
                if (p != null) presetForm(p.name) else TargetForm(preset = null, name = "", relSubdir = "")
            }
            _state.update { it.copy(loaded = true, form = form, existingNames = existing - setOfNotNull(editing)) }
        }
    }

    private fun presetForm(name: String?): TargetForm {
        val cur = _state.value.form
        val p = name?.let(Presets::byName)
        return if (p == null) {
            cur.copy(preset = null, name = if (Presets.byName(cur.name) != null) "" else cur.name)
        } else {
            cur.copy(
                preset = p.name,
                name = p.name,
                relSubdir = p.relSubdir,
                excludesText = p.excludes.joinToString("\n"),
            )
        }
    }

    fun selectPreset(name: String?) = _state.update { it.copy(form = presetForm(name)) }

    fun update(f: (TargetForm) -> TargetForm) = _state.update { it.copy(form = f(it.form)) }

    /** フォルダ選択画面の結果 */
    fun onTreePicked(uri: String, primaryRoot: String) {
        val path = TreeUriPaths.toPath(uri, primaryRoot)
        if (path == null) {
            _messages.trySend(UiText(R.string.err_tree_uri))
        } else {
            update { it.copy(dataDir = path) }
        }
    }

    fun loadApps() {
        if (_state.value.apps != null) return
        viewModelScope.launch {
            val apps = withContext(Dispatchers.IO) { container.launcherApps.list() }
            _state.update { it.copy(apps = apps) }
        }
    }

    fun selectApp(a: LauncherApp?) = update { it.copy(emuPackage = a?.packageName, emuLabel = a?.label) }

    /** 保存の前に dry-run で、送信・取得・競合の件数を見せる（設計書 §6.3） */
    fun requestSave() {
        val s = _state.value
        if (s.errors.any) {
            _state.update { it.copy(showErrors = true) }
            return
        }
        _state.update { it.copy(check = SaveCheck.Running) }
        viewModelScope.launch {
            val o = container.syncRepository.run(s.form.toEntity(sortOrder), SyncMode.SYNC, dryRun = true, log = false)
            _state.update {
                it.copy(
                    check = when (o) {
                        is SyncOutcome.Done -> SaveCheck.Done(o.result)
                        is SyncOutcome.Failed -> SaveCheck.Failed(o.message)
                    },
                )
            }
        }
    }

    fun cancelSave() = _state.update { it.copy(check = null) }

    fun save() {
        val s = _state.value
        if (s.errors.any) return
        viewModelScope.launch {
            container.db.targets().upsert(s.form.toEntity(sortOrder))
            _state.update { it.copy(check = null, finished = true) }
        }
    }

    fun delete() {
        val name = editing ?: return
        viewModelScope.launch {
            container.syncRepository.deleteTarget(name)
            _state.update { it.copy(finished = true) }
        }
    }
}
