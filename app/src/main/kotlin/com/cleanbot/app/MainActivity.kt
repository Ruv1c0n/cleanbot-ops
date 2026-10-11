package com.cleanbot.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.cleanbot.app.ui.navigation.CleanBotNavHost
import com.cleanbot.app.ui.theme.CleanBotTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CleanBotTheme {
                CleanBotNavHost()
            }
        }
    }
}
