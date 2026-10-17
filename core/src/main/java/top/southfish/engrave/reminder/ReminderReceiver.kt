package top.southfish.engrave.reminder

import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import top.southfish.engrave.core.R
import top.southfish.engrave.data.repository.REMINDER_MINUTES_KEY
import top.southfish.engrave.data.repository.settingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

/** 到点发一条「该背单词了」通知，点进去直达应用。 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        ReminderScheduler.ensureChannel(appContext)
        val contentIntent = PendingIntent.getActivity(
            appContext,
            0,
            // 用字符串组件名而不是 MainActivity::class.java：core 不能依赖 app 层
            // 的 Activity 类。约定各 app 的 MainActivity 都在其 namespace 根包
            // （= 运行时 packageName）下，这里按包名拼出全类名
            Intent().setClassName(appContext.packageName, appContext.packageName + ".MainActivity").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // 点通知直达学习页：MainActivity 检测此 extra 后交给导航层
                putExtra(ReminderIntents.EXTRA_AUTO_START_STUDY, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(appContext, ReminderScheduler.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_reminder)
            .setContentTitle("该背单词了")
            .setContentText("今天的单词还在等你，点我开始")
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        appContext.getSystemService(android.app.NotificationManager::class.java)
            ?.notify(ReminderScheduler.NOTIFICATION_ID, notification)

        // 精确闹钟是一次性的：发完这条，自己把明天的排上。
        // 闹钟在 Doze 里也能跑，但 DataStore 读是异步的，得 goAsync 保活。
        val result = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            try {
                val prefs = appContext.settingsDataStore.data.firstOrNull()
                ReminderScheduler.schedule(appContext, prefs?.get(REMINDER_MINUTES_KEY))
            } catch (_: Exception) {
                // 读不到设置就不重排；用户下次进设置页改时间会重新 schedule
            } finally {
                result.finish()
                scope.cancel()
            }
        }
    }
}

/**
 * 系统闹钟在这些时机全会丢，需要重排：
 * 开机完成、应用被更新（覆盖安装）、时间被改、时区切换。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val accepted = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED
        )
        if (intent.action !in accepted) return
        val appContext = context.applicationContext
        val result = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            try {
                val prefs = appContext.settingsDataStore.data.firstOrNull()
                val minutes = prefs?.get(REMINDER_MINUTES_KEY)
                ReminderScheduler.ensureChannel(appContext)
                ReminderScheduler.schedule(appContext, minutes)
            } catch (_: Exception) {
                // 设置读不到就不重排，下次进设置页改动时间会重新 schedule
            } finally {
                result.finish()
                scope.cancel()
            }
        }
    }
}
