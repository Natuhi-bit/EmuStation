package com.emustation.emusync.ui.common

import android.content.Context
import com.emustation.emusync.R
import com.emustation.emusync.domain.sync.SyncMode
import java.text.DateFormat
import java.util.Date

/** 「送信 2・取得 1・競合 1」のような要約。何もなければ「変更なし」 */
fun syncSummary(context: Context, uploaded: Int, downloaded: Int, conflicts: Int, errors: Int): String {
    val parts = buildList {
        if (uploaded > 0) add(context.getString(R.string.sum_uploaded, uploaded))
        if (downloaded > 0) add(context.getString(R.string.sum_downloaded, downloaded))
        if (conflicts > 0) add(context.getString(R.string.sum_conflicts, conflicts))
        if (errors > 0) add(context.getString(R.string.sum_errors, errors))
    }
    return if (parts.isEmpty()) context.getString(R.string.sum_nothing) else parts.joinToString("・")
}

fun modeLabel(context: Context, mode: String, dryRun: Boolean): String {
    if (dryRun) return context.getString(R.string.mode_dry_run)
    return context.getString(
        when (runCatching { SyncMode.valueOf(mode) }.getOrNull()) {
            SyncMode.PULL -> R.string.mode_pull
            SyncMode.PUSH -> R.string.mode_push
            else -> R.string.mode_sync
        },
    )
}

fun formatDateTime(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))

/** 12.3 KB のような表記 */
fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}
