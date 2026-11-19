package com.emustation.emusync.ui.home

import com.emustation.emusync.data.LocalState
import com.emustation.emusync.data.api.LockInfo
import com.emustation.emusync.domain.play.PlayStep
import com.emustation.emusync.domain.sync.SyncResult
import com.emustation.emusync.ui.common.UiText

sealed interface ServerState {
    data object Checking : ServerState
    data object NotConfigured : ServerState
    data class Connected(val device: String) : ServerState

    /** [offline]：サーバーまで届かなかった（VPN を確認） */
    data class Failed(val text: UiText, val offline: Boolean) : ServerState
}

/** ターゲットの状態（設計書 §6.1）。1行に複数出ることがある */
sealed interface TargetStatus {
    data object Synced : TargetStatus
    data class Unsent(val count: Int) : TargetStatus
    data object NeverSynced : TargetStatus
    data class Conflicts(val count: Int) : TargetStatus
    data class PlayingElsewhere(val device: String) : TargetStatus
    data object PlayingHere : TargetStatus
    data object DataDirMissing : TargetStatus
    data object NoPermission : TargetStatus
    data class ScanFailed(val message: String) : TargetStatus
    data object Disabled : TargetStatus
}

data class TargetRow(
    val name: String,
    /** 機種名（プリセットなら PSP など、自由入力ならターゲット名） */
    val machine: String,
    val emuPackage: String?,
    /** エミュの名前。未設定なら null */
    val emuLabel: String?,
    /** エミュがアンインストールされている */
    val emuMissing: Boolean,
    val lastSyncAt: Long?,
    val statuses: List<TargetStatus>,
    val running: Boolean,
    /** この端末でプレイ中（プレイセッションがある） */
    val playingHere: Boolean = false,
)

/** 「遊ぶ」の途中で出すダイアログ（設計書 §5.1） */
sealed interface PlayDialog {
    val target: String
    val machine: String

    data class Progress(override val target: String, override val machine: String, val step: PlayStep) : PlayDialog

    /** 423。[やめる] / [奪って起動] */
    data class LockedByOther(
        override val target: String,
        override val machine: String,
        val device: String,
        val expiresAt: Double,
    ) : PlayDialog

    /** サーバーにつながらない。[同期せずに起動] / [やめる] */
    data class Unreachable(override val target: String, override val machine: String, val message: UiText) : PlayDialog

    /** 競合が出た。[無視して起動] / [やめる] */
    data class Conflicts(override val target: String, override val machine: String, val result: SyncResult) : PlayDialog

    data class Error(override val target: String, override val machine: String, val message: UiText) : PlayDialog
}

/** アプリに戻ってきたときの「送信しますか？」（設計書 §5.2） */
data class ResumePrompt(val target: String, val machine: String, val startedAt: Long, val online: Boolean)

sealed interface PreviewState {
    val machine: String

    data class Running(override val machine: String) : PreviewState
    data class Done(override val machine: String, val result: SyncResult) : PreviewState
    data class Failed(override val machine: String, val text: UiText) : PreviewState
}

data class HomeUiState(
    val loaded: Boolean = false,
    val server: ServerState = ServerState.Checking,
    val rows: List<TargetRow> = emptyList(),
    val hasAllFilesAccess: Boolean = true,
    val hasNotificationPermission: Boolean = true,
    val preview: PreviewState? = null,
    val play: PlayDialog? = null,
    val resumePrompt: ResumePrompt? = null,
)

/** 1つのターゲットの状態を組み立てる */
fun statusesFor(
    enabled: Boolean,
    lastSyncAt: Long?,
    conflicts: Int,
    local: LocalState?,
    lock: LockInfo?,
    selfDevice: String?,
    playingHere: Boolean,
): List<TargetStatus> = buildList {
    if (!enabled) add(TargetStatus.Disabled)
    if (lock != null && lock.device != selfDevice) add(TargetStatus.PlayingElsewhere(lock.device))
    if (playingHere || (lock != null && lock.device == selfDevice)) add(TargetStatus.PlayingHere)
    if (conflicts > 0) add(TargetStatus.Conflicts(conflicts))
    when (local) {
        LocalState.NoPermission -> add(TargetStatus.NoPermission)
        LocalState.DataDirMissing -> add(TargetStatus.DataDirMissing)
        is LocalState.Failed -> add(TargetStatus.ScanFailed(local.message))
        is LocalState.Scanned ->
            if (lastSyncAt == null) add(TargetStatus.NeverSynced)
            else if (local.unsent > 0) add(TargetStatus.Unsent(local.unsent))
        null -> if (lastSyncAt == null) add(TargetStatus.NeverSynced)
    }
    if (local is LocalState.Scanned && none { it !is TargetStatus.Disabled && it !is TargetStatus.PlayingElsewhere }) {
        add(TargetStatus.Synced)
    }
}
