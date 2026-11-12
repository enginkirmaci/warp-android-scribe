package com.engin.warpandroidscribe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.engin.warpandroidscribe.ui.ScribeTheme
import com.engin.warpandroidscribe.ui.SettingsScreen

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ScribeTheme {
                SettingsScreen()
            }
        }
    }
}
