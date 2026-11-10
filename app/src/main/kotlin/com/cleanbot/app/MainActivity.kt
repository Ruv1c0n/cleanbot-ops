package com.cleanbot.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cleanbot.app.ui.navigation.CleanBotNavHost
import com.cleanbot.app.ui.theme.CleanBotTheme

class MainActivity : ComponentActivity() {

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Отказ — просто нет уведомлений, приложение продолжает работать.
        }

    /** Id робота из тапа по уведомлению; меняется и в onNewIntent, когда activity уже жива. */
    private var pendingRobotId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge (рыночный стандарт Android 15+): контент рисуется под
        // системными барами, инсетты откусывают Scaffold-ы на экранах.
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        pendingRobotId = intent?.getStringExtra(EXTRA_ROBOT_ID)
        setContent {
            CleanBotTheme {
                CleanBotNavHost(initialRobotId = pendingRobotId)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_ROBOT_ID)?.let { pendingRobotId = it }
    }

    override fun onStop() {
        super.onStop()
        // Сброс: следующий тап по уведомлению (даже тот же робот) должен снова навигировать.
        pendingRobotId = null
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
