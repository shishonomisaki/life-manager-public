package com.shishonomisaki.lifemanager.telegram

import com.shishonomisaki.lifemanager.core.model.AutoField
import com.shishonomisaki.lifemanager.core.model.Defaults
import com.shishonomisaki.lifemanager.core.model.Project
import com.shishonomisaki.lifemanager.core.model.SourceKind
import com.shishonomisaki.lifemanager.core.model.Task
import com.shishonomisaki.lifemanager.core.model.TaskSource
import com.shishonomisaki.lifemanager.core.model.TaskStatus
import com.shishonomisaki.lifemanager.core.parse.GeminiTaskParser
import com.shishonomisaki.lifemanager.core.parse.ParseContext
import com.shishonomisaki.lifemanager.core.parse.ParseResult
import com.shishonomisaki.lifemanager.core.parse.ParsedTask
import com.shishonomisaki.lifemanager.core.parse.QuotaExceededException
import com.shishonomisaki.lifemanager.core.parse.TimeParser
import com.shishonomisaki.lifemanager.core.stats.WeeklyStats
import java.time.ZonedDateTime
import java.util.Base64

/**
 * Telegram 收件匣（規格第 5 節，階段 0 版）：拉訊息 → 整理成待辦 → 直接建立 → 回覆結果和刪除按鈕。
 * 只理會已綁定的那個私人對話，其他人只能用一次性代碼綁定。
 */
class InboxBot(
    private val api: TelegramApi,
    private val store: InboxStore,
    private val state: BotState,
    private val brain: Brain,
    private val now: () -> ZonedDateTime = { ZonedDateTime.now(Defaults.ZONE) },
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
    geminiRetryDelayMs: Long = 2_000,
) {
    private val organizer = Organizer(store, brain, now, log, geminiRetryDelayMs)

    /** 拉一次訊息並全部處理完，回傳處理了幾則。[timeoutSec] > 0 是長輪詢。 */
    suspend fun poll(timeoutSec: Int): Int {
        val updates = api.getUpdates(state.nextOffset.takeIf { it > 0 }, timeoutSec)
        handle(updates)
        return updates.size
    }

    suspend fun handle(updates: List<Update>) {
        val sorted = updates.sortedBy { it.updateId }
        var i = 0
        while (i < sorted.size) {
            // 一次傳好幾張圖會拆成好幾則，同一組合在一起整理
            val group = sorted[i].message?.mediaGroupId
            val batch = if (group == null) listOf(sorted[i])
            else sorted.drop(i).takeWhile { it.message?.mediaGroupId == group }
            try {
                handleBatch(batch)
            } catch (e: Exception) {
                log("處理 Telegram 訊息失敗", e)
                tellFailure(batch, e)
            }
            // 失敗也往下走，避免同一則一直重試、重複建立
            state.nextOffset = batch.last().updateId + 1
            i += batch.size
        }
    }

    private suspend fun handleBatch(batch: List<Update>) {
        batch.first().callbackQuery?.let { return onCallback(it) }
        val messages = batch.mapNotNull { it.message }
        val first = messages.firstOrNull() ?: return
        if (first.chat.type != "private") return
        val chatId = first.chat.id
        val text = first.text?.trim()

        // 綁定：/link 123456，或從 APP 點連結開啟時的 /start 123456
        LINK.find(text.orEmpty())?.let { m ->
            if (state.tryLink(m.groupValues[1], chatId)) {
                store.linkChat(chatId)
                send(chatId, Replies.LINKED)
            } else {
                send(chatId, Replies.LINK_FAILED)
            }
            return
        }
        if (chatId != state.linkedChatId) {
            // 還沒連結也要回一句，不然看起來像 Bot 壞掉
            send(chatId, Replies.NOT_LINKED)
            return
        }
        if (text != null && text.startsWith("/")) return onCommand(first, text)
        val plain = messages.size == 1 && first.forwardOrigin == null && first.replyToMessage == null
        if (plain && text != null) {
            shortcut(text)?.let { return onCommand(first, it) }
        }
        val reply = first.replyToMessage
        // 回覆自己的訊息（回覆鏈）：那則之前改過哪一筆，就接著改那一筆
        val fromBot = reply?.from?.isBot != false
        if (reply != null && first.forwardOrigin == null && (fromBot || state.tasksOfMessage(reply.messageId).isNotEmpty())) {
            val inboxId = if (fromBot) Callback.inboxIdOf(reply.replyMarkup) else null
            val taskId = if (fromBot) Callback.taskIdOf(reply.replyMarkup) else null
            val listed = if (fromBot) Callback.completeIdsOf(reply.replyMarkup) else emptyList()
            val remembered = state.tasksOfMessage(reply.messageId).ifEmpty { if (fromBot) titledIn(reply.text) else emptyList() }
            if (inboxId != null || taskId != null || listed.isNotEmpty() || remembered.isNotEmpty()) {
                // 用語音回覆：先聽寫，再當成打字處理
                val said = text ?: spoken(messages) ?: return
                when {
                    inboxId != null -> return onEditReply(first, reply, inboxId, said)
                    taskId != null -> return editTasks(first, listOf(taskId), said, reminderKeyboard = true)
                    listed.isNotEmpty() -> if (onListReply(first, listed, said)) return
                    else -> return editTasks(first, remembered, said)
                }
            }
        }
        // 沒回覆任何訊息卻在講「改名」「改成」：多半是想改舊的，先問，不要直接建成新待辦
        if (plain && text != null && first.photo == null && first.document == null && MODIFY_HINT.containsMatchIn(text)) {
            return askModifyOrNew(first, text)
        }
        onContent(messages)
    }

    /**
     * 舊的 Bot 訊息沒有按鈕也沒記下來（例如更新前發的提醒、已按完成的）：用訊息裡的待辦標題找。
     * 只認得出一筆時才用，認不出回傳空的。
     */
    private suspend fun titledIn(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        val hits = store.allTasks().filter { it.title.length >= 2 && text.contains(it.title) }
        if (hits.isEmpty()) return emptyList()
        // 「會議」和「準備會議」都出現時取長的那個
        val longest = hits.maxOf { it.title.length }
        return hits.filter { it.title.length == longest }.takeIf { it.size == 1 }?.map { it.id }.orEmpty()
    }

    /** 回覆 Bot 的語音：聽寫成文字。聽不到（沒有 Gemini 或失敗）就說一聲，回傳 null。 */
    private suspend fun spoken(messages: List<Message>): String? {
        val m = messages.first()
        val voice = m.voice ?: return null.also { send(m.chat.id, Replies.REPLY_TEXT_ONLY, replyTo = m.messageId) }
        val heard = organizer.transcribe(Organizer.Attachment(voice.mimeType ?: "audio/ogg", Organizer.Kind.VOICE, voice.duration) { api.downloadFile(voice.fileId) })
        if (heard == null) send(m.chat.id, Replies.VOICE_REPLY_FAILED, replyTo = m.messageId)
        return heard
    }

    private suspend fun askModifyOrNew(msg: Message, text: String) {
        val inbox = InboxEntry(
            id = store.newInboxId(), kind = "text", text = text, forwardFrom = null,
            tgMessageId = msg.messageId, tgChatId = msg.chat.id, status = InboxEntry.IGNORED, taskIds = emptyList(),
        )
        store.saveInbox(inbox)
        send(
            msg.chat.id, Replies.MODIFY_OR_NEW, replyTo = msg.messageId,
            keyboard = InlineKeyboard(listOf(listOf(Button("➕ 還是新增", "${Callback.FORCE}:${inbox.id}")))),
        )
    }

    // ---------- APP → Telegram ----------

    /** 手機上的提醒響了，同步發到 Telegram，在筆電上也能按完成或延後。 */
    suspend fun sendReminder(taskId: String, fallbackTitle: String) {
        val chatId = state.linkedChatId ?: return
        val t = store.getTask(taskId)
        if (t == null) {
            send(chatId, "⏰ 提醒\n$fallbackTitle")
            return
        }
        if (!t.status.isOpen) return
        val sent = post(chatId, Replies.reminder(t, lookup(store.parseContext(now()))), keyboard = Replies.reminderKeyboard(t.id))
        // 按完成後按鈕會拿掉，回覆時就靠這個找回是哪一筆
        state.rememberMessage(sent.messageId, listOf(t.id))
    }

    /** 提醒訊息上的按鈕。回傳給 Telegram 小提示的文字。 */
    private suspend fun onReminderButton(
        chatId: Long, messageId: Long, action: String, taskId: String, keyboard: InlineKeyboard?,
    ): String? {
        val t = store.getTask(taskId) ?: run {
            runCatching { api.editMessageText(chatId, messageId, "這項已經被刪除了。") }
            return "已刪除"
        }
        // 按完成會拿掉按鈕；記下來，之後回覆這則（例如「改回未完成」）才知道是哪一筆
        state.rememberMessage(messageId, listOf(t.id))
        val n = now()
        val l = lookup(store.parseContext(n))
        // 在另外跳出的選項泡泡上按的：結果取代那則選項；在提醒本身按的：保留三個按鈕
        val onReminder = Callback.isReminderMessage(keyboard)
        suspend fun show(prefix: String, task: Task, done: Boolean = false) {
            val kb = if (onReminder && !done) Replies.reminderKeyboard(task.id) else null
            runCatching { api.editMessageText(chatId, messageId, prefix + "\n" + Replies.line(task, l), kb) }
        }
        return when (action) {
            Callback.REMIND_DONE -> {
                if (t.status.isOpen) store.completeTask(t.id)
                show("✅ 已完成", t.copy(status = TaskStatus.DONE), done = true)
                "已完成"
            }
            Callback.REMIND_DELETE -> {
                store.deleteTask(t.id)
                runCatching { api.editMessageText(chatId, messageId, "🗑 已刪除：${t.title}") }
                "已刪除"
            }
            Callback.REMIND_DOING, Callback.REMIND_WAITING -> {
                val status = if (action == Callback.REMIND_DOING) TaskStatus.DOING else TaskStatus.WAITING
                val updated = t.copy(status = status)
                store.saveTask(updated)
                show("📌 改成「${status.label}」", updated)
                status.label
            }
            Callback.REMIND_LATER -> {
                send(chatId, Replies.LATER_PROMPT, replyTo = messageId, keyboard = Replies.laterKeyboard(t.id))
                null
            }
            Callback.REMIND_MORE -> {
                send(chatId, Replies.MORE_PROMPT, replyTo = messageId, keyboard = Replies.moreKeyboard(t.id))
                null
            }
            Callback.REMIND_DATE -> {
                send(
                    chatId, "${t.title}\n要改到什麼時候？直接回覆這則，例如「週五下午3點」「10/20」「明天早上」。",
                    replyTo = messageId, keyboard = Replies.editPromptKeyboard(t.id),
                )
                null
            }
            Callback.REMIND_CANCEL -> {
                runCatching { api.editMessageText(chatId, messageId, "不改了。") }
                null
            }
            else -> {
                val at = when (action) {
                    Callback.REMIND_SNOOZE -> n.plusMinutes(10)
                    Callback.REMIND_HOUR -> n.plusHours(1)
                    Callback.REMIND_TONIGHT -> n.toLocalDate().atTime(20, 0).atZone(n.zone).let { if (it.isAfter(n)) it else it.plusDays(1) }
                    else -> n.toLocalDate().plusDays(1).atTime(9, 0).atZone(n.zone)
                }.toInstant()
                val updated = t.copy(remindAt = at)
                store.saveTask(updated)
                val label = Replies.dateTime(at, n.zone, n.toLocalDate())
                show("⏰ 下次提醒：$label", updated)
                "下次提醒 $label"
            }
        }
    }

    /**
     * 回覆提醒、確認訊息或清單來改待辦。[ids] 是那則訊息上的待辦；好幾筆時要講編號（「2 改明天」）或「全部」。
     * 改完回一則確認，並記下來，回覆確認訊息也能接著改。
     */
    private suspend fun editTasks(
        msg: Message, ids: List<String>, text: String, reminderKeyboard: Boolean = false,
        after: (suspend (ParseContext) -> Unit)? = null,
    ) {
        val chatId = msg.chat.id
        val (index, afterIndex) = splitIndex(text.trim())
        val targets: List<String> = when {
            index != null -> listOfNotNull(ids.getOrNull(index - 1))
            ALL.containsMatchIn(afterIndex) || ids.size == 1 -> ids
            else -> ids.filter { store.getTask(it) != null }.takeIf { it.size == 1 }.orEmpty()
        }
        if (targets.isEmpty()) {
            val hint = if (index != null) "沒有第 $index 項。" else "要改哪一項？前面加編號，例如「2 改成明天」。"
            return send(chatId, hint, replyTo = msg.messageId)
        }
        val tasks = targets.mapNotNull { store.getTask(it) }
        if (tasks.isEmpty()) return send(chatId, "這項已經被刪除了。", replyTo = msg.messageId)
        // 回覆自己這則（「延期一週」回覆「改回未完成」）也找得到是哪一筆
        state.rememberMessage(msg.messageId, tasks.map { it.id })
        val words = afterIndex.replace(ALL, "").trim()
        val ctx = withProject(words, store.parseContext(now()), tasks)
        val lines = tasks.mapNotNull { applyEdit(it, words, ctx) }.flatten()
        if (lines.isEmpty()) return send(chatId, editHelp(words), replyTo = msg.messageId)
        after?.invoke(ctx)
        val kb = if (reminderKeyboard && tasks.size == 1) store.getTask(tasks[0].id)?.takeIf { it.status.isOpen }?.let { Replies.reminderKeyboard(it.id) } else null
        confirm(chatId, lines.joinToString("\n"), msg.messageId, tasks.map { it.id }, kb)
    }

    /** 送出確認訊息並記下是哪幾筆，回覆它可以接著改。 */
    private suspend fun confirm(chatId: Long, text: String, replyTo: Long?, taskIds: List<String>, keyboard: InlineKeyboard? = null) {
        val sent = post(chatId, text, replyTo, keyboard)
        state.rememberMessage(sent.messageId, taskIds)
    }

    /** 回覆清單：「完成 6 8」照這張清單的編號完成；「7 改到早上」「9改名 X」改那一筆。不是這兩種回傳 false。 */
    private suspend fun onListReply(msg: Message, ids: List<String>, text: String): Boolean {
        doneNumbers(text)?.let { completeNumbers(msg.chat.id, ids, it, msg.messageId, askFull = false); return true }
        if (splitIndex(text.trim()).first == null) return false
        editTasks(msg, ids, text)
        return true
    }

    /** 照 [listed] 的編號完成。[askFull]：編號超出這張清單、但上一張完整清單有，就先問要不要照完整清單。 */
    private suspend fun completeNumbers(chatId: Long, listed: List<String>, arg: String, replyTo: Long?, askFull: Boolean) {
        val picks = (if (arg == "all") listed.indices.map { it + 1 } else arg.split(' ').mapNotNull { it.toIntOrNull() })
            .filter { it >= 1 }.distinct()
        if (picks.isEmpty() || listed.isEmpty()) {
            return send(chatId, "先打「列清單」或 /today 看清單，再傳「完成 2」或「第一項第二項完成」。", replyTo = replyTo)
        }
        val full = state.lastFullListed
        if (askFull && picks.any { it > listed.size } && full != listed && picks.all { it <= full.size }) {
            return send(
                chatId, Replies.askFullList(listed.size, picks.filter { it > listed.size }), replyTo = replyTo,
                keyboard = InlineKeyboard(listOf(listOf(Button("照完整清單完成 ${picks.joinToString("、")}", "${Callback.DONE_FULL}:${picks.joinToString(".")}")))),
            )
        }
        val done = mutableListOf<String>()
        val lines = picks.map { n ->
            val id = listed.getOrNull(n - 1) ?: return@map "沒有第 $n 項。"
            val t = store.getTask(id) ?: return@map "第 $n 項已經被刪除了。"
            if (t.status.isOpen) store.completeTask(id)
            done += id
            "✅ 已完成「${t.title}」"
        }
        confirm(chatId, lines.joinToString("\n"), replyTo, done)
    }

    /** 「7 改明天」「第七項 刪除」「9改名 X」→ 編號和剩下的話；沒有編號回傳 null。 */
    private fun splitIndex(text: String): Pair<Int?, String> {
        val m = INDEX.find(text) ?: return null to text
        val n = number(m.groupValues[1].ifEmpty { m.groupValues[2] }.ifEmpty { m.groupValues[3] }) ?: return null to text
        return n to text.substring(m.range.last + 1).trim()
    }

    // ---------- 指令 ----------

    private suspend fun onCommand(msg: Message, text: String) {
        val chatId = msg.chat.id
        val cmd = text.substringBefore(' ').substringBefore('@').lowercase()
        val arg = text.substringAfter(' ', "").trim()
        when (cmd) {
            "/list" -> sendAll(chatId, arg)
            "/today" -> if (arg.isEmpty()) sendToday(chatId) else sendAll(chatId, "今天 $arg")
            "/week" -> sendWeek(chatId)
            "/done" -> completeNumbers(chatId, state.lastListed, arg, null, askFull = true)
            "/edit" -> {
                val n = arg.substringBefore(' ').toIntOrNull()
                val words = arg.substringAfter(' ', "").trim()
                val id = n?.let { state.lastListed.getOrNull(it - 1) }
                    ?: return send(chatId, "最近列的清單沒有第 ${n ?: "?"} 項。先「列清單」，再回覆那則說「${n ?: 2} 改到明天」。", replyTo = msg.messageId)
                editTasks(msg, listOf(id), words)
            }
            "/link" -> send(chatId, "已經連結好了。")
            else -> send(chatId, Replies.HELP)
        }
    }

    /** 「列清單」「今天」「完成 2」這種短句當成指令，不要變成待辦。 */
    private fun shortcut(text: String): String? {
        val t = text.replace(PUNCT, "")
        // 「列清單 工作 今天」：清單後面接條件
        val words = text.trim().split(Regex("\\s+"))
        if (words.size > 1 && text.length <= 40 && words[0].replace(PUNCT, "") in SHORTCUTS.getValue("/list")) {
            return "/list " + words.drop(1).joinToString(" ")
        }
        doneNumbers(text)?.let { return "/done $it" }
        EDIT_CMD.matchEntire(text.trim())?.let { m -> number(m.groupValues[1])?.let { return "/edit $it ${m.groupValues[2]}" } }
        if (t.length > 12) return null
        return SHORTCUTS.entries.firstOrNull { (_, words) -> t in words }?.key
    }

    private fun lookup(ctx: ParseContext) = Replies.Lookup(ctx.projects, ctx.tags, ctx.now.zone, ctx.now.toLocalDate())

    private suspend fun todayTasks(): List<Task> {
        val n = now()
        val endOfToday = n.toLocalDate().plusDays(1).atStartOfDay(n.zone).toInstant()
        return store.allTasks()
            .filter { it.status.isOpen && it.parentId == null }
            .filter { t -> (t.dueAt ?: t.remindAt)?.isBefore(endOfToday) == true }
            .sortedBy { it.dueAt ?: it.remindAt }
            .take(30)
    }

    private suspend fun sendToday(chatId: Long) {
        val tasks = todayTasks()
        state.lastListed = tasks.map { it.id }
        val l = lookup(store.parseContext(now()))
        send(chatId, Replies.today(tasks, l, now().toInstant()), keyboard = Replies.todayKeyboard(tasks))
    }

    /**
     * /list 加條件縮小範圍，可以疊加：/list 今天 工作、/list /today、/list 進行中、/list 搬家（專案）、/list 王經理（標題關鍵字）。
     */
    private suspend fun sendAll(chatId: Long, arg: String) {
        val n = now()
        val ctx = store.parseContext(n)
        val filter = ListFilter.parse(arg, ctx, n)
        val open = store.allTasks()
            .filter { it.status.isOpen && it.parentId == null && filter.matches(it) }
            // 有日期的照時間排前面，沒日期的放後面
            .sortedWith(compareBy(nullsLast()) { it.dueAt ?: it.remindAt })
        val tasks = open.take(30)
        state.lastListed = tasks.map { it.id }
        if (arg.isBlank()) state.lastFullListed = tasks.map { it.id }
        send(chatId, Replies.all(tasks, open.size, filter.label, lookup(ctx), n.toInstant()), keyboard = Replies.todayKeyboard(tasks))
    }

    private suspend fun sendWeek(chatId: Long) {
        val n = now()
        val stats = WeeklyStats.compute(
            store.allTasks(), WeeklyStats.weekStart(n.toLocalDate(), Defaults.WEEK_STARTS_ON), n.toInstant(), n.zone,
        )
        send(chatId, Replies.week(stats, lookup(store.parseContext(n))))
    }

    // ---------- 收到內容 → 整理成待辦 ----------

    private suspend fun onContent(messages: List<Message>) {
        val first = messages.first()
        val chatId = first.chat.id
        val text = messages.mapNotNull { it.text ?: it.caption }.joinToString("\n").trim()
        val origin = messages.firstNotNullOfOrNull { it.forwardOrigin }?.let { it.name ?: "轉傳訊息" }
        val refs = mutableListOf<String>()
        fun file(id: String, mime: String, size: Long?, seconds: Int = 0) =
            Organizer.Kind.of(mime)?.takeIf { (size ?: 0) <= Organizer.MAX_FILE_BYTES }
                ?.let { refs += "$mime|$id"; Organizer.Attachment(mime, it, seconds) { api.downloadFile(id) } }
        val files = messages.flatMap { m ->
            listOfNotNull(
                m.photo?.maxByOrNull { it.width.toLong() * it.height }?.let { file(it.fileId, "image/jpeg", it.fileSize) },
                m.voice?.let { file(it.fileId, it.mimeType ?: "audio/ogg", it.fileSize, it.duration) },
                m.audio?.let { file(it.fileId, it.mimeType ?: "audio/mpeg", it.fileSize) },
                m.document?.let { file(it.fileId, it.mimeType.orEmpty(), it.fileSize) },
            )
        }
        if (text.isEmpty() && files.isEmpty()) {
            send(chatId, Replies.UNSUPPORTED, replyTo = first.messageId)
            return
        }
        runCatching { api.sendTyping(chatId) }

        // Telegram 會把照片壓到長邊約 1280：很長的截圖變得很窄，字看不清楚
        val narrow = messages.mapNotNull { m -> m.photo?.maxByOrNull { it.width.toLong() * it.height } }
            .firstOrNull { it.width in 1 until 500 && it.height > it.width * 3 }
        val warning = narrow?.let { Replies.narrowScreenshot(it.width, it.height) }
        val out = organizer.organize(
            Organizer.Input(text, origin, files, SourceKind.TELEGRAM, "Telegram 的", first.messageId, chatId, refs, warning),
        )
        deliver(first, out)
    }

    /** 把整理結果回給使用者：指令就執行、語音在等就說一聲、建立了就列出來（主題沒把握時另外問）。 */
    private suspend fun deliver(origin: Message, out: Organizer.Outcome) {
        val chatId = origin.chat.id
        val inbox = out.inbox
        out.command?.let {
            // 回覆某則訊息時，Gemini 的「edit 3」「done 3」是照最近清單的編號，不是被回覆的那則，不能照做
            if (origin.replyToMessage != null && (it.startsWith("edit") || it.startsWith("done"))) {
                return send(chatId, Replies.REPLY_UNKNOWN, replyTo = origin.messageId)
            }
            return onCommand(origin, "/$it")
        }
        if (out.waiting) {
            val sent = post(chatId, Replies.VOICE_WAITING, replyTo = origin.messageId)
            store.saveInbox(inbox.copy(replyMessageId = sent.messageId))
            return
        }
        if (out.items.isEmpty()) {
            val force = inbox.text?.takeIf { it.isNotBlank() }?.let {
                InlineKeyboard(listOf(listOf(Button("還是建立成待辦", "${Callback.FORCE}:${inbox.id}"))))
            }
            send(chatId, listOfNotNull(Replies.NOTHING_FOUND, out.notice).joinToString("\n\n"), replyTo = origin.messageId, keyboard = force)
            return
        }
        val view = out.items.map { (t, subs) -> Replies.Item(t, subs) }
        val suggested = out.items.firstNotNullOfOrNull { it.first.suggestedProject }
        val sent = post(
            chatId, Replies.created(view, lookup(out.ctx), suggested, out.notice, model = inbox.model), replyTo = origin.messageId,
            keyboard = Replies.createdKeyboard(inbox.id, view),
        )
        store.saveInbox(inbox.copy(replyMessageId = sent.messageId))
        // 不知道放哪個主題：另外跳一則讓使用者點，不擠在整理結果裡
        if (out.unsureTopic.isNotEmpty()) {
            val unsure = out.unsureTopic.mapNotNull { out.items.getOrNull(it)?.first }
            sendTopicPicker(chatId, sent.messageId, unsure, out.ctx.topics, Replies.TOPIC_UNSURE)
        }
        // 跟已經有的待辦幾乎一樣：問要不要補到舊的備註
        out.duplicates.forEach { (new, old) ->
            send(
                chatId, Replies.duplicate(new, old, lookup(out.ctx)), replyTo = sent.messageId,
                keyboard = InlineKeyboard(
                    listOf(listOf(Button("📎 補到舊的備註", "${Callback.MERGE}:${new.id}:${old.id}"), Button("保留兩筆", "${Callback.KEEP}:${new.id}"))),
                ),
            )
        }
        // 只講幾點、今天已經過了：排到明天，另外問要不要改今天
        out.rolledOver.forEach { t ->
            val due = t.dueAt ?: return@forEach
            send(
                chatId, Replies.rolledOver(t, due, out.ctx.now.zone), replyTo = sent.messageId,
                keyboard = InlineKeyboard(listOf(listOf(Button("改成今天 ${Replies.clock(due, out.ctx.now.zone)}", "${Callback.BACK_TO_TODAY}:${t.id}")))),
            )
        }
    }

    private suspend fun sendTopicPicker(chatId: Long, createdMessageId: Long, tasks: List<Task>, topics: List<String>, prompt: String) {
        if (tasks.isEmpty()) return
        send(chatId, Replies.topicPicker(prompt, tasks), replyTo = createdMessageId, keyboard = Replies.topicKeyboard(tasks, topics))
    }

    // ---------- 回覆整理結果來修改 ----------

    private suspend fun onEditReply(msg: Message, botMsg: Message, inboxId: String, text: String) {
        val chatId = msg.chat.id
        val inbox = store.getInbox(inboxId) ?: return send(chatId, "找不到這則的紀錄了。", replyTo = msg.messageId)
        if (REDO.matches(text.trim())) {
            val out = organizer.redo(inbox, filesOf(inbox))
                ?: return send(chatId, Replies.REDO_FAILED, replyTo = msg.messageId)
            refreshCreated(chatId, botMsg.messageId, out.inbox, out.ctx)
            return send(chatId, if (out.items.isEmpty()) Replies.REDO_EMPTY else Replies.REDO_DONE, replyTo = msg.messageId)
        }
        editTasks(msg, inbox.taskIds, text) { ctx -> refreshCreated(chatId, botMsg.messageId, inbox, ctx) }
    }

    /**
     * 一句話改一筆待辦，回傳每個改動的確認（「✅ 已…」）；完全看不懂回傳 null。
     * 改名可以接在其他修改後面：「改16點 名稱改成 跳跳」。看不懂的部分會說出來，不默默略過。
     */
    private suspend fun applyEdit(t: Task, words: String, ctx: ParseContext): List<String>? {
        val rename = RENAME_ANY.find(words) ?: return editOne(t, words, ctx)?.let { listOf(it) }
        val name = rename.groupValues[1].trim()
        if (name.isEmpty()) return null
        store.saveTask(t.copy(title = name, source = t.source.copy(confidence = 1.0)))
        val lines = mutableListOf("✅ 已把「${t.title}」改名為「$name」")
        val before = words.substring(0, rename.range.first).trim()
        if (leftover(before).isNotEmpty()) {
            val renamed = store.getTask(t.id) ?: return lines
            lines += editOne(renamed, before, ctx) ?: "「$before」看不懂，這部分沒改。"
        }
        return lines
    }

    /** 一個修改（時間、主題、專案、狀態…）；看不懂回傳 null。 */
    private suspend fun editOne(t: Task, words: String, ctx: ParseContext): String? {
        val confirmed = t.source.copy(confidence = 1.0)
        return when {
            DELETE_WORDS.matches(words) -> { store.deleteTask(t.id); "✅ 已刪除「${t.title}」" }
            MERGE_INTO.matches(words) -> {
                val n = number(MERGE_INTO.find(words)!!.groupValues[1])
                val into = n?.let { state.lastListed.getOrNull(it - 1) }?.let { store.getTask(it) }
                    ?: return "最近列的清單沒有第 ${n ?: "?"} 項，先「列清單」再說「合併到 5」。"
                if (into.id == t.id) return "「${t.title}」就是第 $n 項。"
                merge(t, into)
            }
            POSTPONE.matches(words) -> postpone(t, POSTPONE.find(words)!!, ctx)
            REOPEN_WORDS.matches(words) -> {
                if (t.status.isOpen) return "「${t.title}」本來就還沒完成。"
                store.saveTask(t.copy(status = TaskStatus.TODO, completedAt = null))
                "✅ 已把「${t.title}」改回未完成"
            }
            DONE_WORDS.matches(words) -> { store.completeTask(t.id); "✅ 已完成「${t.title}」" }
            DOING_WORDS.matches(words) -> { store.saveTask(t.copy(status = TaskStatus.DOING)); "✅ 已把「${t.title}」改成進行中" }
            words in ctx.topics -> setTopic(t, words)
            NO_PROJECT.matches(words) -> {
                val old = t.projectId?.let { id -> ctx.projects.firstOrNull { it.id == id }?.name }
                    ?: return "「${t.title}」本來就沒有專案。"
                store.saveTask(t.copy(projectId = null, autoTagged = t.autoTagged - AutoField.PROJECT))
                "✅ 已把「${t.title}」移出專案「$old」"
            }
            projectName(words, listOf(t)) != null -> {
                val name = projectName(words, listOf(t))!!
                val p = ctx.projects.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return null
                store.saveTask(
                    t.copy(
                        projectId = p.id, topic = p.topic, suggestedProject = null,
                        autoTagged = t.autoTagged - AutoField.PROJECT - AutoField.TOPIC,
                        source = t.source.copy(confidence = 1.0),
                    ),
                )
                "✅ 已把「${t.title}」放到專案「${p.name}」"
            }
            else -> {
                val parsed = TimeParser.parse(words, ctx.now)
                var target = parsed.at
                // 只講幾點、沒講哪天：留在原本那天（還沒過的話）
                val due = t.dueAt
                if (target != null && due != null && TIME_ONLY.matches(words)) {
                    val sameDay = ZonedDateTime.of(due.atZone(ctx.now.zone).toLocalDate(), target.toLocalTime(), ctx.now.zone)
                    if (!sameDay.isBefore(ctx.now)) target = sameDay
                }
                val at = target?.toInstant()
                val topic = topicInSentence(words, ctx)
                when {
                    at != null -> {
                        store.saveTask(t.copy(dueAt = at, remindAt = at, recurrence = parsed.recurrence ?: t.recurrence, source = confirmed))
                        val rest = leftover(parsed.title.takeIf { it != words.trim() }.orEmpty())
                        "✅ 已把「${t.title}」改到 ${Replies.dateTime(at, ctx.now.zone, ctx.now.toLocalDate())}" +
                            if (rest.isNotEmpty()) "\n「$rest」看不懂，這部分沒改。" else ""
                    }
                    topic != null -> setTopic(t, topic)
                    WAITING_WORDS.matches(words) -> {
                        val who = WAITING_WORDS.find(words)!!.groupValues[1].trim().ifEmpty { null }
                        store.saveTask(t.copy(status = TaskStatus.WAITING, waitingFor = who ?: t.waitingFor))
                        "✅ 已把「${t.title}」改成等待${who?.let { "（$it）" } ?: "他人"}"
                    }
                    RENAME.matches(words) -> {
                        val name = RENAME.find(words)!!.groupValues[1].trim()
                        store.saveTask(t.copy(title = name, source = confirmed))
                        "✅ 已把「${t.title}」改名為「$name」"
                    }
                    else -> null
                }
            }
        }
    }

    /** 把 [new] 補到 [old] 的備註，刪掉 [new]（子任務一起）。 */
    /** 「延期一週」：到期和提醒都從原本的時間往後移；沒有時間就從現在算。已完成的順便改回未完成。 */
    private fun postpone(t: Task, m: MatchResult, ctx: ParseContext): String {
        val n = m.groupValues[1].ifEmpty { "1" }.let { number(it) } ?: 1
        val unit = m.groupValues[2]
        val zone = ctx.now.zone
        fun shift(at: java.time.Instant): java.time.Instant = at.atZone(zone).let {
            when (unit) {
                "天", "日" -> it.plusDays(n.toLong())
                "月" -> it.plusMonths(n.toLong())
                "小時", "鐘頭" -> it.plusHours(n.toLong())
                "分鐘" -> it.plusMinutes(n.toLong())
                else -> it.plusWeeks(n.toLong())
            }
        }.toInstant()
        val base = t.dueAt ?: t.remindAt ?: ctx.now.toInstant()
        val due = shift(base)
        val remind = t.remindAt?.let { shift(it) } ?: due
        val reopened = !t.status.isOpen
        store.saveTask(
            t.copy(
                dueAt = due, remindAt = remind, source = t.source.copy(confidence = 1.0),
                status = if (reopened) TaskStatus.TODO else t.status, completedAt = if (reopened) null else t.completedAt,
            ),
        )
        val label = when (unit) { "週", "周", "星期", "禮拜" -> "週"; "月" -> "個月"; "鐘頭" -> "小時"; else -> unit }
        return "✅ 已把「${t.title}」延後 $n $label，改到 ${Replies.dateTime(due, zone, ctx.now.toLocalDate())}" +
            if (reopened) "（也改回未完成）" else ""
    }

    private suspend fun merge(new: Task, old: Task): String {
        val day = Replies.date(now().toLocalDate())
        val add = listOfNotNull(new.title, new.notes).joinToString("\n")
        val notes = listOfNotNull(old.notes, "補充（$day）：$add").joinToString("\n")
        store.saveTask(old.copy(notes = notes))
        store.deleteTask(new.id)
        return "✅ 已把「${new.title}」補到「${old.title}」的備註，新的那筆刪掉了"
    }

    /** 拿掉「改」「到」「這個」「會議」這類不影響意思的字，剩下的才算沒看懂。 */
    private fun leftover(s: String): String = s.replace(FILLER, "").replace(PUNCT, " ").trim()

    /** 「@ICARD」「放到新專案 ICARD」「開專案」：專案還沒有就先建立（主題跟著第一筆待辦），多筆一起改只建一次。 */
    private fun withProject(words: String, ctx: ParseContext, tasks: List<Task>): ParseContext {
        val name = projectName(words, tasks) ?: return ctx
        if (ctx.projects.any { it.name.equals(name, ignoreCase = true) }) return ctx
        val topic = tasks.firstOrNull()?.topic ?: Defaults.DEFAULT_TOPIC
        val id = store.createProject(name, topic)
        return ctx.copy(projects = ctx.projects + Project(id = id, name = name, topic = topic))
    }

    private fun projectName(words: String, tasks: List<Task>): String? {
        val name = PROJECT_SET.find(words)?.groupValues?.get(1)
            ?: AT_PROJECT.find(words)?.groupValues?.get(1)
            ?: if (OPEN_SUGGESTED.matches(words)) tasks.firstNotNullOfOrNull { it.suggestedProject } else null
        return name?.trim()?.takeIf { it.isNotEmpty() && it.length <= 30 }
    }

    private fun setTopic(t: Task, topic: String): String {
        store.saveTask(t.copy(topic = topic, autoTagged = t.autoTagged - AutoField.TOPIC, source = t.source.copy(confidence = 1.0)))
        brain.learnTopic(t.title, topic)
        return "✅ 已把「${t.title}」改到〔$topic〕"
    }

    /** 「這是工作的事」「改成工作」「分到工作」：短句裡只提到一個主題。 */
    private fun topicInSentence(words: String, ctx: ParseContext): String? {
        if (words.length > 15 || words.contains("不是")) return null
        return ctx.topics.filter { words.contains(it) }.singleOrNull()
    }

    private suspend fun refreshCreated(chatId: Long, messageId: Long, inbox: InboxEntry, ctx: ParseContext) {
        val items = inbox.taskIds.map { id ->
            val t = store.getTask(id)
            Replies.Item(t, if (t == null) emptyList() else store.children(id))
        }
        val suggested = items.firstNotNullOfOrNull { it.task?.suggestedProject }
        runCatching {
            api.editMessageText(
                chatId, messageId, Replies.created(items, lookup(ctx), suggested, null, edited = true, model = inbox.model),
                Replies.createdKeyboard(inbox.id, items),
            )
        }.onFailure { log("更新整理結果訊息失敗", it) }
    }

    // ---------- 按鈕 ----------

    private suspend fun onCallback(cb: CallbackQuery) {
        val msg = cb.message
        if (msg == null || msg.chat.id != state.linkedChatId) return
        val chatId = msg.chat.id
        val parts = cb.data.orEmpty().split(":")
        var answer: String? = null
        when (parts.firstOrNull()) {
            in Callback.REMIND_ACTIONS -> {
                parts.getOrNull(1)?.let { answer = onReminderButton(chatId, msg.messageId, parts[0], it, msg.replyMarkup) }
            }
            Callback.DELETE -> {
                val inbox = parts.getOrNull(1)?.let { store.getInbox(it) }
                val id = parts.getOrNull(2)?.toIntOrNull()?.let { inbox?.taskIds?.getOrNull(it) }
                if (inbox != null && id != null) {
                    val t = store.getTask(id)
                    if (t != null) store.deleteTask(id)
                    answer = "已刪除"
                    refreshCreated(chatId, msg.messageId, inbox, store.parseContext(now()))
                }
            }
            Callback.UNDO -> {
                val inbox = parts.getOrNull(1)?.let { store.getInbox(it) }
                if (inbox != null) {
                    inbox.taskIds.forEach { if (store.getTask(it) != null) store.deleteTask(it) }
                    answer = "已全部取消"
                    runCatching { api.editMessageText(chatId, msg.messageId, "已全部取消（${inbox.taskIds.size} 項）。") }
                }
            }
            Callback.FORCE -> {
                val inbox = parts.getOrNull(1)?.let { store.getInbox(it) }
                if (inbox != null && inbox.taskIds.isEmpty()) {
                    val ctx = store.parseContext(now())
                    val raw = inbox.text.orEmpty()
                    val firstLine = raw.lines().firstOrNull { it.isNotBlank() }?.trim()
                    val parsed = firstLine?.let { TimeParser.parse(it, ctx.now) }
                    val at = parsed?.at?.toInstant()
                    val p = ParsedTask(
                        title = parsed?.title?.take(40) ?: "看 Telegram 訊息",
                        notes = raw.takeIf { it.length > (firstLine?.length ?: 0) },
                        dueAt = at, remindAt = at,
                        classification = brain.rules.classify(raw.ifEmpty { "訊息" }, null, ctx),
                        confidence = 0.5,
                    )
                    val (task, _) = organizer.build(p, TaskSource(SourceKind.TELEGRAM, inbox.id), inbox.forwardFrom, null)
                    store.createTask(task)
                    val updated = inbox.copy(status = InboxEntry.PARSED, taskIds = listOf(task.id))
                    store.saveInbox(updated)
                    answer = "已建立"
                    refreshCreated(chatId, msg.messageId, updated, ctx)
                    if (p.classification.topic == null && brain.hints().guess(raw, ctx.topics) == null) {
                        sendTopicPicker(chatId, msg.messageId, listOf(task), ctx.topics, Replies.TOPIC_UNSURE)
                    }
                }
            }
            Callback.DONE_FULL -> {
                val arg = parts.getOrNull(1).orEmpty().replace('.', ' ')
                runCatching { api.editMessageText(chatId, msg.messageId, "照完整清單完成 ${arg.replace(' ', '、')}：") }
                completeNumbers(chatId, state.lastFullListed, arg, msg.messageId, askFull = false)
                answer = "已完成"
            }
            Callback.MERGE -> {
                val new = parts.getOrNull(1)?.let { store.getTask(it) }
                val old = parts.getOrNull(2)?.let { store.getTask(it) }
                if (new != null && old != null) {
                    val line = merge(new, old)
                    runCatching { api.editMessageText(chatId, msg.messageId, line) }
                    state.rememberMessage(msg.messageId, listOf(old.id))
                    answer = "已補到備註"
                    msg.replyToMessage?.let { created ->
                        Callback.inboxIdOf(created.replyMarkup)?.let { id -> store.getInbox(id) }
                            ?.let { refreshCreated(chatId, created.messageId, it, store.parseContext(now())) }
                    }
                }
            }
            Callback.KEEP -> {
                runCatching { api.editMessageText(chatId, msg.messageId, "好，兩筆都留著。") }
            }
            Callback.BACK_TO_TODAY -> {
                val t = parts.getOrNull(1)?.let { store.getTask(it) }
                val due = t?.dueAt
                if (t != null && due != null) {
                    val back = due.minus(java.time.Duration.ofDays(1))
                    store.saveTask(t.copy(dueAt = back, remindAt = t.remindAt?.minus(java.time.Duration.ofDays(1)) ?: back))
                    val ctx = store.parseContext(now())
                    val line = "✅ 已把「${t.title}」改到 ${Replies.dateTime(back, ctx.now.zone, ctx.now.toLocalDate())}"
                    runCatching { api.editMessageText(chatId, msg.messageId, line) }
                    state.rememberMessage(msg.messageId, listOf(t.id))
                    answer = "已改成今天"
                    msg.replyToMessage?.let { created ->
                        Callback.inboxIdOf(created.replyMarkup)?.let { id -> store.getInbox(id) }
                            ?.let { refreshCreated(chatId, created.messageId, it, ctx) }
                    }
                }
            }
            Callback.TOPIC_PICK -> {
                val inbox = parts.getOrNull(1)?.let { store.getInbox(it) }
                val tasks = inbox?.taskIds?.mapNotNull { store.getTask(it) }.orEmpty()
                sendTopicPicker(chatId, msg.messageId, tasks, store.parseContext(now()).topics, Replies.TOPIC_PROMPT)
            }
            Callback.TOPIC_SET -> {
                val t = parts.getOrNull(1)?.let { store.getTask(it) }
                val ctx = store.parseContext(now())
                val topic = parts.getOrNull(2)?.toIntOrNull()?.let { ctx.topics.getOrNull(it) }
                if (t != null && topic != null) {
                    answer = setTopic(t, topic)
                    // 選單上列的待辦換成新主題；只有一筆就收掉按鈕
                    val ids = Callback.topicTaskIdsOf(msg.replyMarkup)
                    val tasks = ids.mapNotNull { store.getTask(it) }
                    val prompt = msg.text?.substringBefore('\n')?.takeIf { it.isNotBlank() } ?: Replies.TOPIC_PROMPT
                    runCatching {
                        api.editMessageText(
                            chatId, msg.messageId, Replies.topicPicker(prompt, tasks),
                            if (tasks.size > 1) Replies.topicKeyboard(tasks, ctx.topics) else null,
                        )
                    }
                    // 整理結果那則也更新
                    msg.replyToMessage?.let { created ->
                        Callback.inboxIdOf(created.replyMarkup)?.let { id -> store.getInbox(id) }
                            ?.let { refreshCreated(chatId, created.messageId, it, ctx) }
                    }
                }
            }
            Callback.COMPLETE -> {
                val id = parts.getOrNull(1)
                val t = id?.let { store.getTask(it) }
                if (t != null && t.status.isOpen) {
                    store.completeTask(t.id)
                    answer = "已完成：${t.title}"
                }
                val ids = Callback.completeIdsOf(msg.replyMarkup)
                val tasks = ids.mapNotNull { tid ->
                    val task = store.getTask(tid)
                    if (tid == id && task != null) task.copy(status = TaskStatus.DONE) else task
                }
                val ctx = store.parseContext(now())
                runCatching {
                    val l = lookup(ctx)
                    val header = msg.text?.substringBefore('\n')?.takeIf { it.isNotBlank() }
                    val text = if (header == null) Replies.today(tasks, l, now().toInstant()) else Replies.numbered(header, tasks, l, now().toInstant())
                    api.editMessageText(chatId, msg.messageId, text, Replies.todayKeyboard(tasks))
                }
                state.lastListed = tasks.map { it.id }
            }
        }
        runCatching { api.answerCallbackQuery(cb.id, answer) }
    }

    // ---------- Gemini 恢復後重新整理 ----------

    /**
     * Gemini 忙線時先用規則建立（或語音先等著）的那些，現在再整理一次：
     * 語音就正式建立並回覆；已建立的只更新系統自己選的分類，再更新原本那則整理結果。
     */
    suspend fun retryPending(max: Int = 5) {
        if (brain.gemini() == null) return
        for (entry in store.pendingInbox().take(max)) {
            val files = filesOf(entry)
            val out = try {
                organizer.retry(entry, files)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                log("重新整理失敗", e)
                null
            }
            if (out == null) {
                giveUpOrWait(entry)
                // Gemini 還是不行，這輪先停
                return
            }
            val chatId = entry.tgChatId.takeIf { it != 0L && it == state.linkedChatId } ?: continue
            if (entry.status == InboxEntry.PENDING) {
                val origin = Message(messageId = entry.tgMessageId, chat = Chat(chatId, "private"))
                deliver(origin, out)
                if (entry.replyMessageId != 0L) runCatching { api.deleteMessage(chatId, entry.replyMessageId) }
            } else if (entry.replyMessageId != 0L) {
                refreshCreated(chatId, entry.replyMessageId, out.inbox, out.ctx)
            }
        }
    }

    /** 從 inbox 記下的附件重新下載。 */
    private fun filesOf(entry: InboxEntry) = entry.fileRefs.mapNotNull { ref ->
        val mime = ref.substringBefore('|')
        val id = ref.substringAfter('|')
        Organizer.Kind.of(mime)?.let { Organizer.Attachment(mime, it) { api.downloadFile(id) } }
    }

    private suspend fun giveUpOrWait(entry: InboxEntry) {
        val tries = entry.retries + 1
        if (tries < MAX_RETRIES) {
            store.saveInbox(entry.copy(retries = tries))
            return
        }
        store.saveInbox(entry.copy(retries = tries, retryAi = false, status = if (entry.status == InboxEntry.PENDING) InboxEntry.FAILED else entry.status))
        val chatId = entry.tgChatId.takeIf { it != 0L && it == state.linkedChatId } ?: return
        if (entry.status == InboxEntry.PENDING) {
            runCatching { send(chatId, Replies.VOICE_GAVE_UP, replyTo = entry.tgMessageId) }
        }
    }

    /** 處理失敗時告訴使用者，不要讓訊息石沉大海。 */
    private suspend fun tellFailure(batch: List<Update>, e: Exception) {
        val chatId = batch.firstNotNullOfOrNull { it.message?.chat?.id ?: it.callbackQuery?.message?.chat?.id }
        if (chatId == null || chatId != state.linkedChatId) return
        val replyTo = batch.firstNotNullOfOrNull { it.message?.messageId }
        runCatching { send(chatId, Replies.failed(e.message ?: e.javaClass.simpleName), replyTo) }
    }

    private suspend fun send(chatId: Long, text: String, replyTo: Long? = null, keyboard: InlineKeyboard? = null) {
        post(chatId, text, replyTo, keyboard)
    }

    /** 和 [send] 一樣，但回傳送出的訊息（要記下 message_id 的時候用）。 */
    private suspend fun post(chatId: Long, text: String, replyTo: Long? = null, keyboard: InlineKeyboard? = null): Message =
        api.sendMessage(chatId, text, replyTo, keyboard)

    companion object {
        /** Gemini 一直忙線時最多重試幾輪（大約每 3 分鐘一輪）。 */
        private const val MAX_RETRIES = 40
        private val LINK = Regex("^/(?:link|start)(?:@\\w+)?\\s+(\\d{6})\\s*$")
        /** 「2 改成明天」「第2項 刪除」；「10點」「10/8」不算編號。 */
        private const val EDIT_WORD = "(?:改|設|名稱|標題|名字|取消完成|還沒|未完成|刪|移出|放到|放進|進行中|完成|@|＠|#|＃)"
        private val INDEX = Regex(
            "^(?:第\\s*([0-9]{1,2}|[一二兩三四五六七八九十]{1,3})\\s*[項个個筆]?|([0-9]{1,2})(?:\\s*[項個筆.、．:：]|\\s+(?![點点時號日月分半:：/])|(?=$EDIT_WORD))|" +
                "([一二兩三四五六七八九十]{1,3})\\s*[項個筆])\\s*",
        )
        /** 沒回覆時「第7項設定為早上」「9改名 X」：改最近清單上的那一項。 */
        private val EDIT_CMD = Regex("^(?:第\\s*)?([0-9]{1,2}|[一二兩三四五六七八九十]{1,3})\\s*[項個筆]?\\s*[.、．:：]?\\s*($EDIT_WORD.*)$")
        /** 沒回覆任何訊息卻講「改名」「改成」：先問是要改哪一筆還是新增。 */
        private val MODIFY_HINT = Regex("改名|名稱改|標題改|改成|改為|更改")
        /** 「合併到 5」「補到第5項」：把這筆補到最近清單第 5 項的備註。 */
        private val MERGE_INTO = Regex("^(?:合併到|合併進|併到|併入|補到|補進)\\s*(?:第\\s*)?([0-9]{1,2}|[一二兩三四五六七八九十]{1,3})\\s*[項個筆]?(?:的?備註)?[。!！]?$")
        /** 「調整為」「改回」「標記為」這類前面的動詞。 */
        private const val SET_TO = "(?:(?:狀態)?(?:調整|改|設定?|變|切換|標記)(?:為|成|回|回去)?\\s*)?"
        private val REOPEN_WORDS = Regex("^$SET_TO(?:取消完成|還沒做完|還沒完成|還沒做|沒做完|未完成|待辦|重新打開|復原)[。!！]?$")
        /** 「延期一週」「延後3天」「順延兩小時」：從原本的時間往後算。 */
        private val POSTPONE = Regex(
            "^(?:延期|延後|順延|往後延|往後推|推遲|延)\\s*([0-9]{1,2}|[一二兩三四五六七八九十]{1,3})?\\s*個?\\s*(天|日|週|周|星期|禮拜|月|小時|鐘頭|分鐘)[。!！]?$",
        )
        /** 改名可以接在其他修改後面：「改名 X」「名稱改成 X」「會議名稱 跳跳」。 */
        private val RENAME_ANY = Regex(
            "(?:改名(?:為|成)?|重新命名(?:為)?|(?:名稱|標題|名字)\\s*(?:改成|改為|換成|改|為|是)?)[：:\\s]*[「『\"“]?(.+?)[」』\"”]?\\s*[。!！]?$",
        )
        /** 修改句裡不影響意思的字。 */
        private val FILLER = Regex("改成|改到|改為|設定為|設定成|設為|設成|設定|調整|改|到|成|為|在|這個|這筆|這項|它|會議|待辦|事項|項目|活動|的|和|跟|並且|然後|也")
        private val ALL = Regex("^全部都?")
        private val DELETE_WORDS = Regex("^(?:刪除|刪掉|删除|删掉|取消|不用了|不要了|移除)[。!！]?$")
        private val DONE_WORDS = Regex("^$SET_TO(?:完成|做完了?|好了|已完成|done)[。!！]?$", RegexOption.IGNORE_CASE)
        private val DOING_WORDS = Regex("^(?:進行中|開始|開始做|在做了?|處理中)[。!！]?$")
        /** 「等王經理」「等待回覆」「等待他人」。 */
        private val WAITING_WORDS = Regex("^(?:等待|等)(?:他人)?(.*?)(?:回覆|回應)?[。!！]?$")
        private const val EDIT_HELP = "看不懂要怎麼改。可以說「改成週五下午3點」「家庭」「改名 新標題」「@專案」「放到新專案 名稱」「進行中」「等王經理」「刪除」「完成」。"
        private val PUNCT = Regex("[\\s。.,，!！?？~～]+")
        private const val DONE_WORD = "(?:完成|做完了?|搞定)"
        private const val NUMS = "[第0-9一二兩三四五六七八九十項個、,，\\s和跟及與]+"
        private val DONE_BEFORE = Regex("^\\s*$DONE_WORD\\s*($NUMS)\\s*[。!！]?$")
        private val DONE_AFTER = Regex("^\\s*($NUMS?)\\s*都?${DONE_WORD}了?\\s*[。!！]?$")
        private val DONE_ALL = Regex("^\\s*(?:全部|通通|全都)\\s*都?${DONE_WORD}了?\\s*[。!！]?$")
        private val NUMBER = Regex("[0-9]+|[一二兩三四五六七八九十]+")
        private const val CN = "零一二三四五六七八九"

        /** 「第一項第二項完成」「完成 1、3」「1 2 完成」→ "1 2"；「全部完成」→ "all"。不是的話回傳 null。 */
        internal fun doneNumbers(text: String): String? {
            if (DONE_ALL.matches(text)) return "all"
            val m = DONE_BEFORE.matchEntire(text) ?: DONE_AFTER.matchEntire(text) ?: return null
            val ns = NUMBER.findAll(m.groupValues[1]).mapNotNull { number(it.value) }.filter { it in 1..99 }.toList()
            return ns.takeIf { it.isNotEmpty() }?.joinToString(" ")
        }

        /** 1–99 的阿拉伯或中文數字（兩＝二）。 */
        private fun number(s: String): Int? {
            s.toIntOrNull()?.let { return it }
            val t = s.replace('兩', '二')
            fun d(c: Char) = CN.indexOf(c).takeIf { it >= 0 }
            return when {
                t == "十" -> 10
                t.length == 1 -> d(t[0])
                t.length == 2 && t[0] == '十' -> d(t[1])?.let { 10 + it }
                t.length == 2 && t[1] == '十' -> d(t[0])?.let { it * 10 }
                t.length == 3 && t[1] == '十' -> d(t[0])?.let { a -> d(t[2])?.let { a * 10 + it } }
                else -> null
            }
        }
        private val SHORTCUTS = mapOf(
            "/list" to setOf(
                "列清單", "清單", "列表", "待辦", "待辦清單", "待辦事項", "目前待辦", "所有待辦", "全部待辦",
                "列出待辦", "有什麼待辦", "還有什麼", "還有什麼事", "還有哪些", "我有什麼事",
            ),
            "/today" to setOf("今天", "今日", "今天待辦", "今天的待辦", "今天要做什麼", "今天有什麼", "今天有什麼事"),
            "/week" to setOf("本週", "本周", "這週", "這周", "週報", "本週統計", "這週統計", "統計"),
            "/help" to setOf("說明", "幫助", "怎麼用", "指令", "help"),
        )

        private fun editHelp(words: String): String {
            val unclear = TimeParser.ambiguousNumbers(words).firstOrNull() ?: return EDIT_HELP
            return "「$unclear」看不出是日期還是時間。日期請打 11/30、8 碼西元 20261130 或 7 碼民國 1151130；時間請打 4 碼 1130 或 11:30。"
        }
        private val TIME_ONLY = Regex(
            "^(?:改|設定|設|調整)?(?:成|到|為|为|在)?\\s*(?:(?:早上|上午|中午|下午|傍晚|晚上)\\s*" +
                "(?:[0-9]{1,2}[:：][0-9]{2}|[0-9]{4}|[0-9一二兩三四五六七八九十]+\\s*(?:點|点|時)(?:\\s*(?:半|整|[0-9一二兩三四五六七八九十]+\\s*分?))?)?|" +
                "(?:[0-9]{1,2}[:：][0-9]{2}|[0-9]{4}|[0-9一二兩三四五六七八九十]+\\s*(?:點|点|時)(?:\\s*(?:半|整|[0-9一二兩三四五六七八九十]+\\s*分?))?))\\s*$",
        )
        /** 最後才當改名：「改成 新標題」（時間、主題都對不上時）。 */
        private val RENAME = Regex("^(?:改成|改為)[：:\\s]*(.+)$")
        /** 「放到新專案 ICARD」「這則放到專案「搬家」」「專案 ICARD」；「專案會議改明天」不算。 */
        private val PROJECT_SET = Regex(
            "^(?:這則|這項|這個|這些|都)?\\s*(?:(?:放到|放進|移到|移進|加到|加入|歸到|歸入|改到|分到|丟到)\\s*新?專案|新專案|專案(?=[\\s：:「『\"“]))" +
                "\\s*(?:叫做|叫|是|為|[：:])?\\s*[「『\"“]?(.+?)[」』\"”]?\\s*[。!！]?$",
        )
        /** 整句只有「@ICARD」。 */
        private val AT_PROJECT = Regex("^[@＠]\\s*([^\\s@＠].*?)\\s*$")
        /** 照建議開專案。 */
        private val OPEN_SUGGESTED = Regex("^(?:開|建立|新增|建|開新)專案[。!！]?$")
        /** 用強模型重新整理一則。 */
        private val REDO = Regex("^(?:重整|重新整理|用強模型重整|用強模型重新整理)[。!！]?$")
        private val NO_PROJECT = Regex("^(?:無專案|不要專案|移出專案|拿掉專案|取消專案)[。!！]?$")
    }
}
