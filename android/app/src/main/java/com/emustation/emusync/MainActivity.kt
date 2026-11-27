package com.emustation.emusync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.emustation.emusync.ui.AppNavHost
import com.emustation.emusync.ui.theme.EmusyncTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as EmusyncApp
        setContent {
            EmusyncTheme {
                AppNavHost(app)
            }
        }
    }
}
