package com.example.vocabwatch.reminder

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

/**
 * 每日背单词提醒：精确到分钟的 RTC 闹钟，每天重复一次。
 *
 * 用 setRepeating（非 exact）就够了——提醒晚几分钟无所谓，还省掉
 * SCHEDULE_EXACT_ALARM 的权限拉扯。重启后闹钟会丢，BootReceiver 负责重排。
 */
object ReminderScheduler {

    const val CHANNEL_ID = "study_reminder"
    const val NOTIFICATION_ID = 1001

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "背单词提醒",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "到设定时间提醒你背单词"
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    /** minutesOfDay 传 null 表示关闭提醒。 */
    fun schedule(context: Context, minutesOfDay: Int?) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val operation = pendingIntent(context)
        alarm.cancel(operation)
        if (minutesOfDay == null) return

        val now = Calendar.getInstance()
        val trigger = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, minutesOfDay / 60)
            set(Calendar.MINUTE, minutesOfDay % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now.timeInMillis) add(Calendar.DATE, 1)
        }
        alarm.setRepeating(
            AlarmManager.RTC_WAKEUP,
            trigger.timeInMillis,
            AlarmManager.INTERVAL_DAY,
            operation
        )
    }

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, ReminderReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** 手表上是 Wear OS 5（API 34+），但逻辑按最低 API 30 写全。 */
    fun isRuntimePermissionNeeded(): Boolean = Build.VERSION.SDK_INT >= 33
}
