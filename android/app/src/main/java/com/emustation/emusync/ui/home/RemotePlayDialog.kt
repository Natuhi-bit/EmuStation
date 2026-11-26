package com.emustation.emusync.ui.home

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.emustation.emusync.R

/** リモートプレイ（PC の EmuStation を Moonlight で遊ぶ）で使うアプリ */
object RemotePlayApps {
    const val MOONLIGHT = "com.limelight"
    const val WIREGUARD = "com.wireguard.android"

    fun isInstalled(context: Context, pkg: String): Boolean =
        context.packageManager.getLaunchIntentForPackage(pkg) != null

    /** 入っていれば開く。なければ Google Play のページを開く */
    fun open(context: Context, pkg: String) {
        val launch = context.packageManager.getLaunchIntentForPackage(pkg)
        val intent = launch ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

/**
 * リモートプレイの案内：PC の EmuStation を Sunshine に登録 → Moonlight で「EmuStation」を選ぶ。
 * 家の外からは WireGuard で自宅のネットワークにつないで、PC の自宅での IP アドレスで Moonlight からつなぐ
 */
@Composable
fun RemotePlayDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val moonlight = remember { RemotePlayApps.isInstalled(context, RemotePlayApps.MOONLIGHT) }
    val wireguard = remember { RemotePlayApps.isInstalled(context, RemotePlayApps.WIREGUARD) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("remote-play-dialog"),
        title = { Text(stringResource(R.string.remote_play_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.remote_play_intro))
                Text(stringResource(R.string.remote_play_step_pc), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.remote_play_step_pair), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.remote_play_step_outside), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(
                    onClick = { RemotePlayApps.open(context, RemotePlayApps.WIREGUARD) },
                    modifier = Modifier.testTag("open-wireguard"),
                ) {
                    Text(stringResource(if (wireguard) R.string.remote_play_open_wireguard else R.string.remote_play_get_wireguard))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onDismiss(); RemotePlayApps.open(context, RemotePlayApps.MOONLIGHT) },
                modifier = Modifier.testTag("open-moonlight"),
            ) {
                Text(stringResource(if (moonlight) R.string.remote_play_open_moonlight else R.string.remote_play_get_moonlight))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}
