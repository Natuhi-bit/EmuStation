package com.emustation.emusync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.emustation.emusync.ui.settings.SettingsScreen
import com.emustation.emusync.ui.settings.SettingsViewModel
import com.emustation.emusync.ui.theme.EmusyncTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as EmusyncApp
        setContent {
            EmusyncTheme {
                // 段階 1：設定画面だけ。ホーム画面は段階 3 で追加する
                val vm: SettingsViewModel = viewModel(
                    factory = viewModelFactory { initializer { SettingsViewModel(app, app.container) } },
                )
                SettingsScreen(vm)
            }
        }
    }
}
