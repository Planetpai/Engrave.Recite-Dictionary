package com.example.vocabwatch.reminder

import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.example.vocabwatch.MainActivity
import com.example.vocabwatch.R
import com.example.vocabwatch.data.repository.REMINDER_MINUTES_KEY
import com.example.vocabwatch.data.repository.settingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

/** 到点发一条「该背单词了」通知，点进去直达应用。 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        ReminderScheduler.ensureChannel(context)
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(context, ReminderScheduler.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_reminder)
            .setContentTitle("该背单词了")
            .setContentText("今天的单词还在等你，点我开始")
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        context.getSystemService(android.app.NotificationManager::class.java)
            ?.notify(ReminderScheduler.NOTIFICATION_ID, notification)
    }
}

/** 重启后系统闹钟全部丢失，开机补排一次。 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val appContext = context.applicationContext
        val result = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            try {
                val prefs = appContext.settingsDataStore.data.firstOrNull()
                val minutes = prefs?.get(REMINDER_MINUTES_KEY)
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
