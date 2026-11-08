package com.shishonomisaki.lifemanager.telegram

import com.shishonomisaki.lifemanager.core.model.AutoField
import com.shishonomisaki.lifemanager.core.model.Defaults
import com.shishonomisaki.lifemanager.core.model.Project
import com.shishonomisaki.lifemanager.core.model.SourceKind
import com.shishonomisaki.lifemanager.core.model.Task
import com.shishonomisaki.lifemanager.core.model.TaskStatus
import com.shishonomisaki.lifemanager.core.parse.GeminiTaskParser
import com.shishonomisaki.lifemanager.core.parse.ParseContext
import com.shishonomisaki.lifemanager.core.parse.QuotaExceededException
import com.shishonomisaki.lifemanager.core.parse.RuleTaskParser
import com.shishonomisaki.lifemanager.core.parse.TaskParser
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InboxBotTest {

    private val now = ZonedDateTime.of(2026, 10, 6, 14, 0, 0, 0, Defaults.ZONE) // 週二
    private val me = 777L

    // ---------- 假的 Telegram、資料、狀態 ----------

    private class Call(val method: String, val body: JsonObject)

    private inner class FakeHttp : TelegramApi.TelegramHttp {
        val calls = mutableListOf<Call>()
        var nextMessageId = 1000L
        var updates: List<String> = emptyList()

        fun sent(method: String = "sendMessage") = calls.filter { it.method == method }
        fun lastText(method: String = "sendMessage") = sent(method).last().body["text"]!!.jsonPrimitive.content

        override suspend fun post(url: String, json: String, readTimeoutMs: Int): Pair<Int, String> {
            val method = url.substringAfterLast('/')
            val body = Json.parseToJsonElement(json).jsonObject
            calls += Call(method, body)
            val result = when (method) {
                "getUpdates" -> updates.joinToString(",", "[", "]").also { updates = emptyList() }
                "sendMessage" -> """{"message_id":${nextMessageId++},"chat":{"id":${body["chat_id"]!!.jsonPrimitive.long},"type":"private"}}"""
                "getFile" -> """{"file_id":"x","file_path":"voice/file_1.oga"}"""
                else -> "true"
            }
            return 200 to """{"ok":true,"result":$result}"""
        }

        override suspend fun download(url: String) = byteArrayOf(1, 2, 3)
    }

    private inner class FakeStore : InboxStore {
        val tasks = linkedMapOf<String, Task>()
        val inbox = mutableMapOf<String, InboxEntry>()
        val projects = mutableListOf<Project>()
        var linked: Long? = null
        private var seq = 0

        override suspend fun parseContext(now: ZonedDateTime) =
            ParseContext(now = now, projects = projects.toList(), openTasks = tasks.values.filter { it.status.isOpen })
        override fun createProject(name: String, topic: String) = "p${++seq}".also { projects += Project(id = it, name = name, topic = topic) }
        override fun newTaskId() = "t${++seq}"
        override fun createTask(task: Task) { tasks[task.id] = task }
        override fun saveTask(task: Task) { tasks[task.id] = task }
        override suspend fun getTask(id: String) = tasks[id]
        override suspend fun children(parentId: String) = tasks.values.filter { it.parentId == parentId }
        override suspend fun deleteTask(id: String) {
            tasks.remove(id)
            tasks.values.filter { it.parentId == id }.map { it.id }.forEach { tasks.remove(it) }
        }
        override suspend fun completeTask(id: String) {
            tasks[id]?.let { tasks[id] = it.copy(status = TaskStatus.DONE, completedAt = now.toInstant()) }
        }
        override suspend fun allTasks() = tasks.values.toList()
        override fun newInboxId() = "in${++seq}"
        override fun saveInbox(entry: InboxEntry) { inbox[entry.id] = entry }
        override suspend fun getInbox(id: String) = inbox[id]
        override suspend fun pendingInbox() = inbox.values.filter { it.retryAi }
        override fun linkChat(chatId: Long) { linked = chatId }
    }

    private class FakeState(override var linkedChatId: Long?) : BotState {
        override var nextOffset = 0L
        override var lastListed: List<String> = emptyList()
        override var lastFullListed: List<String> = emptyList()
        val messages = mutableMapOf<Long, List<String>>()
        override fun rememberMessage(messageId: Long, taskIds: List<String>) { if (taskIds.isNotEmpty()) messages[messageId] = taskIds }
        override fun tasksOfMessage(messageId: Long) = messages[messageId].orEmpty()
        var code = "123456"
        override fun tryLink(code: String, chatId: Long): Boolean {
            if (code != this.code) return false
            linkedChatId = chatId
            return true
        }
    }

    private class FakeBrain(var geminiOut: String? = null, var quota: Boolean = false) : Brain {
        var hasStrong = false
        /** 強模型的回覆，沒設就跟平常的一樣。 */
        var strongOut: String? = null
        var strongCalls = 0
        val strongFailures = mutableListOf<Int>()
        /** 自動升級還剩幾次。 */
        var strongBudget = 18
        val strongAuto = mutableListOf<Boolean>()
        var hints = TopicHints()
        override fun hints() = hints
        override fun learnTopic(title: String, topic: String) { hints = hints.copy(learned = listOf(title to topic) + hints.learned) }
        override fun geminiStrong(auto: Boolean): GeminiTaskParser? {
            if (!hasStrong || (auto && strongBudget <= 0)) return null
            val out = strongOut ?: geminiOut ?: return null
            return GeminiTaskParser("key", "flash") { _, _ ->
                strongCalls++
                strongAuto += auto
                val status = strongFailures.removeFirstOrNull()
                if (status != null) GeminiTaskParser.HttpResult(status, "err")
                else GeminiTaskParser.HttpResult(200, """{"candidates":[{"content":{"parts":[{"text":${Json.encodeToString(String.serializer(), out)}}]}}]}""")
            }
        }
        override fun onStrongUsed() { strongBudget-- }
        override val rules: TaskParser = RuleTaskParser()
        var quotaMarked = false
        /** 接下來幾次 Gemini 呼叫要回的錯誤碼。 */
        val failures = mutableListOf<Int>()
        val geminiRequests = mutableListOf<String>()
        override fun gemini(): GeminiTaskParser? {
            val out = geminiOut ?: return null
            return GeminiTaskParser("key") { _, body ->
                geminiRequests += body
                val status = failures.removeFirstOrNull()
                if (status != null) GeminiTaskParser.HttpResult(status, "err")
                else if (quota) GeminiTaskParser.HttpResult(429, "")
                else GeminiTaskParser.HttpResult(200, """{"candidates":[{"content":{"parts":[{"text":${Json.encodeToString(String.serializer(), out)}}]}}]}""")
            }
        }
        override fun isPrivate(topic: String) = topic == "家庭" && privateFamily
        var privateFamily = false
        override fun onQuotaExceeded() { quotaMarked = true }
        override suspend fun ocr(image: ByteArray): String? = "10/9 下午3點 牙醫回診"
    }

    private val http = FakeHttp()
    private val store = FakeStore()
    private val state = FakeState(me)
    private val brain = FakeBrain()
    private val bot = InboxBot(TelegramApi("TOKEN", http), store, state, brain, now = { now }, geminiRetryDelayMs = 0)

    private var updateId = 1L
    private fun msg(json: String, chat: Long = me) =
        """{"update_id":${updateId++},"message":{"message_id":${updateId + 50},"chat":{"id":$chat,"type":"private"},"date":0,$json}}"""

    private fun run(vararg updates: String) = runBlocking {
        http.updates = updates.toList()
        bot.poll(0)
    }

    /** 最後一則（或最後一則開頭是 [startsWith] 的）訊息上的按鈕。 */
    private fun lastKeyboard(startsWith: String? = null): List<String> =
        http.sent().last { startsWith == null || it.body["text"]?.jsonPrimitive?.content?.startsWith(startsWith) == true }.body["reply_markup"]?.jsonObject?.get("inline_keyboard").toString()
            .let { Regex("\"callback_data\":\"([^\"]+)\"").findAll(it).map { m -> m.groupValues[1] }.toList() }

    // ---------- 測試 ----------

    @Test
    fun strangersAreIgnoredUntilLinked() {
        state.linkedChatId = null
        run(msg(""""text":"明天繳費""""))
        assertTrue(store.tasks.isEmpty())
        assertEquals(Replies.NOT_LINKED, http.lastText())

        run(msg(""""text":"/link 000000""""))
        assertEquals(Replies.LINK_FAILED, http.lastText())

        run(msg(""""text":"/start 123456""""))
        assertEquals(me, state.linkedChatId)
        assertEquals(me, store.linked)
        assertTrue(http.lastText().startsWith("已連結"))
    }

    @Test
    fun otherChatsCannotAddTasks() {
        run(msg(""""text":"明天繳費"""", chat = 999))
        assertTrue(store.tasks.isEmpty())
    }

    @Test
    fun textWithoutGeminiUsesRulesAndReplies() {
        run(msg(""""text":"明天10點繳電話費""""))
        val t = store.tasks.values.single()
        assertEquals("繳電話費", t.title)
        assertEquals(now.plusDays(1).withHour(10).toInstant(), t.dueAt)
        assertEquals(SourceKind.TELEGRAM, t.source.kind)
        assertEquals("家庭", t.topic)
        assertTrue("pay" in t.tags)
        val inbox = store.inbox.values.single()
        assertEquals(listOf(t.id), inbox.taskIds)
        assertEquals(t.source.inboxId, inbox.id)
        val text = http.lastText()
        assertTrue(text.startsWith("已建立 1 項"), text)
        assertTrue("明天 10:00" in text && "〔家庭〕" in text && "#付款" in text, text)
        assertEquals(listOf("d:${inbox.id}:0", "tp:${inbox.id}"), lastKeyboard())
        assertEquals(updateId, state.nextOffset)
    }

    @Test
    fun deleteButtonRemovesTask() {
        run(msg(""""text":"買牛奶\n明天打電話給王經理""""))
        assertEquals(2, store.tasks.size)
        val inbox = store.inbox.values.single()
        assertEquals(listOf("d:${inbox.id}:0", "d:${inbox.id}:1", "tp:${inbox.id}", "u:${inbox.id}"), lastKeyboard("已建立"))
        // 「買牛奶」規則看不出主題，另外跳一則問
        assertTrue(http.lastText().startsWith(Replies.TOPIC_UNSURE) && "買牛奶" in http.lastText(), http.lastText())
        assertTrue("王經理" !in http.lastText())
        val botMsg = http.sent().last()
        val cb = """{"update_id":${updateId++},"callback_query":{"id":"cb1","from":{"id":$me},"data":"d:${inbox.id}:0",
            "message":{"message_id":1000,"chat":{"id":$me,"type":"private"}}}}"""
        run(cb)
        assertEquals(1, store.tasks.size)
        assertTrue("（已刪除）" in http.lastText("editMessageText"))
        assertEquals("已刪除", http.sent("answerCallbackQuery").last().body["text"]!!.jsonPrimitive.content)
        assertNotNull(botMsg)

        run("""{"update_id":${updateId++},"callback_query":{"id":"cb2","from":{"id":$me},"data":"u:${inbox.id}",
            "message":{"message_id":1000,"chat":{"id":$me,"type":"private"}}}}""")
        assertTrue(store.tasks.isEmpty())
    }

    @Test
    fun geminiResultWithSubtasksAndProjectSuggestion() {
        brain.geminiOut = """{"tasks":[{"title":"搬家","topic":"家庭","confidence":0.9,"due":"2026-10-31T09:00",
            "subtasks":["找搬家公司","退租","改地址"],"tags":["要討論"]},
            {"title":"回覆王經理報價","topic":"工作","confidence":0.5,"tags":["等回覆"]}],"suggestedProject":"搬家"}"""
        run(msg(""""text":"搬家要找搬家公司、退租、改地址；還有回王經理報價""""))
        val parent = store.tasks.values.first { it.title == "搬家" }
        val subs = store.tasks.values.filter { it.parentId == parent.id }
        assertEquals(listOf("找搬家公司", "退租", "改地址"), subs.map { it.title })
        assertEquals("搬家", parent.suggestedProject)
        assertEquals(listOf("discuss"), parent.tags)
        val quote = store.tasks.values.first { it.title == "回覆王經理報價" }
        assertNull(quote.suggestedProject)
        assertTrue(quote.needsReview)
        // AI 沒把握的那筆另外問主題
        assertTrue(http.lastText().startsWith(Replies.TOPIC_UNSURE) && "回覆王經理報價" in http.lastText(), http.lastText())
        val text = http.sent().map { it.body["text"]!!.jsonPrimitive.content }.last { it.startsWith("已建立") }
        assertTrue("└ 找搬家公司、退租、改地址" in text, text)
        assertTrue("第 2 項不太確定" in text, text)
        assertTrue("建議開專案「搬家」" in text, text)
        assertEquals(2, store.inbox.values.single().taskIds.size)
    }

    @Test
    fun voiceIsSentToGeminiAsAudio() {
        brain.geminiOut = """{"tasks":[{"title":"週五交報告","topic":"工作","confidence":0.8}]}"""
        run(msg(""""voice":{"file_id":"v1","duration":3,"mime_type":"audio/ogg"}"""))
        val req = brain.geminiRequests.single()
        assertTrue("audio/ogg" in req && "AQID" in req, req) // base64 of 1,2,3
        assertEquals("voice", store.inbox.values.single().kind)
        assertEquals("週五交報告", store.tasks.values.single().title)
    }

    @Test
    fun quotaExceededFallsBackToRules() {
        brain.geminiOut = "{}"
        brain.quota = true
        run(msg(""""text":"明天買菜""""))
        assertTrue(brain.quotaMarked)
        assertEquals("買菜", store.tasks.values.single().title)
        assertTrue("額度用完" in http.lastText())
    }

    @Test
    fun privateTopicSkipsGemini() {
        brain.geminiOut = """{"tasks":[]}"""
        brain.privateFamily = true
        run(msg(""""text":"繳水費""""))
        assertTrue(brain.geminiRequests.isEmpty())
        assertEquals(1, store.tasks.size)
    }

    @Test
    fun voiceWithoutGeminiCreatesReviewItem() {
        run(msg(""""voice":{"file_id":"v1","duration":3}"""))
        val t = store.tasks.values.single()
        assertEquals("看 Telegram 的語音訊息", t.title)
        assertTrue(t.needsReview)
    }

    @Test
    fun screenshotWithoutGeminiUsesOcr() {
        run(msg(""""photo":[{"file_id":"s","width":90,"height":90},{"file_id":"b","width":900,"height":1600}]"""))
        val t = store.tasks.values.single()
        assertEquals("牙醫回診", t.title)
        assertEquals(ZonedDateTime.of(2026, 10, 9, 15, 0, 0, 0, Defaults.ZONE).toInstant(), t.dueAt)
        assertEquals("b", http.sent("getFile").single().body["file_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun forwardedMessageBecomesOneTaskWithSource() {
        run(msg(""""text":"週四下午兩點開會\n記得帶合約\n還有樣品","forward_origin":{"type":"user","date":0,"sender_user":{"id":5,"first_name":"王經理"}}"""))
        val t = store.tasks.values.single()
        assertEquals("開會", t.title)
        assertTrue(t.notes!!.startsWith("轉傳自：王經理"), t.notes)
        assertEquals("forward", store.inbox.values.single().kind)
        assertEquals("王經理", store.inbox.values.single().forwardFrom)
    }

    @Test
    fun replyToResultEditsTime() {
        run(msg(""""text":"買牛奶\n打電話給王經理""""))
        val inbox = store.inbox.values.single()
        val keyboard = """{"inline_keyboard":[[{"text":"x","callback_data":"d:${inbox.id}:0"}]]}"""
        val reply = """"text":"2 改成週五下午3點","reply_to_message":{"message_id":1000,"chat":{"id":$me,"type":"private"},
            "from":{"id":1,"is_bot":true,"first_name":"bot"},"reply_markup":$keyboard}"""
        run(msg(reply))
        val call = store.tasks.values.first { it.title.contains("王經理") }
        assertEquals(ZonedDateTime.of(2026, 10, 9, 15, 0, 0, 0, Defaults.ZONE).toInstant(), call.dueAt)
        assertNull(store.tasks.values.first { it.title == "買牛奶" }.dueAt)
        assertTrue("週五" in http.lastText("editMessageText") || "10/9" in http.lastText("editMessageText"))

        run(msg(""""text":"1 家庭","reply_to_message":{"message_id":1000,"chat":{"id":$me,"type":"private"},"reply_markup":$keyboard}"""))
        assertEquals("家庭", store.tasks.values.first { it.title == "買牛奶" }.topic)

        run(msg(""""text":"刪除","reply_to_message":{"message_id":1000,"chat":{"id":$me,"type":"private"},"reply_markup":$keyboard}"""))
        assertEquals(2, store.tasks.size) // 兩項時沒說哪一項不動
        assertTrue("要改哪一項" in http.lastText())
    }

    @Test
    fun todayAndDone() {
        run(msg(""""text":"今天晚上8點倒垃圾""""))
        run(msg(""""text":"/today""""))
        assertTrue("倒垃圾" in http.lastText())
        val id = store.tasks.keys.single()
        assertEquals(listOf("c:$id"), lastKeyboard())
        run(msg(""""text":"/done 1""""))
        assertEquals(TaskStatus.DONE, store.tasks[id]!!.status)
        run(msg(""""text":"/week""""))
        assertTrue("完成 1 項" in http.lastText(), http.lastText())
    }

    @Test
    fun chineseShortcutsAndListFilters() {
        fun task(id: String, title: String, topic: String, dueDay: Int?) = Task(
            id = id, title = title, topic = topic,
            dueAt = dueDay?.let { ZonedDateTime.of(2026, 10, it, 18, 0, 0, 0, Defaults.ZONE).toInstant() },
        )
        listOf(
            task("a", "交報告", "工作", 6), task("b", "倒垃圾", "家庭", 6),
            task("c", "回王經理", "工作", 9), task("d", "想旅行", "個人", null),
        ).forEach { store.tasks[it.id] = it }

        run(msg(""""text":"列清單""""))
        val all = http.lastText()
        assertTrue(all.startsWith("目前待辦（4 項）") && "想旅行" in all, all)
        assertEquals(4, store.tasks.size) // 沒有被建成待辦

        run(msg(""""text":"/list /today 工作""""))
        val todayWork = http.lastText()
        assertTrue(todayWork.startsWith("待辦｜今天・工作（1 項）") && "交報告" in todayWork, todayWork)

        run(msg(""""text":"列清單 王經理""""))
        assertTrue("回王經理" in http.lastText() && "交報告" !in http.lastText(), http.lastText())

        run(msg(""""text":"/today 家庭""""))
        assertTrue("倒垃圾" in http.lastText() && "交報告" !in http.lastText(), http.lastText())

        run(msg(""""text":"完成 1""""))
        assertEquals(TaskStatus.DONE, store.tasks["b"]!!.status)

        // 按 ✓ 之後標題保持原本的條件
        run(msg(""""text":"/list 工作""""))
        run("""{"update_id":${updateId++},"callback_query":{"id":"cb","from":{"id":$me},"data":"c:a",
            "message":{"message_id":1000,"chat":{"id":$me,"type":"private"},"text":"待辦｜工作（2 項）\n1. 交報告"}}}""")
        assertTrue(http.lastText("editMessageText").startsWith("待辦｜工作"), http.lastText("editMessageText"))

        // 長句子照樣整理成待辦
        run(msg(""""text":"明天列清單給老闆看""""))
        assertEquals(5, store.tasks.size)
    }

    // ---------- 分類 ----------

    private fun replyTo(botMessageId: Long, keyboard: String, words: String) =
        msg(""""text":"$words","reply_to_message":{"message_id":$botMessageId,"chat":{"id":$me,"type":"private"},
            "from":{"id":1,"is_bot":true,"first_name":"bot"},"reply_markup":$keyboard}""")

    private fun createdKeyboardJson(inboxId: String) =
        """{"inline_keyboard":[[{"text":"x","callback_data":"d:$inboxId:0"}],[{"text":"y","callback_data":"tp:$inboxId"}]]}"""

    @Test
    fun explicitTopicAndTagsWin() {
        run(msg(""""text":"工作，明天下午交報告給爸爸""""))
        val t = store.tasks.values.single()
        assertEquals("工作", t.topic)
        assertTrue(AutoField.TOPIC !in t.autoTagged)
        assertTrue("工作" !in t.title, t.title)

        store.tasks.clear()
        run(msg(""""text":"週六帶小孩打疫苗 #個人 #付款""""))
        val u = store.tasks.values.single()
        assertEquals("個人", u.topic)
        assertTrue("pay" in u.tags)
        assertTrue("#" !in u.title, u.title)
    }

    @Test
    fun sentenceTopicEditIsLearned() {
        run(msg(""""text":"提醒僑商處要提供商店的店家城市清單""""))
        val t = store.tasks.values.single()
        assertEquals("個人", t.topic)
        assertTrue(http.lastText().startsWith(Replies.TOPIC_UNSURE), http.lastText())
        val inbox = store.inbox.values.single()

        run(replyTo(1000, createdKeyboardJson(inbox.id), "這是工作的事"))
        assertEquals("工作", store.tasks[t.id]!!.topic)
        assertEquals(listOf(t.title to "工作"), brain.hints.learned)

        // 下次提到僑商處就知道是工作
        run(msg(""""text":"跟僑商處拿樣品""""))
        assertEquals("工作", store.tasks.values.last().topic)
    }

    @Test
    fun replyPutsIntoProject() {
        run(msg(""""text":"整理 ICARD 規格""""))
        val t = store.tasks.values.single()
        val inbox = store.inbox.values.single()

        // 還沒有的專案：建立再放進去，主題跟著待辦
        run(replyTo(1000, createdKeyboardJson(inbox.id), "這則放到新專案 ICARD"))
        val p = store.projects.single()
        assertEquals("ICARD", p.name)
        assertEquals(t.topic, p.topic)
        assertEquals(p.id, store.tasks[t.id]!!.projectId)
        assertTrue("專案「ICARD」" in http.lastText(), http.lastText())

        // 已經有的專案：@名稱，不重複建立
        run(replyTo(1000, createdKeyboardJson(inbox.id), "移出專案"))
        assertNull(store.tasks[t.id]!!.projectId)
        run(replyTo(1000, createdKeyboardJson(inbox.id), "@icard"))
        assertEquals(1, store.projects.size)
        assertEquals(p.id, store.tasks[t.id]!!.projectId)
    }

    @Test
    fun replyWithNewAtProjectAndOpenSuggested() {
        run(msg(""""text":"買牛奶""""))
        val t = store.tasks.values.single()
        val inbox = store.inbox.values.single()
        run(replyTo(1000, createdKeyboardJson(inbox.id), "@搬家"))
        assertEquals("搬家", store.projects.single().name)
        assertEquals(store.projects.single().id, store.tasks[t.id]!!.projectId)

        store.saveTask(store.tasks[t.id]!!.copy(projectId = null, suggestedProject = "家裡雜事"))
        run(replyTo(1000, createdKeyboardJson(inbox.id), "開專案"))
        val p = store.projects.first { it.name == "家裡雜事" }
        assertEquals(p.id, store.tasks[t.id]!!.projectId)
        assertNull(store.tasks[t.id]!!.suggestedProject)
    }

    @Test
    fun newMessageAtNewProjectCreatesIt() {
        run(msg(""""text":"明天交 ICARD 報告 @ICARD""""))
        val p = store.projects.single()
        assertEquals("ICARD", p.name)
        val t = store.tasks.values.single()
        assertEquals(p.id, t.projectId)
        assertTrue("@" !in t.title, t.title)
        // email 不算
        run(msg(""""text":"寄信給 abc@gmail.com""""))
        assertEquals(1, store.projects.size)
    }

    @Test
    fun projectWordInsideTitleIsNotAProjectCommand() {
        run(msg(""""text":"買牛奶""""))
        val inbox = store.inbox.values.single()
        run(replyTo(1000, createdKeyboardJson(inbox.id), "專案會議改明天"))
        assertTrue(store.projects.isEmpty())
    }

    // ---------- 10/20 測試回報 ----------

    private fun listKeyboard(ids: List<String>) =
        """{"inline_keyboard":[[${ids.mapIndexed { i, id -> """{"text":"${i + 1}","callback_data":"c:$id"}""" }.joinToString(",")}]]}"""

    @Test
    fun replyToAListUsesThatListsNumbers() {
        val ids = listThree()
        val fullListMsg = http.nextMessageId - 1
        run(msg(""""text":"清單 倒垃圾""""))
        assertEquals(listOf("b"), state.lastListed)
        // 回覆完整那張：照那張的編號
        run(replyTo(fullListMsg, listKeyboard(ids), "完成 3"))
        assertEquals(TaskStatus.DONE, store.tasks["c"]!!.status)
        assertTrue(http.lastText().startsWith("✅ 已完成「回信」"), http.lastText())
        // 沒回覆、號碼超出最近那張（只有倒垃圾）：先問，按了才照完整清單
        run(msg(""""text":"完成 1 2""""))
        assertTrue(http.lastText().startsWith("最近列的清單只有 1 項，沒有第 2 項"), http.lastText())
        assertTrue(store.tasks["a"]!!.status.isOpen && store.tasks["b"]!!.status.isOpen)
        assertEquals(listOf("dl:1.2"), lastKeyboard())
        run("""{"update_id":${updateId++},"callback_query":{"id":"cb","from":{"id":$me},"data":"dl:1.2",
            "message":{"message_id":1005,"chat":{"id":$me,"type":"private"}}}}""")
        assertEquals(TaskStatus.DONE, store.tasks["a"]!!.status)
        assertEquals(TaskStatus.DONE, store.tasks["b"]!!.status)
    }

    @Test
    fun replyToAListEditsOneItem() {
        val ids = listThree()
        val listMsg = http.nextMessageId - 1
        run(replyTo(listMsg, listKeyboard(ids), "2改名 寄出季報"))
        assertEquals("寄出季報", store.tasks["b"]!!.title)
        assertTrue(http.lastText().startsWith("✅ 已把「倒垃圾」改名為「寄出季報」"), http.lastText())
        run(replyTo(listMsg, listKeyboard(ids), "第3項設定為下午4點"))
        assertEquals(ZonedDateTime.of(2026, 10, 6, 16, 0, 0, 0, Defaults.ZONE).toInstant(), store.tasks["c"]!!.dueAt)
        // 沒回覆也行：照最近的清單
        run(msg(""""text":"1改名 交季報""""))
        assertEquals("交季報", store.tasks["a"]!!.title)
        assertEquals(3, store.tasks.size)
    }

    @Test
    fun confirmationMessagesCanBeRepliedTo() {
        run(msg(""""text":"買牛奶""""))
        val t = store.tasks.values.single()
        val inbox = store.inbox.values.single()
        run(replyTo(1000, createdKeyboardJson(inbox.id), "家庭"))
        val confirmation = http.nextMessageId - 1
        assertTrue(http.lastText().startsWith("✅ 已把「買牛奶」改到〔家庭〕"))
        // 回覆確認訊息再改一次
        run(replyTo(confirmation, "null", "這是個人的事"))
        assertEquals("個人", store.tasks[t.id]!!.topic)
        assertTrue("已建立 1 項（已修改）" in http.lastText("editMessageText"))
    }

    @Test
    fun undoCompletion() {
        val ids = listThree()
        run(msg(""""text":"完成 1""""))
        val confirmation = http.nextMessageId - 1
        assertEquals(TaskStatus.DONE, store.tasks[ids[0]]!!.status)
        run(replyTo(confirmation, "null", "取消完成"))
        assertEquals(TaskStatus.TODO, store.tasks[ids[0]]!!.status)
        assertTrue(http.lastText().startsWith("✅ 已把「交報告」改回未完成"), http.lastText())
        // 完成指令沒有編號：不要回「第 0 項」
        run(msg(""""text":"/done 0""""))
        assertTrue("第 0 項" !in http.lastText(), http.lastText())
    }

    @Test
    fun voiceReplyIsTranscribedThenApplied() {
        val ids = listThree()
        run(msg(""""text":"完成 2""""))
        val confirmation = http.nextMessageId - 1
        brain.geminiOut = """{"tasks":[],"heard":"取消完成"}"""
        run(msg(""""voice":{"file_id":"v9","duration":2,"mime_type":"audio/ogg"},"reply_to_message":{"message_id":$confirmation,"chat":{"id":$me,"type":"private"},
            "from":{"id":1,"is_bot":true,"first_name":"bot"}}"""))
        assertEquals(TaskStatus.TODO, store.tasks[ids[1]]!!.status)
    }

    @Test
    fun renameTogetherWithTimeAndSayWhatWasNotUnderstood() {
        run(msg(""""text":"開會""""))
        val t = store.tasks.values.single()
        val inbox = store.inbox.values.single()
        run(replyTo(1000, createdKeyboardJson(inbox.id), "改16點 會議名稱 跳跳"))
        assertEquals("跳跳", store.tasks[t.id]!!.title)
        assertEquals(ZonedDateTime.of(2026, 10, 6, 16, 0, 0, 0, Defaults.ZONE).toInstant(), store.tasks[t.id]!!.dueAt)
        run(replyTo(1000, createdKeyboardJson(inbox.id), "改17點 帶筆電"))
        assertTrue("「帶筆電」看不懂" in http.lastText(), http.lastText())
    }

    @Test
    fun newMessageThatLooksLikeAnEditAsksFirst() {
        run(msg(""""text":"明天兩點會議 改名 跳高活動""""))
        assertTrue(store.tasks.isEmpty())
        assertEquals(Replies.MODIFY_OR_NEW, http.lastText())
        val inbox = store.inbox.values.single()
        run("""{"update_id":${updateId++},"callback_query":{"id":"cb","from":{"id":$me},"data":"f:${inbox.id}",
            "message":{"message_id":1000,"chat":{"id":$me,"type":"private"}}}}""")
        assertEquals(1, store.tasks.size)
    }

    @Test
    fun passedTimeGoesToTomorrowAndCanBeMovedBack() {
        run(msg(""""text":"1130開會""""))
        val t = store.tasks.values.single()
        assertEquals(ZonedDateTime.of(2026, 10, 7, 11, 30, 0, 0, Defaults.ZONE).toInstant(), t.dueAt)
        assertTrue(http.lastText().startsWith("今天 11:30 已經過了"), http.lastText())
        assertEquals(listOf("td:${t.id}"), lastKeyboard())
        run("""{"update_id":${updateId++},"callback_query":{"id":"cb","from":{"id":$me},"data":"td:${t.id}",
            "message":{"message_id":1001,"chat":{"id":$me,"type":"private"}}}}""")
        assertEquals(ZonedDateTime.of(2026, 10, 6, 11, 30, 0, 0, Defaults.ZONE).toInstant(), store.tasks[t.id]!!.dueAt)
    }

    @Test
    fun duplicateOfAnOpenTaskOffersToMerge() {
        store.tasks["old"] = Task(id = "old", title = "處理重複寄送SQL語法", topic = "工作")
        run(msg(""""text":"處理重複寄送 SQL 語法""""))
        val new = store.tasks.values.first { it.id != "old" }
        assertTrue(http.lastText().startsWith("「處理重複寄送 SQL 語法」跟已經有的這筆很像"), http.lastText())
        assertEquals(listOf("mg:${new.id}:old", "kp:${new.id}"), lastKeyboard())
        run("""{"update_id":${updateId++},"callback_query":{"id":"cb","from":{"id":$me},"data":"mg:${new.id}:old",
            "message":{"message_id":1001,"chat":{"id":$me,"type":"private"}}}}""")
        assertNull(store.tasks[new.id])
        assertTrue("處理重複寄送 SQL 語法" in store.tasks["old"]!!.notes.orEmpty())
        // 不像的不問
        run(msg(""""text":"買牛奶""""))
        assertTrue(http.sent().none { "很像" in it.body["text"]!!.jsonPrimitive.content && "買牛奶" in it.body["text"]!!.jsonPrimitive.content })
    }

    @Test
    fun replyMergeIntoListItem() {
        listThree()
        run(msg(""""text":"寄報價單""""))
        val t = store.tasks.values.first { it.title == "寄報價單" }
        val inbox = store.inbox.values.single()
        run(msg(""""text":"列清單""""))
        run(replyTo(1000, createdKeyboardJson(inbox.id), "合併到 1"))
        assertNull(store.tasks[t.id])
        assertTrue("寄報價單" in store.tasks["a"]!!.notes.orEmpty())
    }

    @Test
    fun narrowLongScreenshotIsFlagged() {
        brain.geminiOut = """{"tasks":[{"title":"確認防火牆紀錄","topic":"工作","confidence":0.9}]}"""
        run(msg(""""photo":[{"file_id":"p1","width":40,"height":320},{"file_id":"p2","width":155,"height":1279}]"""))
        val created = http.sent().map { it.body["text"]!!.jsonPrimitive.content }.first { it.startsWith("已建立") }
        assertTrue("155×1279" in created, created)
        // 用檔案傳的截圖照原圖處理
        brain.geminiRequests.clear()
        run(msg(""""document":{"file_id":"d1","mime_type":"image/png","file_size":2000}"""))
        assertTrue(store.inbox.values.any { it.kind == "photo" && it.fileRefs == listOf("image/png|d1") })
    }

    @Test
    fun strongModelIsLabelled() {
        brain.geminiOut = """{"tasks":[{"title":"牙醫回診","topic":"個人","confidence":0.9}]}"""
        brain.hasStrong = true
        run(msg(""""text":"${"字".repeat(601)}""""))
        assertTrue(http.sent().any { "（Flash 整理）" in it.body["text"]!!.jsonPrimitive.content })
    }

    @Test
    fun topicPickerButtons() {
        run(msg(""""text":"買牛奶""""))
        val t = store.tasks.values.single()
        val inbox = store.inbox.values.single()
        run("""{"update_id":${updateId++},"callback_query":{"id":"cb","from":{"id":$me},"data":"tp:${inbox.id}",
            "message":{"message_id":1000,"chat":{"id":$me,"type":"private"}}}}""")
        assertTrue(http.lastText().startsWith(Replies.TOPIC_PROMPT))
        val family = Defaults.TOPICS.indexOf("家庭")
        assertTrue("ts:${t.id}:$family" in lastKeyboard())

        run("""{"update_id":${updateId++},"callback_query":{"id":"cb2","from":{"id":$me},"data":"ts:${t.id}:$family",
            "message":{"message_id":1001,"chat":{"id":$me,"type":"private"},"text":"${Replies.TOPIC_PROMPT}\n買牛奶　〔個人〕",
            "reply_markup":{"inline_keyboard":[[{"text":"家庭","callback_data":"ts:${t.id}:$family"}]]},
            "reply_to_message":{"message_id":1000,"chat":{"id":$me,"type":"private"},"reply_markup":${createdKeyboardJson(inbox.id)}}}}}""")
        assertEquals("家庭", store.tasks[t.id]!!.topic)
        assertTrue("〔家庭〕" in http.lastText("editMessageText"))
        assertEquals(1, brain.hints.learned.size)
    }

    // ---------- 一次完成多項、語音指令 ----------

    @Test
    fun doneNumbersParsing() {
        assertEquals("1 2", InboxBot.doneNumbers("第一項第二項完成"))
        assertEquals("1 3", InboxBot.doneNumbers("完成 1、3"))
        assertEquals("1 2", InboxBot.doneNumbers("1 2 完成"))
        assertEquals("12", InboxBot.doneNumbers("第十二項做完了"))
        assertEquals("2", InboxBot.doneNumbers("完成2"))
        assertEquals("all", InboxBot.doneNumbers("全部完成"))
        assertNull(InboxBot.doneNumbers("完成報告"))
        assertNull(InboxBot.doneNumbers("完成"))
    }

    private fun listThree(): List<String> {
        listOf("a" to "交報告", "b" to "倒垃圾", "c" to "回信").forEachIndexed { i, (id, title) ->
            store.tasks[id] = Task(id = id, title = title, dueAt = now.plusHours(i + 1L).toInstant())
        }
        run(msg(""""text":"列清單""""))
        return listOf("a", "b", "c")
    }

    @Test
    fun completeSeveralAtOnce() {
        listThree()
        run(msg(""""text":"第一項第二項完成""""))
        assertEquals(TaskStatus.DONE, store.tasks["a"]!!.status)
        assertEquals(TaskStatus.DONE, store.tasks["b"]!!.status)
        assertEquals(TaskStatus.TODO, store.tasks["c"]!!.status)
        assertEquals(3, store.tasks.size)
        assertTrue("交報告" in http.lastText() && "倒垃圾" in http.lastText())
    }

    @Test
    fun voiceCommand() {
        listThree()
        brain.geminiOut = """{"tasks":[],"command":"done all","heard":"全部完成"}"""
        run(msg(""""voice":{"file_id":"v1","duration":2,"mime_type":"audio/ogg"}"""))
        assertTrue(store.tasks.values.all { it.status == TaskStatus.DONE })
        assertEquals(3, store.tasks.size)
    }

    // ---------- Gemini 忙線 ----------

    @Test
    fun busyGeminiUsesBackupModel() {
        brain.geminiOut = """{"tasks":[{"title":"交報告","topic":"工作","confidence":0.9}]}"""
        brain.hasStrong = true
        brain.failures += 503
        run(msg(""""text":"明天交報告""""))
        assertEquals(1, brain.strongCalls)
        assertEquals("工作", store.tasks.values.single().topic)
        assertTrue("Gemini" !in http.lastText("sendMessage"), http.lastText())
    }

    // ---------- 模型分流 ----------

    @Test
    fun screenshotGoesStraightToStrongModel() = runBlocking {
        brain.geminiOut = """{"tasks":[{"title":"牙醫回診","topic":"個人","confidence":0.9}]}"""
        brain.hasStrong = true
        val organizer = Organizer(store, brain, now = { now }, retryDelayMs = 0)
        val out = organizer.organize(
            Organizer.Input("", null, listOf(Organizer.Attachment("image/png", Organizer.Kind.PHOTO) { byteArrayOf(9) }), SourceKind.TELEGRAM, "Telegram"),
        )
        assertEquals("牙醫回診", out.items.single().first.title)
        assertTrue(brain.geminiRequests.isEmpty())
        assertEquals(1, brain.strongCalls)
        assertEquals("strong:A1", out.inbox.model)
    }

    @Test
    fun unsureLiteResultIsRedoneWithStrongModel() = runBlocking {
        brain.geminiOut = """{"tasks":[{"title":"處理清單","topic":"個人","confidence":0.4}]}"""
        brain.strongOut = """{"tasks":[{"title":"請僑商處提供店家城市清單","topic":"工作","confidence":0.9}]}"""
        brain.hasStrong = true
        val organizer = Organizer(store, brain, now = { now }, retryDelayMs = 0)
        fun input() = Organizer.Input("提醒僑商處要提供商店的店家城市清單", null, emptyList(), SourceKind.TELEGRAM, "Telegram")
        val out = organizer.organize(input())
        assertEquals("工作", out.items.single().first.topic)
        assertEquals("strong:B2", out.inbox.model)

        // 今天自動升級用完：保留 Lite 的結果
        brain.strongBudget = 0
        val kept = organizer.organize(input())
        assertEquals("處理清單", kept.items.single().first.title)
        assertEquals("lite:B2", kept.inbox.model)
    }

    @Test
    fun goodLiteResultStaysOnLite() = runBlocking {
        brain.geminiOut = """{"tasks":[{"title":"交報告","topic":"工作","confidence":0.9,"due":"2026-10-09T09:00"}]}"""
        brain.hasStrong = true
        val out = Organizer(store, brain, now = { now }, retryDelayMs = 0)
            .organize(Organizer.Input("明天交報告", null, emptyList(), SourceKind.TELEGRAM, "Telegram"))
        assertEquals(0, brain.strongCalls)
        assertEquals("lite", out.inbox.model)
    }

    @Test
    fun missingTimeIsRedoneButExplicitTopicIsNot() = runBlocking {
        brain.geminiOut = """{"tasks":[{"title":"交報告","confidence":0.9}]}"""
        brain.hasStrong = true
        val organizer = Organizer(store, brain, now = { now }, retryDelayMs = 0)
        // 有指定主題、也沒講時間：不升級
        val given = organizer.organize(Organizer.Input("工作，交報告", null, emptyList(), SourceKind.TELEGRAM, "Telegram"))
        assertEquals("lite", given.inbox.model)
        // 講了「明天」結果卻沒時間（也沒主題）：升級
        val timed = organizer.organize(Organizer.Input("工作，明天交報告", null, emptyList(), SourceKind.TELEGRAM, "Telegram"))
        assertEquals("strong:B5", timed.inbox.model)
    }

    @Test
    fun replyRedoUsesStrongModelEvenOverBudget() {
        brain.geminiOut = """{"tasks":[{"title":"處理清單","topic":"個人","confidence":0.9}]}"""
        brain.strongOut = """{"tasks":[{"title":"請僑商處提供清單","topic":"工作","confidence":0.9},{"title":"整理店家城市","topic":"工作","confidence":0.9}]}"""
        brain.hasStrong = true
        brain.strongBudget = 0
        run(msg(""""text":"僑商處清單""""))
        val old = store.tasks.values.single()
        val inbox = store.inbox.values.single()
        run(replyTo(1000, createdKeyboardJson(inbox.id), "重整"))
        assertEquals(listOf(false), brain.strongAuto)
        assertNull(store.tasks[old.id])
        assertEquals(setOf("請僑商處提供清單", "整理店家城市"), store.tasks.values.map { it.title }.toSet())
        assertEquals(Replies.REDO_DONE, http.lastText())
        assertTrue("整理店家城市" in http.lastText("editMessageText"))

        // 沒有強模型：原本的不動
        brain.hasStrong = false
        run(replyTo(1000, createdKeyboardJson(inbox.id), "重整"))
        assertEquals(Replies.REDO_FAILED, http.lastText())
        assertEquals(2, store.tasks.size)
    }

    @Test
    fun busyVoiceWaitsThenRetries() {
        brain.geminiOut = """{"tasks":[{"title":"週五交報告","topic":"工作","confidence":0.9}],"heard":"週五交報告"}"""
        brain.failures += listOf(503, 503)
        run(msg(""""voice":{"file_id":"v1","duration":3,"mime_type":"audio/ogg"}"""))
        assertTrue(store.tasks.isEmpty())
        assertEquals(Replies.VOICE_WAITING, http.lastText())
        val waiting = store.inbox.values.single()
        assertEquals(InboxEntry.PENDING, waiting.status)
        assertEquals(listOf("audio/ogg|v1"), waiting.fileRefs)

        runBlocking { bot.retryPending() }
        val t = store.tasks.values.single()
        assertEquals("週五交報告", t.title)
        assertTrue(http.lastText().startsWith("已建立 1 項"))
        assertEquals(waiting.tgMessageId, http.sent().last().body["reply_parameters"]!!.jsonObject["message_id"]!!.jsonPrimitive.content.toLong())
        assertEquals(1, http.sent("deleteMessage").size)
        assertEquals("週五交報告", store.inbox.values.single().text)
        assertTrue(store.inbox.values.none { it.retryAi })
    }

    @Test
    fun busyTextIsReclassifiedLater() {
        brain.geminiOut = """{"tasks":[{"title":"請僑商處提供店家城市清單","topic":"工作","confidence":0.9}]}"""
        brain.failures += listOf(503, 503)
        run(msg(""""text":"提醒僑商處要提供商店的店家城市清單""""))
        val t = store.tasks.values.single()
        assertEquals("個人", t.topic)
        val created = http.sent().first { it.body["text"]?.jsonPrimitive?.content?.startsWith("已建立") == true }
        assertTrue("恢復後會自動重新分類" in created.body["text"]!!.jsonPrimitive.content)
        assertTrue(store.inbox.values.single().retryAi)

        runBlocking { bot.retryPending() }
        assertEquals("工作", store.tasks[t.id]!!.topic)
        assertEquals(t.title, store.tasks[t.id]!!.title) // 標題不動
        assertTrue("〔工作〕" in http.lastText("editMessageText"))
        assertTrue(store.inbox.values.none { it.retryAi })
    }

    @Test
    fun nothingFoundOffersForceButton() {
        brain.geminiOut = """{"tasks":[]}"""
        run(msg(""""text":"哈哈好喔""""))
        assertTrue(store.tasks.isEmpty())
        val inbox = store.inbox.values.single()
        assertEquals(InboxEntry.IGNORED, inbox.status)
        assertEquals(listOf("f:${inbox.id}"), lastKeyboard())
        run("""{"update_id":${updateId++},"callback_query":{"id":"cb","from":{"id":$me},"data":"f:${inbox.id}",
            "message":{"message_id":1000,"chat":{"id":$me,"type":"private"}}}}""")
        assertEquals("哈哈好喔", store.tasks.values.single().title)
    }

    @Test
    fun geminiBusyIsRetriedOnceAndOtherErrorsAreExplained() = runBlocking {
        brain.geminiOut = """{"tasks":[{"title":"繳保費","topic":"家庭","confidence":0.9}]}"""
        val organizer = Organizer(store, brain, now = { now }, retryDelayMs = 0)
        fun input() = Organizer.Input("繳保費", null, emptyList(), SourceKind.TELEGRAM, "Telegram")

        brain.failures += 503
        val ok = organizer.organize(input())
        assertNull(ok.notice)
        assertEquals("繳保費", ok.items.single().first.title)

        brain.failures += listOf(404)
        val bad = organizer.organize(input())
        assertTrue("模型" in bad.notice!!, bad.notice)
        assertTrue(brain.failures.isEmpty())
    }

    @Test
    fun timeOnlyEditKeepsTheSameDayAndBareNumbersAsk() {
        run(msg(""""text":"買牛奶\n打電話給王經理""""))
        val inbox = store.inbox.values.single()
        val keyboard = """{"inline_keyboard":[[{"text":"x","callback_data":"d:${inbox.id}:0"}]]}"""
        fun edit(words: String) = run(msg(""""text":"$words","reply_to_message":{"message_id":1000,"chat":{"id":$me,"type":"private"},
            "from":{"id":1,"is_bot":true,"first_name":"bot"},"reply_markup":$keyboard}"""))
        fun call() = store.tasks.values.first { it.title.contains("王經理") }.dueAt
        edit("2 改成週五下午3點")
        // 4 碼是 24 小時制時間，留在原本那天
        edit("2 改0930")
        assertEquals(ZonedDateTime.of(2026, 10, 9, 9, 30, 0, 0, Defaults.ZONE).toInstant(), call())
        // 不是合法時間的 4 碼、6 碼：問
        edit("2 改2530")
        assertTrue("「2530」看不出是日期還是時間" in http.lastText(), http.lastText())
        edit("2 改251130")
        assertTrue("「251130」" in http.lastText(), http.lastText())
        edit("2 改9:45")
        assertEquals(ZonedDateTime.of(2026, 10, 9, 9, 45, 0, 0, Defaults.ZONE).toInstant(), call())
        edit("2 改5點45分")
        assertEquals(ZonedDateTime.of(2026, 10, 9, 17, 45, 0, 0, Defaults.ZONE).toInstant(), call())
    }

    @Test
    fun brokenUpdateStillAdvancesOffset() {
        val badStore = object : InboxStore by store {
            override suspend fun parseContext(now: ZonedDateTime): ParseContext = throw IllegalStateException("boom")
        }
        val b = InboxBot(TelegramApi("TOKEN", http), badStore, state, brain, now = { now })
        runBlocking { b.handle(listOf(TelegramApi.json.decodeFromString<Update>(msg(""""text":"x"""")))) }
        assertEquals(updateId, state.nextOffset)
        assertTrue(http.lastText().startsWith("這則沒整理成功（boom）"))
    }

    @Test
    fun mediaGroupIsOneInboxEntry() {
        brain.geminiOut = """{"tasks":[{"title":"看兩張截圖","topic":"個人","confidence":0.9}]}"""
        run(
            msg(""""media_group_id":"g","caption":"這兩張","photo":[{"file_id":"a","width":1,"height":1}]"""),
            msg(""""media_group_id":"g","photo":[{"file_id":"b","width":1,"height":1}]"""),
        )
        assertEquals(1, store.inbox.size)
        assertEquals(2, http.sent("getFile").size)
    }

    @Test
    fun sharedContentUsesSameOrganizer() = runBlocking {
        brain.geminiOut = """{"tasks":[{"title":"繳保費","topic":"家庭","confidence":0.9,"due":"2026-10-10T09:00","tags":["付款"]}]}"""
        val loaded = mutableListOf<String>()
        val out = Organizer(store, brain, now = { now }).organize(
            Organizer.Input(
                "保費 10/10 到期", null,
                listOf(Organizer.Attachment("image/png", Organizer.Kind.PHOTO) { loaded += "img"; byteArrayOf(9) }),
                SourceKind.SHARE, "分享來的",
            ),
        )
        val t = out.items.single().first
        assertEquals("繳保費", t.title)
        assertEquals(SourceKind.SHARE, t.source.kind)
        assertEquals(listOf("pay"), t.tags)
        assertEquals(listOf("img"), loaded)
        assertTrue("image/png" in brain.geminiRequests.single())
        assertEquals("photo", store.inbox[out.inbox.id]!!.kind)
        assertTrue(http.calls.isEmpty())
    }

    @Test
    fun reminderIsSentAndButtonsWork() {
        run(msg(""""text":"今天下午3點打電話給王經理""""))
        val id = store.tasks.keys.single()
        fun press(data: String) = run("""{"update_id":${updateId++},"callback_query":{"id":"cb","from":{"id":$me},"data":"$data",
            "message":{"message_id":1000,"chat":{"id":$me,"type":"private"}}}}""")
        runBlocking { bot.sendReminder(id, "x") }
        assertTrue(http.lastText().startsWith("⏰ 提醒"), http.lastText())
        assertEquals(listOf("rc", "rl", "rm").map { "$it:$id" }, lastKeyboard())
        press("rl:$id")
        assertEquals(Replies.LATER_PROMPT, http.lastText())
        assertEquals(listOf("rs", "rh", "rn", "rt", "re").map { "$it:$id" }, lastKeyboard())
        press("rm:$id")
        assertEquals(listOf("rp", "rw", "re", "rd").map { "$it:$id" }, lastKeyboard())

        press("rs:$id")
        assertEquals(now.plusMinutes(10).toInstant(), store.tasks[id]!!.remindAt)
        assertTrue("下次提醒：今天 14:10" in http.lastText("editMessageText"), http.lastText("editMessageText"))
        press("rp:$id")
        assertEquals(TaskStatus.DOING, store.tasks[id]!!.status)

        // 改日期：按按鈕 → Bot 問時間 → 回覆那則
        press("re:$id")
        assertEquals(listOf("rx:$id"), lastKeyboard())
        val prompt = """{"inline_keyboard":[[{"text":"取消","callback_data":"rx:$id"}]]}"""
        run(msg(""""text":"週五下午3點","reply_to_message":{"message_id":1001,"chat":{"id":$me,"type":"private"},"reply_markup":$prompt}"""))
        assertEquals(ZonedDateTime.of(2026, 10, 9, 15, 0, 0, 0, Defaults.ZONE).toInstant(), store.tasks[id]!!.dueAt)
        run(msg(""""text":"等王經理","reply_to_message":{"message_id":1001,"chat":{"id":$me,"type":"private"},"reply_markup":$prompt}"""))
        assertEquals(TaskStatus.WAITING, store.tasks[id]!!.status)
        assertEquals("王經理", store.tasks[id]!!.waitingFor)
        press("rt:$id")
        assertEquals(now.plusDays(1).withHour(9).toInstant(), store.tasks[id]!!.remindAt)
        press("rc:$id")
        assertEquals(TaskStatus.DONE, store.tasks[id]!!.status)

        val before = http.sent().size
        runBlocking { bot.sendReminder(id, "x") } // 已完成就不再發
        assertEquals(before, http.sent().size)
        runBlocking { bot.sendReminder("__test__", "測試提醒") }
        assertEquals("⏰ 提醒\n測試提醒", http.lastText())

        run(msg(""""text":"明天繳費""""))
        val other = store.tasks.values.first { it.status.isOpen }.id
        press("rd:$other")
        assertNull(store.tasks[other])
    }

    @Test
    fun replyToCompletedReminderAndReplyChain() {
        val ids = listThree()
        store.tasks["sql"] = Task(id = "sql", title = "處理重複寄送SQL語法", dueAt = now.plusHours(2).toInstant())
        runBlocking { bot.sendReminder("sql", "x") }
        val reminder = http.nextMessageId - 1
        run("""{"update_id":${updateId++},"callback_query":{"id":"cb","from":{"id":$me},"data":"rc:sql",
            "message":{"message_id":$reminder,"chat":{"id":$me,"type":"private"}}}}""")
        assertEquals(TaskStatus.DONE, store.tasks["sql"]!!.status)
        // 按完成後按鈕沒了，回覆那則還是要對到這一筆，不是最近清單上的
        val before = updateId
        run(replyTo(reminder, "null", "調整為未完成"))
        assertEquals(TaskStatus.TODO, store.tasks["sql"]!!.status)
        assertTrue(http.lastText().startsWith("✅ 已把「處理重複寄送SQL語法」改回未完成"), http.lastText())
        assertEquals(TaskStatus.TODO, store.tasks[ids[0]]!!.status)
        // 回覆自己剛剛那則：沿著回覆鏈找到同一筆
        val mine = before + 1 + 50
        val due = store.tasks["sql"]!!.dueAt!!
        run(msg(""""text":"延期一週","reply_to_message":{"message_id":$mine,"chat":{"id":$me,"type":"private"},
            "from":{"id":$me,"is_bot":false,"first_name":"Me"},"text":"調整為未完成"}"""))
        assertEquals(due.atZone(now.zone).plusWeeks(1).toInstant(), store.tasks["sql"]!!.dueAt)
        assertTrue(http.lastText().startsWith("✅ 已把「處理重複寄送SQL語法」延後 1 週"), http.lastText())
    }

    @Test
    fun replyToOldBotMessageFindsTaskByTitle() {
        listThree()
        store.tasks["sql"] = Task(id = "sql", title = "處理重複寄送SQL語法", status = TaskStatus.DONE, dueAt = now.plusHours(2).toInstant())
        // 更新前發的提醒：沒有按鈕也沒記下來，只能看訊息裡的標題
        run(msg(""""text":"改回未完成","reply_to_message":{"message_id":777,"chat":{"id":$me,"type":"private"},
            "from":{"id":1,"is_bot":true,"first_name":"bot"},"text":"✅ 已完成\n✅ 處理重複寄送SQL語法 今天 14:00"}"""))
        assertEquals(TaskStatus.TODO, store.tasks["sql"]!!.status)
        run(replyTo(http.nextMessageId - 1, "null", "延後3天"))
        assertEquals(now.plusHours(2).plusDays(3).toInstant(), store.tasks["sql"]!!.dueAt)
    }
}
