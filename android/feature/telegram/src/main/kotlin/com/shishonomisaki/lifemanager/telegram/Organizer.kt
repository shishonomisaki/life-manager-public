package com.shishonomisaki.lifemanager.telegram

import com.shishonomisaki.lifemanager.core.model.AutoField
import com.shishonomisaki.lifemanager.core.model.Defaults
import com.shishonomisaki.lifemanager.core.model.Project
import com.shishonomisaki.lifemanager.core.model.SourceKind
import com.shishonomisaki.lifemanager.core.model.Task
import com.shishonomisaki.lifemanager.core.model.TaskSource
import com.shishonomisaki.lifemanager.core.parse.Classification
import com.shishonomisaki.lifemanager.core.parse.GeminiTaskParser
import com.shishonomisaki.lifemanager.core.parse.ModelRouting
import com.shishonomisaki.lifemanager.core.parse.ParseContext
import com.shishonomisaki.lifemanager.core.parse.ParseResult
import com.shishonomisaki.lifemanager.core.parse.ParsedTask
import com.shishonomisaki.lifemanager.core.parse.QuotaExceededException
import com.shishonomisaki.lifemanager.core.parse.TimeParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException
import java.time.ZonedDateTime
import java.util.Base64

/**
 * 收件匣的整理流程：一則內容（文字、轉傳來源、截圖、語音、PDF）→ 待辦＋子任務，存進資料庫並記一筆 inbox。
 * Telegram Bot 和「分享給 APP」共用。
 */
class Organizer(
    private val store: InboxStore,
    private val brain: Brain,
    private val now: () -> ZonedDateTime = { ZonedDateTime.now(Defaults.ZONE) },
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
    private val retryDelayMs: Long = 2_000,
) {

    enum class Kind {
        PHOTO, VOICE, PDF;

        companion object {
            fun of(mime: String): Kind? = when {
                mime.startsWith("image/") -> PHOTO
                mime.startsWith("audio/") -> VOICE
                mime == "application/pdf" -> PDF
                else -> null
            }
        }
    }

    /** 附件，內容用到時才讀（沒有 Gemini 又不是圖片就不必下載）。 */
    class Attachment(val mime: String, val kind: Kind, val seconds: Int = 0, val load: suspend () -> ByteArray)

    data class Input(
        val text: String,
        val origin: String?,
        val files: List<Attachment>,
        val sourceKind: SourceKind,
        /** 放進「看 ___語音訊息」這種備用標題，例如「Telegram 的」「分享的」。 */
        val sourceLabel: String,
        val tgMessageId: Long = 0,
        val tgChatId: Long = 0,
        /** Telegram 附件「mime|fileId」，Gemini 忙線時留著之後重新下載。 */
        val fileRefs: List<String> = emptyList(),
        /** 附在整理結果上的提醒，例如長截圖被壓得太窄。 */
        val warning: String? = null,
    )

    data class Outcome(
        val inbox: InboxEntry,
        val items: List<Pair<Task, List<Task>>>,
        /** 改用規則版時的說明，例如 Gemini 額度用完。 */
        val notice: String?,
        val ctx: ParseContext,
        /** 內容其實是指令（Gemini 聽語音判斷的），例如 list、done 1 2。 */
        val command: String? = null,
        /** 不知道該放哪個主題、先放預設主題的項目（從 0 算）。 */
        val unsureTopic: List<Int> = emptyList(),
        /** 只講幾點、今天那時間已過所以排到明天的待辦，要問使用者。 */
        val rolledOver: List<Task> = emptyList(),
        /** 新建的（first）和已經有的未完成待辦（second）幾乎一樣，要問要不要補到舊的備註。 */
        val duplicates: List<Pair<Task, Task>> = emptyList(),
    ) {
        /** 語音收到了但 Gemini 忙線，等恢復再聽。 */
        val waiting: Boolean get() = inbox.status == InboxEntry.PENDING
    }

    /** Gemini 這次的結果：成功、額度用完、暫時失敗（之後可以重試）、其他錯誤。 */
    private sealed interface Ai {
        /** [model]：lite、strong:原因（見 [ModelRouting]）。 */
        data class Ok(val result: ParseResult, val model: String = "lite") : Ai
        data object Quota : Ai
        data class Busy(val reason: String) : Ai
        data class Failed(val reason: String) : Ai
        data object Off : Ai
    }

    /**
     * 平常用 Lite；內容複雜（截圖、長文、長語音）直接用強模型，Lite 失敗或結果不可靠再升級重做一次。
     * [background]：背景補分類，一律只用平常的模型。[force]：使用者要求「重整」，直接用強模型。
     */
    private suspend fun askGemini(
        text: String, input: Input, ctx: ParseContext, allowed: Boolean,
        topicGiven: Boolean = false, background: Boolean = false, force: Boolean = false,
    ): Ai {
        val lite = (if (allowed) brain.gemini() else null) ?: return Ai.Off
        val voice = input.files.filter { it.kind == Kind.VOICE }.sumOf { it.seconds }
        val hard = if (background) null else ModelRouting.upfront(text, input.files.any { it.kind == Kind.PHOTO }, voice)
        if (force || hard != null) {
            strong(text, input, ctx, auto = !force)?.let { return Ai.Ok(it, "strong:" + (hard ?: "manual")) }
            if (force) return Ai.Failed("強模型現在不能用（沒設定、今天額度用完或連不上）")
        }
        val first = askOne(lite, text, input, ctx, retryOnBusy = background || brain.geminiStrong() == null)
        if (background) return first
        val why = when (first) {
            is Ai.Ok -> ModelRouting.recheck(text, first.result, now(), topicGiven) ?: return first
            is Ai.Busy, Ai.Quota -> "B1"
            is Ai.Failed -> if (first.reason.startsWith("回傳內容")) "B1" else return first
            Ai.Off -> return first
        }
        val better = strong(text, input, ctx, auto = true) ?: return (first as? Ai.Ok)?.copy(model = "lite:$why") ?: first
        return Ai.Ok(better, "strong:$why")
    }

    /** 用強模型做一次；不能用或失敗回傳 null（呼叫端保留原本的結果）。 */
    private suspend fun strong(text: String, input: Input, ctx: ParseContext, auto: Boolean): ParseResult? {
        val gemini = brain.geminiStrong(auto) ?: return null
        brain.onStrongUsed()
        return try {
            parseWithGemini(gemini, text, input.origin, input.files, ctx)
        } catch (e: CancellationException) {
            throw e
        } catch (e: QuotaExceededException) {
            brain.onStrongQuotaExceeded()
            null
        } catch (e: Exception) {
            log("強模型失敗", e)
            null
        }
    }

    private suspend fun askOne(gemini: GeminiTaskParser, text: String, input: Input, ctx: ParseContext, retryOnBusy: Boolean): Ai {
        return try {
            Ai.Ok(
                try {
                    parseWithGemini(gemini, text, input.origin, input.files, ctx)
                } catch (e: Exception) {
                    // 沒有強模型可以升級時：忙線（5xx）或網路不穩就同一個再試一次
                    if (!retryOnBusy || e is CancellationException || e is QuotaExceededException || !retryable(e)) throw e
                    log("Gemini 第一次失敗，再試一次", e)
                    delay(retryDelayMs)
                    try {
                        parseWithGemini(gemini, text, input.origin, input.files, ctx)
                    } catch (again: Exception) {
                        if (again is CancellationException || again is QuotaExceededException) throw again
                        log("再試也失敗", again)
                        throw e
                    }
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: QuotaExceededException) {
            brain.onQuotaExceeded()
            Ai.Quota
        } catch (e: Exception) {
            log("Gemini 整理失敗", e)
            if (retryable(e)) Ai.Busy(geminiReason(e)) else Ai.Failed(geminiReason(e))
        }
    }

    suspend fun organize(input: Input): Outcome {
        var ctx = store.parseContext(now())
        var directives = Directives.extract(input.text.trim(), ctx)
        val text = directives.text
        val hints = brain.hints()
        val hintTopic = directives.topic ?: if (text.isNotEmpty()) brain.rules.classify(text, null, ctx).topic else null
        // 「@新名稱」：還沒有這個專案就建立（轉傳的訊息裡的 @帳號 不算）
        val newProject = directives.newProject
        if (newProject != null && input.origin == null) {
            val topic = hintTopic ?: hints.guess(text, ctx.topics) ?: Defaults.DEFAULT_TOPIC
            val id = store.createProject(newProject, topic)
            ctx = ctx.copy(projects = ctx.projects + Project(id = id, name = newProject, topic = topic))
            directives = directives.copy(projectId = id, topic = directives.topic ?: topic)
        }
        val aiCtx = ctx.copy(guidance = hints.guidance())
        val ai = askGemini(text, input, aiCtx, allowed = hintTopic == null || !brain.isPrivate(hintTopic), topicGiven = directives.topic != null)
        val inboxId = store.newInboxId()
        val voiceOnly = text.isEmpty() && input.files.isNotEmpty() && input.files.all { it.kind == Kind.VOICE }
        val base = InboxEntry(
            id = inboxId, kind = kindOf(input), text = text.ifEmpty { null }, forwardFrom = input.origin,
            tgMessageId = input.tgMessageId, tgChatId = input.tgChatId,
            status = InboxEntry.PARSED, taskIds = emptyList(), fileRefs = input.fileRefs,
        )

        // 語音聽不了又沒文字：建一筆空的待辦沒意義，等 Gemini 恢復再聽
        if (ai is Ai.Busy && voiceOnly && input.fileRefs.isNotEmpty()) {
            val inbox = base.copy(status = InboxEntry.PENDING, retryAi = true)
            store.saveInbox(inbox)
            return Outcome(inbox, emptyList(), null, ctx)
        }
        val unclear = TimeParser.ambiguousNumbers(text).takeIf { it.isNotEmpty() }?.let { nums ->
            "「${nums.joinToString("」「")}」看不出是日期還是時間，沒有設定。要設的話回覆這則，例如「2025/11/30」「11:30」，或 8 碼西元 20251130、7 碼民國 1141130、4 碼時間 1130。"
        }
        val aiNotice = when (ai) {
            is Ai.Quota -> "（Gemini 今天的免費額度用完了，這則先用內建規則整理。）"
            is Ai.Busy -> "（Gemini 忙線（${ai.reason}），這則先用內建規則整理，恢復後會自動重新分類。）"
            is Ai.Failed -> "（Gemini 沒成功：${ai.reason}。這則先用內建規則整理。）"
            else -> null
        }
        val notice = listOfNotNull(input.warning, unclear, aiNotice).joinToString("\n\n").ifEmpty { null }
        val result = (ai as? Ai.Ok)?.result ?: fallback(text, input, ctx)
        val inbox = base.copy(
            text = text.ifEmpty { result.heard },
            retryAi = ai is Ai.Busy && (text.isNotEmpty() || input.fileRefs.isNotEmpty()),
            model = (ai as? Ai.Ok)?.model ?: "rules",
        )
        if (result.command != null && result.tasks.isEmpty()) {
            val ignored = inbox.copy(status = InboxEntry.IGNORED)
            store.saveInbox(ignored)
            return Outcome(ignored, emptyList(), notice, ctx, command = result.command)
        }
        return create(result, inbox, input.origin, input.sourceKind, directives, hints, ctx, notice)
    }

    /** 把整理結果存成待辦，套上使用者指定的和以前改過的分類。 */
    private fun create(
        result: ParseResult, inbox: InboxEntry, origin: String?, kind: SourceKind,
        directives: Directives, hints: TopicHints, ctx: ParseContext, notice: String?,
    ): Outcome {
        val source = TaskSource(kind, inbox.id)
        val parsed = result.tasks.filter { it.title.isNotBlank() }
        val withSubtasks = parsed.count { it.subtasks.isNotEmpty() }
        val unsure = mutableListOf<Int>()
        val items = parsed.mapIndexed { i, p ->
            // 建議的專案掛在有子任務的那筆；都沒有子任務就掛第一筆
            val suggest = result.suggestedProject?.takeIf { if (withSubtasks > 0) p.subtasks.isNotEmpty() else i == 0 }
            val about = listOfNotNull(p.title, p.notes, inbox.text.takeIf { parsed.size == 1 }).joinToString(" ")
            val hinted = hints.guess(about, ctx.topics)
            val (c, explicit) = decide(p.classification, directives, hinted)
            // 沒主題，或整筆都沒把握（AI 還是給了主題）而且不是使用者指定、也不是從習慣猜到的：另外問
            val aiUnsure = result.engine == "gemini" && p.confidence < Task.NEEDS_REVIEW_BELOW && AutoField.TOPIC !in explicit && hinted == null
            if (c.topic == null || aiUnsure) unsure += i
            val (parent, subs) = build(p.copy(classification = c), source, origin, suggest)
            val auto = parent.autoTagged - explicit
            parent.copy(autoTagged = auto) to subs.map { it.copy(autoTagged = it.autoTagged - explicit) }
        }
        items.forEach { (parent, subs) ->
            store.createTask(parent)
            subs.forEach { store.createTask(it) }
        }
        val saved = inbox.copy(
            status = if (items.isEmpty()) InboxEntry.IGNORED else InboxEntry.PARSED,
            taskIds = items.map { it.first.id },
        )
        store.saveInbox(saved)
        // 「1130 開會」在 17:00 傳：排到明天 11:30，但要讓使用者確認
        val clock = inbox.text?.let { TimeParser.parse(it, ctx.now) }
        val rolled = if (clock?.rolledOver == true && items.size == 1) {
            items.map { it.first }.filter { it.dueAt == clock.at?.toInstant() }
        } else emptyList()
        val created = items.map { it.first.id }.toSet()
        val dups = items.mapNotNull { (t, _) ->
            ctx.openTasks.firstOrNull { it.id !in created && it.parentId == null && similar(it.title, t.title) }?.let { t to it }
        }
        return Outcome(saved, items, notice, ctx, unsureTopic = unsure, rolledOver = rolled, duplicates = dups)
    }

    /** 順序：使用者指定的 > 關鍵字和以前改過的 > Gemini／規則。回傳分類和「使用者指定」的欄位。 */
    private fun decide(c: Classification, d: Directives, hinted: String?): Pair<Classification, Set<String>> {
        val explicit = buildSet {
            if (d.topic != null) add(AutoField.TOPIC)
            if (d.projectId != null) add(AutoField.PROJECT)
            if (d.tagIds.isNotEmpty()) add(AutoField.TAGS)
        }
        return c.copy(
            topic = d.topic ?: hinted ?: c.topic,
            projectId = d.projectId ?: c.projectId,
            tagIds = (d.tagIds + c.tagIds).distinct().take(3),
        ) to explicit
    }

    /**
     * Gemini 恢復後重新整理一則：語音（還沒建待辦）就正式建立；規則版建好的待辦只更新系統自己選的分類欄位。
     * Gemini 還是不行回傳 null。
     */
    suspend fun retry(entry: InboxEntry, files: List<Attachment>, kind: SourceKind = SourceKind.TELEGRAM): Outcome? {
        val ctx = store.parseContext(now())
        val directives = Directives.extract(entry.text.orEmpty(), ctx)
        val hints = brain.hints()
        val input = Input(directives.text, entry.forwardFrom, files, kind, "", entry.tgMessageId, entry.tgChatId, entry.fileRefs)
        val ai = askGemini(directives.text, input, ctx.copy(guidance = hints.guidance()), allowed = true, background = true) as? Ai.Ok
            ?: return null
        val result = ai.result
        val done = entry.copy(retryAi = false, model = ai.model)
        if (entry.status == InboxEntry.PENDING) {
            val inbox = done.copy(text = entry.text ?: result.heard)
            if (result.command != null && result.tasks.isEmpty()) {
                val ignored = inbox.copy(status = InboxEntry.IGNORED)
                store.saveInbox(ignored)
                return Outcome(ignored, emptyList(), null, ctx, command = result.command)
            }
            return create(result, inbox, entry.forwardFrom, kind, directives, hints, ctx, null)
        }
        val tasks = entry.taskIds.mapNotNull { store.getTask(it) }
        val pairs = when {
            tasks.size == result.tasks.size -> tasks.zip(result.tasks)
            tasks.size == 1 && result.tasks.isNotEmpty() -> listOf(tasks.first() to result.tasks.first())
            else -> emptyList()
        }
        pairs.forEach { (t, p) ->
            val about = listOfNotNull(t.title, t.notes).joinToString(" ")
            val (c, _) = decide(p.classification, directives, hints.guess(about, ctx.topics))
            val updated = reclassify(t, c)
            if (updated != t) store.saveTask(updated)
        }
        store.saveInbox(done)
        return Outcome(done, tasks.map { (store.getTask(it.id) ?: it) to emptyList() }, null, ctx)
    }

    /** 回覆 Bot 的語音聽寫成文字（平常的模型），不能用回傳 null。 */
    suspend fun transcribe(voice: Attachment): String? {
        val gemini = brain.gemini() ?: return null
        return try {
            val media = listOf(GeminiTaskParser.Media(voice.mime.substringBefore(';'), Base64.getEncoder().encodeToString(voice.load())))
            gemini.parse("（這是使用者回覆 Bot 的語音，只要把聽到的原文放在 heard，tasks 留空。）", store.parseContext(now()), media)
                .heard?.trim()?.takeIf { it.isNotEmpty() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: QuotaExceededException) {
            brain.onQuotaExceeded()
            null
        } catch (e: Exception) {
            log("聽寫回覆失敗", e)
            null
        }
    }

    /**
     * 使用者回覆「重整」：用強模型重新整理這一則，原本建的待辦換成新的。
     * 強模型不能用（沒設定、額度用完、連不上）或這個主題設成不送 AI 時回傳 null。
     */
    suspend fun redo(entry: InboxEntry, files: List<Attachment>, kind: SourceKind = SourceKind.TELEGRAM): Outcome? {
        val ctx = store.parseContext(now())
        val directives = Directives.extract(entry.text.orEmpty(), ctx)
        val hints = brain.hints()
        val topic = directives.topic ?: directives.text.takeIf { it.isNotEmpty() }?.let { brain.rules.classify(it, null, ctx).topic }
        val input = Input(directives.text, entry.forwardFrom, files, kind, "", entry.tgMessageId, entry.tgChatId, entry.fileRefs)
        val allowed = topic == null || !brain.isPrivate(topic)
        val ai = askGemini(directives.text, input, ctx.copy(guidance = hints.guidance()), allowed = allowed, force = true) as? Ai.Ok
            ?: return null
        entry.taskIds.forEach { if (store.getTask(it) != null) store.deleteTask(it) }
        val inbox = entry.copy(retryAi = false, model = ai.model, taskIds = emptyList(), text = entry.text ?: ai.result.heard)
        return create(ai.result, inbox, entry.forwardFrom, kind, directives, hints, ctx, null)
    }

    /** 只動系統自己選的欄位（在 autoTagged 裡）或還空著的欄位，使用者改過的不碰。 */
    private fun reclassify(t: Task, c: Classification): Task {
        if (!t.status.isOpen) return t
        val auto = t.autoTagged.toMutableSet()
        var out = t
        if (c.topic != null && AutoField.TOPIC in auto) out = out.copy(topic = c.topic!!)
        if (c.topic != null && t.topic == Defaults.DEFAULT_TOPIC && AutoField.TOPIC !in auto && t.source.confidence != 1.0) {
            // 規則版沒猜到、先放預設主題的
            out = out.copy(topic = c.topic!!); auto += AutoField.TOPIC
        }
        if (c.projectId != null && (AutoField.PROJECT in auto || t.projectId == null)) {
            out = out.copy(projectId = c.projectId); auto += AutoField.PROJECT
        }
        if (c.tagIds.isNotEmpty() && (AutoField.TAGS in auto || t.tags.isEmpty())) {
            out = out.copy(tags = c.tagIds); auto += AutoField.TAGS
        }
        if ((c.priority ?: 0) != 0 && (AutoField.PRIORITY in auto || t.priority == 0)) {
            out = out.copy(priority = c.priority!!); auto += AutoField.PRIORITY
        }
        return out.copy(autoTagged = auto)
    }

    private fun kindOf(input: Input) = when {
        input.files.any { it.kind == Kind.VOICE } -> "voice"
        input.files.isNotEmpty() -> "photo"
        input.origin != null -> "forward"
        else -> "text"
    }

    private suspend fun parseWithGemini(
        gemini: GeminiTaskParser, text: String, origin: String?, files: List<Attachment>, ctx: ParseContext,
    ): ParseResult {
        val media = files.map { f ->
            GeminiTaskParser.Media(f.mime.substringBefore(';'), Base64.getEncoder().encodeToString(f.load()))
        }
        val body = buildString {
            if (origin != null) append("（從「").append(origin).append("」轉傳來的訊息）\n")
            if (files.any { it.kind == Kind.VOICE }) append("（附件有語音：先聽寫成文字，把聽寫原文放在 notes，再整理。）\n")
            if (files.any { it.kind == Kind.PHOTO }) append("（附件有截圖或照片：讀出裡面的文字和日期時間再整理。）\n")
            append(text)
        }
        return gemini.parse(body.trim(), ctx, media)
    }

    /** Gemini 不能用時：文字走規則版；截圖先用 ML Kit 讀字；只有語音就建一筆待確認的提醒。 */
    private suspend fun fallback(text: String, input: Input, ctx: ParseContext): ParseResult {
        val ocr = input.files.filter { it.kind == Kind.PHOTO }.mapNotNull { f ->
            runCatching { brain.ocr(f.load()) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        }.joinToString("\n")
        val all = listOf(text, ocr).filter { it.isNotBlank() }.joinToString("\n").trim()
        if (all.isEmpty()) {
            val what = if (input.files.any { it.kind == Kind.VOICE }) "語音" else "附件"
            return ParseResult(
                listOf(
                    ParsedTask(
                        title = "看 ${input.sourceLabel}${what}訊息",
                        notes = "沒有 Gemini 時無法自動整理${what}，請看原本的訊息。",
                        confidence = 0.3,
                    ),
                ),
                engine = "placeholder",
            )
        }
        val lines = all.lines().count { it.isNotBlank() }
        // 轉傳的對話、截圖文字、長文：規則版拆行會拆壞，整則當一筆，原文放備註
        if (input.origin != null || ocr.isNotEmpty() || all.length > 300 || lines > 10) {
            val firstLine = all.lines().first { it.isNotBlank() }.trim()
            val at = TimeParser.parse(all, ctx.now).at?.toInstant()
            return ParseResult(
                listOf(
                    ParsedTask(
                        title = TimeParser.parse(firstLine, ctx.now).title.take(40),
                        notes = all,
                        dueAt = at,
                        remindAt = at,
                        classification = brain.rules.classify(all, null, ctx),
                        confidence = 0.4,
                    ),
                ),
                engine = "rules",
            )
        }
        return brain.rules.parse(all, ctx)
    }

    /** ParsedTask → 待辦（和 APP 快速輸入一樣記下哪些欄位是自動選的）＋子任務。 */
    fun build(p: ParsedTask, source: TaskSource, origin: String?, suggest: String?): Pair<Task, List<Task>> {
        val c = p.classification
        val auto = buildSet {
            if (c.topic != null) add(AutoField.TOPIC)
            if (c.projectId != null) add(AutoField.PROJECT)
            if (c.tagIds.isNotEmpty()) add(AutoField.TAGS)
            if ((c.priority ?: 0) != 0) add(AutoField.PRIORITY)
        }
        val notes = listOfNotNull(origin?.let { "轉傳自：$it" }, p.notes).joinToString("\n").ifEmpty { null }
        val parent = Task(
            id = store.newTaskId(),
            title = p.title.trim().take(200),
            notes = notes,
            topic = c.topic ?: Defaults.DEFAULT_TOPIC,
            projectId = c.projectId,
            tags = c.tagIds,
            autoTagged = auto,
            priority = c.priority ?: 0,
            dueAt = p.dueAt,
            remindAt = p.remindAt,
            recurrence = p.recurrence,
            source = source.copy(confidence = p.confidence),
            suggestedProject = if (c.projectId == null) suggest ?: c.suggestedProject else null,
        )
        val subs = p.subtasks.map { s ->
            Task(
                id = store.newTaskId(),
                title = s.trim().take(200),
                topic = parent.topic,
                projectId = parent.projectId,
                parentId = parent.id,
                autoTagged = auto.intersect(setOf(AutoField.TOPIC, AutoField.PROJECT)),
                source = parent.source,
            )
        }
        return parent to subs
    }

    companion object {
        /** Gemini 一次請求最多 20 MB（base64 後會變大），保守一點。 */
        const val MAX_FILE_BYTES = 12L * 1024 * 1024
    }
}

private val GEMINI_HTTP = Regex("Gemini HTTP (\\d{3})")

private fun httpCode(e: Exception) = GEMINI_HTTP.find(e.message.orEmpty())?.groupValues?.get(1)?.toInt()

private fun retryable(e: Exception): Boolean {
    val code = httpCode(e) ?: return e is IOException
    return code >= 500
}

/** 把 Gemini 的錯誤翻成看得懂、知道要做什麼的一句話。 */
internal fun geminiReason(e: Exception): String {
    val msg = e.message.orEmpty()
    return when (val code = httpCode(e)) {
        null -> when {
            e is java.net.SocketTimeoutException -> "等太久沒回應"
            e is IOException -> "手機連不到 Google"
            else -> "回傳內容看不懂"
        }
        400 -> if ("API_KEY" in msg || "API key" in msg) "金鑰不對，請到設定重新貼 Gemini 金鑰" else "請求被拒（400）"
        401, 403 -> "金鑰沒有權限，請到設定確認 Gemini 金鑰"
        404 -> "找不到設定的模型，請到設定把模型改回 ${ModelRouting.LITE}"
        in 500..599 -> "Google 那邊忙線（$code）"
        else -> "HTTP $code"
    }
}

private val NOT_WORD = Regex("[\\s\\p{P}\\p{S}]+")

/** 標題幾乎一樣：去掉空白標點後相同，或兩兩字組重疊八成以上（至少 4 個字）。 */
internal fun similar(a: String, b: String): Boolean {
    val x = a.replace(NOT_WORD, "").lowercase()
    val y = b.replace(NOT_WORD, "").lowercase()
    if (x.isEmpty() || y.isEmpty()) return false
    if (x == y) return true
    if (x.length < 4 || y.length < 4) return false
    fun pairs(s: String) = s.windowed(2).toSet()
    val px = pairs(x)
    val py = pairs(y)
    return 2.0 * px.intersect(py).size / (px.size + py.size) >= 0.8
}
