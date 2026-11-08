package com.cleanbot.app.notification

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.cleanbot.app.MainActivity
import com.cleanbot.app.R

/**
 * Публикация локальных уведомлений по решениям [RobotErrorDetector].
 * Контекст — application (из [com.cleanbot.app.di.AppContainer]), не activity.
 */
class RobotErrorNotifier(private val context: Context) {

    private val detector = RobotErrorDetector()

    /** Вызывается из RobotRepository.applyTelemetry на каждый элемент батча телеметрии. */
    fun onTelemetry(robotId: String, robotName: String, state: String, error: String?) {
        when (val alert = detector.onTelemetry(robotId, state, error, System.currentTimeMillis())) {
            is RobotAlert.Fault -> runCatching { notifyFault(robotId, robotName, alert.code) }
            is RobotAlert.Recovered -> runCatching { notifyRecovered(robotId, robotName) }
            null -> Unit
        }
    }

    private fun canNotify(): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED)

    private fun notifyFault(robotId: String, robotName: String, code: String?) {
        if (!canNotify()) return
        val explanation = explanation(code)
        val notification = NotificationCompat.Builder(context, NotificationChannels.ERRORS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_fault_title, robotName, code ?: UNKNOWN_CODE))
            .setContentText(explanation)
            .setStyle(NotificationCompat.BigTextStyle().bigText(explanation))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(contentIntent(robotId))
            .build()
        NotificationManagerCompat.from(context).notify(notificationId(robotId), notification)
    }

    private fun notifyRecovered(robotId: String, robotName: String) {
        if (!canNotify()) return
        val notification = NotificationCompat.Builder(context, NotificationChannels.RECOVERY)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(robotName)
            .setContentText(context.getString(R.string.notif_recovered, robotName))
            .setAutoCancel(true)
            .setContentIntent(contentIntent(robotId))
            .build()
        NotificationManagerCompat.from(context).notify(notificationId(robotId) + 1, notification)
    }

    /** Тап по уведомлению — дашборд этого робота: intent extra → навигация в NavHost. */
    private fun contentIntent(robotId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_ROBOT + robotId
            putExtra(MainActivity.EXTRA_ROBOT_ID, robotId)
        }
        return PendingIntent.getActivity(
            context,
            notificationId(robotId),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun explanation(code: String?): String = context.getString(
        when (code) {
            "STUCK" -> R.string.error_stuck
            "BRUSH_JAMMED" -> R.string.error_brush_jammed
            "TANK_FULL" -> R.string.error_tank_full
            "SENSOR_FAULT" -> R.string.error_sensor_fault
            "LOW_BATTERY" -> R.string.error_low_battery
            else -> R.string.error_unknown
        }
    )

    private fun notificationId(robotId: String): Int = robotId.hashCode()

    private companion object {
        const val ACTION_OPEN_ROBOT = "com.cleanbot.app.action.OPEN_ROBOT."
        const val UNKNOWN_CODE = "?"
    }
}
