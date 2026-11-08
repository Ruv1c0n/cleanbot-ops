package com.cleanbot.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.cleanbot.app.ui.navigation.CleanBotNavHost
import com.cleanbot.app.ui.theme.CleanBotTheme

class MainActivity : ComponentActivity() {

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Отказ — просто нет уведомлений, приложение продолжает работать.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()
        val initialRobotId = intent?.getStringExtra(EXTRA_ROBOT_ID)
        setContent {
            CleanBotTheme {
                CleanBotNavHost(initialRobotId = initialRobotId)
            }
        }
    }

    /** Рантайм-запрос POST_NOTIFICATIONS на Android 13+ до входа на дашборд. */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    companion object {
        /** Extra тапа по уведомлению об ошибке — id робота для навигации на его дашборд. */
        const val EXTRA_ROBOT_ID = "com.cleanbot.app.extra.ROBOT_ID"
    }
}
