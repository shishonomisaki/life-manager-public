package com.shishonomisaki.lifemanager.reminder

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.shishonomisaki.lifemanager.LifeApp
import com.shishonomisaki.lifemanager.MainActivity
import com.shishonomisaki.lifemanager.R
import com.shishonomisaki.lifemanager.core.model.Defaults
import com.shishonomisaki.lifemanager.data.toTimestamp
import com.shishonomisaki.lifemanager.telegram.TelegramReminderWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** 鬧鐘到了顯示通知；通知上的「完成」「延後 10 分鐘」「延後到明天」也在這裡處理。 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        when (intent.action) {
            ACTION_REMIND -> {
                notify(context, taskId, title)
                // 已連結 Telegram 的話，Bot 對話裡也發一份提醒（筆電上也看得到）
                TelegramReminderWorker.enqueue(context, taskId, title)
            }
            ACTION_DONE, ACTION_SNOOZE, ACTION_TOMORROW -> {
                NotificationManagerCompat.from(context).cancel(taskId.hashCode())
                val pending = goAsync()
                scope.launch {
                    try {
                        withTimeoutOrNull(8_000) { handleAction(context, intent.action!!, taskId, title) }
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    private suspend fun handleAction(context: Context, action: String, taskId: String, title: String) {
        val app = context.applicationContext as LifeApp
        val repo = app.repository
        if (!repo.isSignedIn) return
        if (taskId == ReminderScheduler.TEST_TASK_ID) {
            if (action == ACTION_SNOOZE) app.scheduler.schedule(taskId, title, System.currentTimeMillis() + 10 * 60_000)
            return
        }
        when (action) {
            ACTION_DONE -> repo.getTask(taskId)?.let { repo.complete(it) }
            ACTION_SNOOZE -> snooze(app, taskId, title, Instant.now().plusSeconds(10 * 60))
            ACTION_TOMORROW -> {
                val at = ZonedDateTime.now(Defaults.ZONE).plusDays(1).with(LocalTime.of(9, 0)).toInstant()
                snooze(app, taskId, title, at)
            }
        }
    }

    private fun snooze(app: LifeApp, taskId: String, title: String, at: Instant) {
        app.scheduler.schedule(taskId, title, at.toEpochMilli())
        app.repository.updateTaskFields(taskId, mapOf("remindAt" to at.toTimestamp()))
    }

    private fun notify(context: Context, taskId: String, title: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        ReminderScheduler.createChannel(context)
        val open = PendingIntent.getActivity(
            context, taskId.hashCode(),
            Intent(context, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_TASK_ID, taskId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        fun action(a: String) = PendingIntent.getBroadcast(
            context, 0,
            Intent(context, ReminderReceiver::class.java)
                .setAction(a)
                .setData(Uri.parse("lifemanager://task/$taskId/$a"))
                .putExtra(EXTRA_TASK_ID, taskId)
                .putExtra(EXTRA_TITLE, title),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, ReminderScheduler.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_check)
            .setContentTitle(title.ifBlank { "待辦提醒" })
            .setContentText("到時間了")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(0, "完成", action(ACTION_DONE))
            .addAction(0, "延後 10 分鐘", action(ACTION_SNOOZE))
            .addAction(0, "延後到明天", action(ACTION_TOMORROW))
            .build()
        context.getSystemService(NotificationManager::class.java).notify(taskId.hashCode(), n)
    }

    companion object {
        const val ACTION_REMIND = "com.shishonomisaki.lifemanager.REMIND"
        const val ACTION_DONE = "com.shishonomisaki.lifemanager.DONE"
        const val ACTION_SNOOZE = "com.shishonomisaki.lifemanager.SNOOZE"
        const val ACTION_TOMORROW = "com.shishonomisaki.lifemanager.TOMORROW"
        const val EXTRA_TASK_ID = "taskId"
        const val EXTRA_TITLE = "title"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

/** 開機、更新 APP、鬧鐘權限變更後重排提醒。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        (context.applicationContext as LifeApp).scheduler.forgetScheduled()
        ReminderSyncWorker.runOnce(context)
    }
}

/** 背景定期抓一次待辦重排提醒（例如 APP 沒開時 Bot 新增的）。 */
class ReminderSyncWorker(context: Context, params: androidx.work.WorkerParameters) :
    androidx.work.CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as LifeApp
        if (!app.repository.isSignedIn) return Result.success()
        return runCatching {
            app.scheduler.sync(app.repository.openTasks())
            Result.success()
        }.getOrElse { Result.retry() }
    }

    companion object {
        fun runOnce(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "reminder-sync-now", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<ReminderSyncWorker>().build(),
            )
        }

        fun schedulePeriodic(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "reminder-sync", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ReminderSyncWorker>(15, TimeUnit.MINUTES).build(),
            )
        }
    }
}
