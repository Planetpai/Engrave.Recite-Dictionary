package top.southfish.engrave.reminder

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.util.Calendar

/**
 * 每日背单词提醒：精确到分钟的 RTC 闹钟，每天一次。
 *
 * 可靠性是硬需求（应用被杀 / 待机也要准点响），所以用精确闹钟：
 * - Wear OS 4+（API 33+）声明 USE_EXACT_ALARM 即自动授予，无需引导用户；
 * - API 31-32 声明 SCHEDULE_EXACT_ALARM，默认授予；
 * - 万一系统收回了精确权限（canScheduleExactAlarms() = false），
 *   退化为 setWindow 10 分钟窗口，尽量贴近设定时间。
 *
 * setExactAndAllowWhileIdle 是一次性闹钟，没有重复版；由 ReminderReceiver
 * 发完通知后调用 [schedule] 自己排下一天。重启 / 应用更新 / 每次冷启动
 * 都会重排（BootReceiver + VocabApplication），三重兜底。
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
        if (canScheduleExact(alarm)) {
            alarm.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                trigger.timeInMillis,
                operation
            )
        } else {
            // 无精确权限的兜底：10 分钟窗口内送达
            alarm.setWindow(
                AlarmManager.RTC_WAKEUP,
                trigger.timeInMillis,
                10 * 60_000L,
                operation
            )
        }
    }

    private fun canScheduleExact(alarm: AlarmManager): Boolean =
        Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms()

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, ReminderReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /**
     * 申请电池优化白名单：防止系统「省电清理」把闹钟推迟或把应用杀掉。
     * 已经在白名单里就不弹。这会弹一个系统确认框，只引导一次。
     */
    fun maybeRequestIgnoreBatteryOptimizations(context: Context) {
        runCatching {
            val pm = context.getSystemService(PowerManager::class.java) ?: return
            if (pm.isIgnoringBatteryOptimizations(context.packageName)) return
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** 手表上是 Wear OS 5（API 34+），但逻辑按最低 API 30 写全。 */
    fun isRuntimePermissionNeeded(): Boolean = Build.VERSION.SDK_INT >= 33

    /**
     * 通知权限是否已授予。API 33 以下没有运行时通知权限，视为已授予。
     * 开启提醒前必须先查这个：已授予再调 requestPermission 在部分手表上
     * 会被系统直接回「拒绝」（不弹窗），误把用户带去系统设置页。
     */
    fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }
}
