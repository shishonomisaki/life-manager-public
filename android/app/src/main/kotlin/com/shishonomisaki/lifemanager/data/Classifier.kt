package com.shishonomisaki.lifemanager.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.shishonomisaki.lifemanager.LifeApp
import com.shishonomisaki.lifemanager.core.model.AutoField
import com.shishonomisaki.lifemanager.core.model.Defaults
import com.shishonomisaki.lifemanager.core.model.Task
import com.shishonomisaki.lifemanager.core.parse.Classification
import com.shishonomisaki.lifemanager.core.parse.GeminiTaskParser
import com.shishonomisaki.lifemanager.core.parse.ParseContext
import com.shishonomisaki.lifemanager.core.parse.QuotaExceededException
import com.shishonomisaki.lifemanager.core.parse.RuleTaskParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * 自動選主題、專案、標籤、優先度。
 * 建立待辦時先用規則版立刻補上，再排一個需要網路的背景工作交給 Gemini（離線時連線後才跑）。
 */
class Classifier(private val context: Context, private val settings: SettingsStore) {

    val rules = RuleTaskParser()

    fun gemini(): GeminiTaskParser? {
        val s = settings.current
        if (!s.geminiEnabled) return null
        // 每送一次請求就記一筆，設定頁顯示今天用了幾次
        return GeminiTaskParser(s.geminiKey, s.geminiModel) { url, body ->
            settings.addNormalUse(LocalDate.now(SettingsStore.QUOTA_ZONE))
            post(url, body)
        }
    }

    /**
     * 把分類結果套到待辦上。只動系統自己選的欄位（在 [Task.autoTagged] 裡）或還空著的欄位，
     * 使用者改過的不碰。[firstTime] 表示剛建立、主題還是預設值。
     */
    fun apply(task: Task, c: Classification, firstTime: Boolean): Task {
        val auto = task.autoTagged.toMutableSet()
        fun can(field: String, empty: Boolean) = field in auto || empty
        var t = task
        c.topic?.let { if (can(AutoField.TOPIC, firstTime)) { t = t.copy(topic = it); auto += AutoField.TOPIC } }
        c.projectId?.let { if (can(AutoField.PROJECT, t.projectId == null)) { t = t.copy(projectId = it); auto += AutoField.PROJECT } }
        if (c.tagIds.isNotEmpty() && can(AutoField.TAGS, t.tags.isEmpty())) { t = t.copy(tags = c.tagIds); auto += AutoField.TAGS }
        c.priority?.let { if (it != 0 && can(AutoField.PRIORITY, t.priority == 0)) { t = t.copy(priority = it); auto += AutoField.PRIORITY } }
        if (c.suggestedProject != null && t.projectId == null) t = t.copy(suggestedProject = c.suggestedProject)
        return t.copy(autoTagged = auto)
    }

    fun enqueueAi(taskId: String) {
        if (!settings.current.geminiEnabled) return
        val req = OneTimeWorkRequestBuilder<ClassifyWorker>()
            .setInputData(workDataOf("taskId" to taskId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("classify-$taskId", ExistingWorkPolicy.REPLACE, req)
    }

    fun canUseAi(topic: String): Boolean {
        val s = settings.current
        return s.geminiEnabled && topic !in s.privateTopics &&
            !settings.quotaExhaustedToday(LocalDate.now(Defaults.ZONE))
    }

    companion object {
        suspend fun post(url: String, body: String): GeminiTaskParser.HttpResult = withContext(Dispatchers.IO) {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 15_000
                conn.readTimeout = 60_000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                GeminiTaskParser.HttpResult(code, stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty())
            } finally {
                conn.disconnect()
            }
        }
    }
}

class ClassifyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as LifeApp
        val repo = app.repository
        if (!repo.isSignedIn) return Result.success()
        val id = inputData.getString("taskId") ?: return Result.success()
        val task = repo.getTask(id) ?: return Result.success()
        val classifier = app.classifier
        if (!classifier.canUseAi(task.topic)) return Result.success()
        val gemini = classifier.gemini() ?: return Result.success()
        val ctx = ParseContext(
            now = ZonedDateTime.now(Defaults.ZONE),
            projects = repo.allProjects().filter { it.status.value == "active" },
            tags = repo.allTags(),
        )
        return try {
            val c = gemini.classify(task.title, task.notes, ctx)
            val updated = classifier.apply(task, c, firstTime = false)
            if (updated != task) repo.saveTask(updated)
            Result.success()
        } catch (e: QuotaExceededException) {
            app.settings.markQuotaExhausted(LocalDate.now(Defaults.ZONE))
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.success()
        }
    }
}
