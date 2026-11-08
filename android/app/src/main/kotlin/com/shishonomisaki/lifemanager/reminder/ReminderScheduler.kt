package com.shishonomisaki.lifemanager.reminder

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.media.AudioAttributes
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.shishonomisaki.lifemanager.core.model.Task
import java.time.Instant

/** 用 AlarmManager 在手機上排提醒；Bot 新增的待辦同步下來後也會排好。 */
class ReminderScheduler(private val context: Context) {

    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val prefs = context.getSharedPreferences("reminders", Context.MODE_PRIVATE)

    fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()

    /** 依目前所有待辦重排：未完成且提醒時間在未來的排上，其他的取消。 */
    @Synchronized
    fun sync(tasks: List<Task>, now: Instant = Instant.now()) {
        val wanted = tasks.filter { it.status.isOpen && it.remindAt != null && it.remindAt!!.isAfter(now.minusSeconds(60)) }
        val previous = prefs.getStringSet(KEY_IDS, emptySet()).orEmpty()
        val previousAt = prefs.all.filterKeys { it.startsWith("at_") }
        val editor = prefs.edit()
        wanted.forEach { t ->
            val at = t.remindAt!!.toEpochMilli()
            val key = "at_${t.id}"
            val sig = "$at|${t.title}"
            if (previousAt[key] != sig) {
                schedule(t.id, t.title, at)
                editor.putString(key, sig)
            }
        }
        val wantedIds = wanted.map { it.id }.toSet()
        (previous - wantedIds).forEach { id ->
            cancel(id)
            editor.remove("at_$id")
        }
        editor.putStringSet(KEY_IDS, wantedIds).apply()
    }

    fun cancelAll() {
        prefs.getStringSet(KEY_IDS, emptySet()).orEmpty().forEach { cancel(it) }
        prefs.edit().clear().apply()
    }

    /** 重開機或權限變更後，系統的鬧鐘都沒了，全部重排。 */
    fun forgetScheduled() {
        prefs.edit().clear().apply()
    }

    fun schedule(taskId: String, title: String, atMillis: Long) {
        val pi = pendingIntent(taskId, title)
        if (canScheduleExact()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        }
    }

    fun cancel(taskId: String) {
        alarms.cancel(pendingIntent(taskId, ""))
    }

    private fun pendingIntent(taskId: String, title: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction(ReminderReceiver.ACTION_REMIND)
            .setData(Uri.parse("lifemanager://task/$taskId"))
            .putExtra(ReminderReceiver.EXTRA_TASK_ID, taskId)
            .putExtra(ReminderReceiver.EXTRA_TITLE, title)
        return PendingIntent.getBroadcast(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val KEY_IDS = "scheduled_ids"
        // 通道的聲音設定建好後就改不了，換設定要換新的 id
        const val CHANNEL_ID = "reminders_v2"
        private const val OLD_CHANNEL_ID = "reminders"
        const val TEST_TASK_ID = "__test__"

        fun createChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.deleteNotificationChannel(OLD_CHANNEL_ID)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "待辦提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "到時間提醒你的待辦"
                    setSound(
                        Settings.System.DEFAULT_NOTIFICATION_URI,
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 500, 250, 500)
                    enableLights(true)
                },
            )
        }

        /** 提醒通道沒被關成靜音（有些手機會把側載 APP 的通知預設成靜音）。 */
        fun channelAudible(context: Context): Boolean {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (!nm.areNotificationsEnabled()) return false
            createChannel(context)
            val ch = nm.getNotificationChannel(CHANNEL_ID) ?: return false
            return ch.importance >= NotificationManager.IMPORTANCE_DEFAULT && (ch.sound != null || ch.shouldVibrate())
        }

        fun openChannelSettings(context: Context) {
            context.startActivity(
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL_ID)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }

        /** 10 秒後跳一則測試提醒，用來確認會不會響。 */
        fun scheduleTest(context: Context) {
            (context.applicationContext as com.shishonomisaki.lifemanager.LifeApp).scheduler
                .schedule(TEST_TASK_ID, "測試提醒", System.currentTimeMillis() + 10_000)
        }
    }
}
