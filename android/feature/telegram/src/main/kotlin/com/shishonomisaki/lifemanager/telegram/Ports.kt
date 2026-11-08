package com.shishonomisaki.lifemanager.telegram

import com.shishonomisaki.lifemanager.core.model.Task
import com.shishonomisaki.lifemanager.core.parse.GeminiTaskParser
import com.shishonomisaki.lifemanager.core.parse.ModelRouting
import com.shishonomisaki.lifemanager.core.parse.ParseContext
import com.shishonomisaki.lifemanager.core.parse.TaskParser
import java.time.ZonedDateTime

/** 收件匣要用到的資料存取（APP 端用 Firestore 實作，測試用假的）。 */
interface InboxStore {
    /** 專案、標籤、近期未完成待辦，給整理器判斷。 */
    suspend fun parseContext(now: ZonedDateTime): ParseContext
    fun newTaskId(): String
    fun createTask(task: Task)
    fun saveTask(task: Task)
    suspend fun getTask(id: String): Task?
    suspend fun children(parentId: String): List<Task>
    /** 刪除待辦和它的子任務。 */
    suspend fun deleteTask(id: String)
    /** 完成（重複的待辦會建立下一次）。 */
    suspend fun completeTask(id: String)
    suspend fun allTasks(): List<Task>

    fun newInboxId(): String
    fun saveInbox(entry: InboxEntry)
    suspend fun getInbox(id: String): InboxEntry?

    /** 等 Gemini 恢復再整理一次的 inbox（[InboxEntry.retryAi]）。 */
    suspend fun pendingInbox(): List<InboxEntry>

    /** 建立專案，回傳 id。 */
    fun createProject(name: String, topic: String): String

    /** 寫進 `users/{uid}.telegram`。 */
    fun linkChat(chatId: Long)
}

/** `users/{uid}/inbox/{inboxId}`，欄位見 docs/schema.md。 */
data class InboxEntry(
    val id: String,
    val kind: String,
    val text: String?,
    val forwardFrom: String?,
    val tgMessageId: Long,
    val tgChatId: Long,
    val status: String,
    val taskIds: List<String>,
    /** Bot 回覆整理結果的那則訊息，重新分類後回頭更新它。 */
    val replyMessageId: Long = 0,
    /** Telegram 附件「mime|fileId」，Gemini 恢復後重新下載。 */
    val fileRefs: List<String> = emptyList(),
    /** Gemini 忙線時先用規則（或語音先等著），恢復後要再整理一次。 */
    val retryAi: Boolean = false,
    /** 用了哪個模型：lite、strong:A1（升級和原因，見 ModelRouting）、rules；之後拿來調升級門檻。 */
    val model: String? = null,
    val retries: Int = 0,
) {
    companion object {
        /** 語音收到了但還沒聽懂，等 Gemini。 */
        const val PENDING = "pending"
        const val PARSED = "parsed"
        const val IGNORED = "ignored"
        const val FAILED = "failed"
    }
}

/** 只存在這支手機的 Bot 狀態。 */
interface BotState {
    /** 下一次 getUpdates 的 offset（已處理的最後一則 +1）；0 表示還沒拉過。 */
    var nextOffset: Long
    val linkedChatId: Long?

    /** 用 APP 產生的一次性代碼綁定這個對話。成功回傳 true。 */
    fun tryLink(code: String, chatId: Long): Boolean

    /** 最近一次 /today 列出的待辦，給 /done 2 用。 */
    var lastListed: List<String>

    /** 最近一次沒加條件的完整清單（/list）；編號超出最近那張清單時，問要不要照這張。 */
    var lastFullListed: List<String>

    /** 記下 Bot 的確認訊息是哪幾筆待辦，回覆那則就能接著改（只留最近幾十則）。 */
    fun rememberMessage(messageId: Long, taskIds: List<String>)

    fun tasksOfMessage(messageId: Long): List<String>
}

/** 整理器：Gemini 為主，規則版備援。 */
interface Brain {
    val rules: TaskParser

    /** 可用的 Gemini；沒設定金鑰或今天額度用完時回傳 null。 */
    fun gemini(): GeminiTaskParser?

    /** 這個主題設定成不送 Gemini。 */
    fun isPrivate(topic: String): Boolean

    fun onQuotaExceeded()

    /** 截圖離線辨識文字（ML Kit），失敗回傳 null。 */
    suspend fun ocr(image: ByteArray): String?

    /**
     * 升級用的強模型（Flash），沒設定、跟平常的一樣、或今天不能再用時回傳 null。
     * [auto]：自動升級（受每天 [ModelRouting.DAILY_STRONG_CAP] 次限制）；手動「重整」傳 false。
     */
    fun geminiStrong(auto: Boolean = true): GeminiTaskParser? = null

    /** 強模型用了一次（成功失敗都算，免費額度是算請求次數）。 */
    fun onStrongUsed() {}

    /** 強模型今天額度用完（429）。 */
    fun onStrongQuotaExceeded() {}

    /** 使用者設定的關鍵字和以前改過的分類。 */
    fun hints(): TopicHints = TopicHints()

    /** 使用者改了分類，記下來下次參考。 */
    fun learnTopic(title: String, topic: String) {}
}
