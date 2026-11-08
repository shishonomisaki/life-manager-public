package com.shishonomisaki.lifemanager.telegram

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.shishonomisaki.lifemanager.LifeApp
import com.shishonomisaki.lifemanager.core.model.Defaults
import com.shishonomisaki.lifemanager.core.parse.GeminiTaskParser
import com.shishonomisaki.lifemanager.data.SettingsStore
import com.shishonomisaki.lifemanager.core.parse.ModelRouting
import com.shishonomisaki.lifemanager.core.parse.TaskParser
import com.shishonomisaki.lifemanager.data.Classifier
import com.shishonomisaki.lifemanager.reminder.ReminderSyncWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * 階段 0 的 Telegram 收件匣：不架伺服器，APP 自己拉訊息。
 * APP 開著時用長輪詢（幾乎即時），背景時由 [TelegramWorker] 每 15 分鐘拉一次。
 */
class TelegramService private constructor(private val app: LifeApp) {

    val settings = TelegramSettings(app)

    /** 同時只能有一個 getUpdates，不然 Telegram 會回 409。 */
    private val mutex = Mutex()

    fun api(token: String = settings.current.token) = TelegramApi(token, AndroidHttp)

    private fun bot(): InboxBot? {
        val s = settings.current
        if (!s.hasToken || !app.repository.isSignedIn) return null
        return InboxBot(api(s.token), FirestoreInboxStore(app), settings, AppBrain(app), log = { m, e -> Log.w(TAG, m, e) })
    }

    /** 檢查金鑰並設定 Bot（指令選單、清掉 webhook）。成功回傳 Bot 的 username。 */
    suspend fun connect(token: String): String {
        val api = api(token.trim())
        val me = api.getMe()
        runCatching { api.deleteWebhook() }
        runCatching { api.setMyCommands(Replies.COMMANDS) }
        settings.setBot(token.trim(), me.username.orEmpty())
        return me.username.orEmpty()
    }

    /** 手機提醒響了，發一份到 Telegram（附完成／延後按鈕）。 */
    suspend fun sendReminder(taskId: String, title: String) {
        val s = settings.current
        if (!s.remindInTelegram || !s.linked) return
        val bot = bot() ?: return
        bot.sendReminder(taskId, title)
    }

    /** 背景用：沒有新訊息為止（最多 5 輪）。前景正在長輪詢時直接略過。 */
    suspend fun drain(): Boolean {
        if (!mutex.tryLock()) return true
        try {
            val bot = bot() ?: return true
            for (round in 1..5) if (pollRecorded(bot, 0) <= 0) break
            retryPending(bot)
            return true
        } finally {
            mutex.unlock()
        }
    }

    /** 前景用：APP 開著時一直長輪詢，畫面離開就停。 */
    suspend fun runForeground() {
        var backoff = 5_000L
        while (currentCoroutineContext().isActive) {
            val bot = bot()
            if (bot == null) { delay(5_000); continue }
            if (!mutex.tryLock()) { delay(2_000); continue }
            val n = try {
                // 拉到的訊息一定處理完才停，避免重複建立
                withContext(NonCancellable) { pollRecorded(bot, 25) }
            } finally {
                mutex.unlock()
            }
            if (n < 0) { delay(backoff); backoff = (backoff * 2).coerceAtMost(60_000) } else {
                backoff = 5_000
                retryPending(bot)
            }
        }
    }

    @Volatile private var lastRetryAt = 0L

    /** Gemini 忙線時先放著的那幾則，大約每 3 分鐘再試一次。 */
    private suspend fun retryPending(bot: InboxBot) {
        val t = System.currentTimeMillis()
        if (t - lastRetryAt < 3 * 60_000) return
        lastRetryAt = t
        try {
            // 開始了就做完，避免建了待辦卻沒記下來、下次又建一次
            withContext(NonCancellable) { bot.retryPending() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "retryPending", e)
        }
    }

    /** 回傳處理了幾則；失敗回傳 -1 並記下原因給設定頁顯示。 */
    private suspend fun pollRecorded(bot: InboxBot, timeoutSec: Int): Int = try {
        bot.poll(timeoutSec).also { settings.recordPoll(null) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: TelegramException) {
        settings.recordPoll(if (e.badToken) "Bot 金鑰無效，請重新貼上" else e.description)
        -1
    } catch (e: Exception) {
        Log.w(TAG, "poll", e)
        settings.recordPoll("連不上 Telegram（${e.javaClass.simpleName}）")
        -1
    }

    companion object {
        private const val TAG = "Telegram"
        @Volatile private var instance: TelegramService? = null

        fun get(context: Context): TelegramService =
            instance ?: synchronized(this) {
                instance ?: TelegramService(context.applicationContext as LifeApp).also { instance = it }
            }
    }
}

/** 整理器：沿用 APP 的 Gemini 設定、私密主題和額度紀錄。 */
internal class AppBrain(private val app: LifeApp) : Brain {
    override val rules: TaskParser get() = app.classifier.rules

    override fun gemini(): GeminiTaskParser? =
        if (app.settings.quotaExhaustedToday(LocalDate.now(Defaults.ZONE))) null else app.classifier.gemini()

    override fun isPrivate(topic: String) = topic in app.settings.current.privateTopics

    override fun onQuotaExceeded() = app.settings.markQuotaExhausted(LocalDate.now(Defaults.ZONE))

    override fun geminiStrong(auto: Boolean): GeminiTaskParser? {
        val s = app.settings.current
        val model = s.geminiStrongModel.trim()
        if (!s.geminiEnabled || model.isEmpty() || model == s.geminiModel.trim()) return null
        val day = LocalDate.now(SettingsStore.QUOTA_ZONE)
        if (app.settings.strongExhausted(day)) return null
        if (auto && app.settings.strongUsed(day) >= ModelRouting.DAILY_STRONG_CAP) return null
        return GeminiTaskParser(s.geminiKey, model) { url, body -> Classifier.post(url, body) }
    }

    override fun onStrongUsed() = app.settings.addStrongUse(LocalDate.now(SettingsStore.QUOTA_ZONE))

    override fun onStrongQuotaExceeded() = app.settings.markStrongExhausted(LocalDate.now(SettingsStore.QUOTA_ZONE))

    override fun hints(): TopicHints = ClassifyHints.get(app).hints()

    override fun learnTopic(title: String, topic: String) = ClassifyHints.get(app).learn(title, topic)

    override suspend fun ocr(image: ByteArray): String? = withContext(Dispatchers.Default) {
        val bmp = BitmapFactory.decodeByteArray(image, 0, image.size) ?: return@withContext null
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        try {
            recognizer.process(InputImage.fromBitmap(bmp, 0)).await().text
        } catch (e: Exception) {
            null
        } finally {
            recognizer.close()
        }
    }
}

private object AndroidHttp : TelegramApi.TelegramHttp {
    override suspend fun post(url: String, json: String, readTimeoutMs: Int): Pair<Int, String> = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = readTimeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            code to stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        } finally {
            conn.disconnect()
        }
    }

    override suspend fun download(url: String): ByteArray = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }
}

/** 提醒發到 Telegram；沒網路時等連上再發。 */
class TelegramReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("taskId") ?: return Result.success()
        val title = inputData.getString("title").orEmpty()
        return try {
            TelegramService.get(applicationContext).sendReminder(id, title)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.success()
        }
    }

    companion object {
        fun enqueue(context: Context, taskId: String, title: String) {
            val s = TelegramService.get(context).settings.current
            if (!s.remindInTelegram || !s.linked) return
            WorkManager.getInstance(context).enqueueUniqueWork(
                "telegram-remind-$taskId", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<TelegramReminderWorker>()
                    .setInputData(androidx.work.workDataOf("taskId" to taskId, "title" to title))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build(),
            )
        }
    }
}

/** 背景每 15 分鐘收一次 Bot 訊息（ASUS 省電若擋背景會更久）。 */
class TelegramWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as LifeApp
        val service = TelegramService.get(app)
        if (!service.settings.current.hasToken || !app.repository.isSignedIn) return Result.success()
        service.drain()
        // 新建的待辦先排好提醒，再盡量等 Firestore 寫上雲端
        ReminderSyncWorker.runOnce(app)
        withTimeoutOrNull(20_000) { runCatching { app.firestore.waitForPendingWrites().await() } }
        return Result.success()
    }

    companion object {
        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedulePeriodic(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "telegram-poll", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<TelegramWorker>(15, TimeUnit.MINUTES).setConstraints(network).build(),
            )
        }

        fun runOnce(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "telegram-poll-now", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<TelegramWorker>()
                    .setConstraints(network)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build(),
            )
        }
    }
}
