package com.shishonomisaki.lifemanager.telegram

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.shishonomisaki.lifemanager.MainActivity
import com.shishonomisaki.lifemanager.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 「持續連線」：常駐一個低調的通知，手機在背景時也一直長輪詢 Telegram，
 * 從筆電傳的訊息、在 Bot 按的按鈕幾秒內就處理（不用等 15 分鐘）。長輪詢大多時間在等，耗電很少。
 */
class InboxConnectionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val service = TelegramService.get(this)
        // startForegroundService 之後一定要先 startForeground，不然系統會讓 APP 當掉
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(this),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        val s = service.settings.current
        if (!s.alwaysOn || !s.hasToken) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (loop?.isActive != true) loop = scope.launch { service.runForeground() }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "inbox_connection"
        private const val NOTIFICATION_ID = 7001

        fun start(context: Context) {
            val s = TelegramService.get(context).settings.current
            if (!s.alwaysOn || !s.hasToken) return
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, InboxConnectionService::class.java))
            }.onFailure { Log.w("InboxConnection", "無法在背景啟動常駐連線", it) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, InboxConnectionService::class.java))
        }

        private fun notification(context: Context): Notification {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Telegram 收件匣連線", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "持續連線時常駐，可以在系統設定把這個通知隱藏"
                    setShowBadge(false)
                },
            )
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
            )
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_check)
                .setContentTitle("Telegram 收件匣連線中")
                .setContentText("從筆電傳給 Bot 的訊息會馬上處理")
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .setContentIntent(open)
                .build()
        }
    }
}

/** 開機或更新 APP 後，打開了「持續連線」就自動接上。 */
class InboxBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        InboxConnectionService.start(context)
    }
}
