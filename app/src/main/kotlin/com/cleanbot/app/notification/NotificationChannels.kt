package com.cleanbot.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.cleanbot.app.R

/** Каналы локальных уведомлений об ошибках роботов; создаются при старте приложения. */
object NotificationChannels {
    const val ERRORS = "robot_errors"
    const val RECOVERY = "robot_recovery"

    fun ensure(context: Context) {
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                ERRORS,
                context.getString(R.string.notif_channel_errors),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.notif_channel_errors_desc)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                RECOVERY,
                context.getString(R.string.notif_channel_recovery),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.notif_channel_recovery_desc)
            }
        )
    }
}
