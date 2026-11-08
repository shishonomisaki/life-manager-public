package com.shishonomisaki.lifemanager.telegram

import com.shishonomisaki.lifemanager.core.model.Priority
import com.shishonomisaki.lifemanager.core.model.Project
import com.shishonomisaki.lifemanager.core.model.SourceKind
import com.shishonomisaki.lifemanager.core.model.Tag
import com.shishonomisaki.lifemanager.core.model.Task
import com.shishonomisaki.lifemanager.core.model.TaskStatus
import com.shishonomisaki.lifemanager.core.stats.WeekStats
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Bot 回覆的文字和按鈕。 */
object Replies {

    const val HELP = """這是你的待辦收件匣。

把要做的事直接傳給我：打字、語音、截圖，或從 LINE／Telegram 轉傳、分享過來都可以，我會整理成待辦存進 APP。

整理好的訊息：
・按「刪除」拿掉整理錯的項目
・直接回覆那則訊息就能修改，例如「改成週五下午3點」「2 改成明天」「這是工作的事」「刪除」「完成」
・按「改主題」選工作、家庭、個人、知識

直接指定分類：開頭講「工作，明天交報告」，或在任何地方加「#工作」；「#標籤」選標籤、「@專案」選專案（還沒有的專案會自動建立）。
整理得不好：回覆它「重整」，用強模型重做一次。
回覆清單可以改某一項：「7 改到明天早上」「9 改名 新名稱」「完成 6 8」。回覆「已完成」那則說「取消完成」可以改回來；重複的可以回覆它說「合併到 5」，補到清單第 5 項的備註。
已經建好的：回覆它「@ICARD」或「放到新專案 ICARD」放進專案，「移出專案」拿掉。

提醒：手機上的提醒響時，這裡也會收到一則，可以按「完成」「稍後」「更多」（進行中、等待他人、改日期、刪除）。

指令（直接打中文也可以）：
/list 目前所有待辦（或打「列清單」「待辦」）
  可以加條件、疊加使用：/list 今天 工作、/list 本週、/list 逾期、/list 進行中、/list 搬家（專案或標籤）、/list 王經理（標題關鍵字）
/today 今天和逾期的待辦（或打「今天」）
/week 本週統計（或打「本週」）
/done 2 完成清單的第 2 項（或打「完成 2」「第一項第二項完成」「全部完成」）
語音也可以下指令，例如說「列清單」「第一項完成」。

在 APP 設定開了「持續連線」，幾秒內就處理；沒開的話背景大約每 15 分鐘收一次。"""

    const val NOT_LINKED = "這是私人 Bot。請在「生活管理」APP 的設定 → Telegram 收件匣按「連結 Telegram」。"
    const val LINK_FAILED = "代碼不對或已過期，請在 APP 設定頁重新按「連結 Telegram」。"
    const val LINKED = "已連結 ✅\n\n$HELP"
    fun failed(reason: String) = "這則沒整理成功（${reason.take(120)}），請再傳一次。一直失敗的話，打開 APP 的設定 → Telegram 收件匣看看。"
    const val VOICE_WAITING = "收到語音了，但 Gemini 現在忙線聽不了。等它恢復我會自動再聽一次，整理好再回你，不用重傳。"
    const val VOICE_GAVE_UP = "這段語音一直沒辦法聽，請改用打字再傳一次。"
    const val REDO_DONE = "已用強模型重新整理，原本的換成上面這些。"
    const val REDO_EMPTY = "強模型看過了，覺得這則沒有要做的事，原本的已移除。"
    const val REDO_FAILED = "強模型現在不能用（沒設定、今天額度用完、連不上，或這個主題設成不送 AI），原本的不動。"
    const val TOPIC_UNSURE = "不確定放哪個主題，點一下："
    const val TOPIC_PROMPT = "要改成哪個主題？"
    const val UNSUPPORTED = "目前看得懂文字、語音、圖片和 PDF，這種內容還不行。"
    const val REPLY_UNKNOWN = "找不到你回覆的那則是哪一筆待辦，這次沒改。可以先「列清單」，再回覆清單說「3 改回未完成」。"
    const val NOTHING_FOUND = "這則看起來沒有要做的事，先略過。要的話按下面的按鈕建立成待辦。"

    val COMMANDS = listOf(
        "list" to "目前待辦，可加條件：/list 今天 工作",
        "today" to "今天和逾期的待辦",
        "week" to "本週統計",
        "done" to "完成第幾項，例如 /done 2",
        "help" to "使用說明",
    )

    private val hm = DateTimeFormatter.ofPattern("HH:mm")
    private val md = DateTimeFormatter.ofPattern("M/d")
    private val weekdays = listOf("一", "二", "三", "四", "五", "六", "日")

    fun date(d: LocalDate) = "${md.format(d)}（${weekdays[d.dayOfWeek.value - 1]}）"

    /** 「今天 10:00」「明天 09:00」「10/8（三）10:00」。 */
    fun dateTime(i: Instant, zone: ZoneId, today: LocalDate): String {
        val z = i.atZone(zone)
        val d = z.toLocalDate()
        val day = when (d) {
            today -> "今天"
            today.plusDays(1) -> "明天"
            today.plusDays(2) -> "後天"
            today.minusDays(1) -> "昨天"
            else -> date(d)
        }
        return "$day ${hm.format(z)}"
    }

    data class Lookup(val projects: List<Project>, val tags: List<Tag>, val zone: ZoneId, val today: LocalDate)

    /** 一筆待辦一行：「繳電話費　10/8（三）10:00　〔家庭／Q4 報價〕#付款」。 */
    fun line(t: Task, l: Lookup): String = buildString {
        if (t.status == TaskStatus.DONE) append("✅ ")
        append(t.title)
        (t.dueAt ?: t.remindAt)?.let { append("　").append(dateTime(it, l.zone, l.today)) }
        t.recurrence?.let { append("（").append(recurrenceLabel(it)).append("）") }
        val project = t.projectId?.let { id -> l.projects.firstOrNull { it.id == id }?.name }
        append("　〔").append(t.topic)
        if (project != null) append("／").append(project)
        append("〕")
        t.tags.mapNotNull { id -> l.tags.firstOrNull { it.id == id }?.name }.forEach { append(" #").append(it.replace(" ", "")) }
        if (t.priority > 0) append(" ‼").append(Priority.label(t.priority))
    }

    fun recurrenceLabel(rule: String): String = when {
        rule.startsWith("FREQ=DAILY") -> "每天"
        rule.startsWith("FREQ=WEEKLY") -> {
            val days = Regex("BYDAY=([A-Z,]+)").find(rule)?.groupValues?.get(1)?.split(",").orEmpty()
            val names = days.mapNotNull { d -> listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU").indexOf(d).takeIf { it >= 0 }?.let { weekdays[it] } }
            if (names.isEmpty()) "每週" else "每週" + names.joinToString("、")
        }
        rule.startsWith("FREQ=MONTHLY") -> Regex("BYMONTHDAY=(\\d+)").find(rule)?.let { "每月 ${it.groupValues[1]} 號" } ?: "每月"
        else -> "重複"
    }

    /** 整理結果的一項；[task] 為 null 表示已被刪除。 */
    data class Item(val task: Task?, val subtasks: List<Task>)

    fun created(
        items: List<Item>,
        l: Lookup,
        suggestedProject: String?,
        notice: String?,
        /** 建立後又改過（回覆修改、按按鈕），標「（已修改）」，免得分不出是改前還是改後。 */
        edited: Boolean = false,
        /** inbox.model；用了升級模型就在最後標一行。 */
        model: String? = null,
    ): String = buildString {
        val live = items.count { it.task != null }
        if (live == 0) {
            append("已全部取消。")
            return@buildString
        }
        append("已建立 ").append(items.size).append(" 項")
        if (edited) append("（已修改）")
        append("：")
        items.forEachIndexed { i, item ->
            append('\n').append(i + 1).append(". ")
            val t = item.task
            if (t == null) {
                append("（已刪除）")
                return@forEachIndexed
            }
            append(line(t, l))
            if (item.subtasks.isNotEmpty()) {
                append("\n　└ ").append(item.subtasks.joinToString("、") { (if (it.status == TaskStatus.DONE) "✅" else "") + it.title })
            }
        }
        val unsure = items.mapIndexedNotNull { i, it -> if (it.task?.needsReview == true) i + 1 else null }
        if (unsure.isNotEmpty()) {
            append("\n\n⚠️ ")
            append(if (items.size == 1) "這項" else "第 ${unsure.joinToString("、")} 項")
            append("不太確定，APP 裡標成「待確認」。")
        }
        if (suggestedProject != null) append("\n\n💡 建議開專案「").append(suggestedProject).append("」，回覆「開專案」就建立並放進去。")
        if (notice != null) append("\n\n").append(notice)
        append("\n\n回覆這則訊息可以修改，例如「改成週五下午3點」")
        if (items.size > 1) append("「2 改成明天」")
        append("。")
        if (model?.startsWith("strong") == true) append("\n（Flash 整理）")
    }

    fun clock(i: Instant, zone: ZoneId): String = hm.format(i.atZone(zone))

    fun duplicate(new: Task, old: Task, l: Lookup) =
        "「${new.title}」跟已經有的這筆很像，可能重複了：\n${line(old, l)}\n要把新的內容補到舊的備註（並刪掉新的）嗎？"

    fun narrowScreenshot(width: Int, height: Int) =
        "⚠️ 這張截圖被 Telegram 壓到 $width×$height，字可能看不清楚，結果不一定準。長截圖請用「檔案」方式傳（不壓縮），或切成幾張再傳。"

    /** 只講幾點、今天已經過了，排到明天。 */
    fun rolledOver(t: Task, due: Instant, zone: ZoneId) =
        "今天 ${clock(due, zone)} 已經過了，「${t.title}」排到明天 ${clock(due, zone)}。要改成今天就按下面。"

    fun askFullList(size: Int, missing: List<Int>) =
        "最近列的清單只有 $size 項，沒有第 ${missing.joinToString("、")} 項。要照上一張完整清單（列清單）的編號嗎？\n" +
            "下次也可以直接回覆那張清單說「完成 6 8」，就會照那張的編號。"

    const val MODIFY_OR_NEW = "這句看起來是要修改，不是新的待辦。\n" +
        "要改哪一筆：回覆那筆的訊息（整理結果、提醒或確認訊息），或先「列清單」再說「第 3 項改名 新名稱」。\n" +
        "真的要新增就按下面。"
    const val REPLY_TEXT_ONLY = "回覆修改請用文字或語音。"
    const val VOICE_REPLY_FAILED = "這段語音現在聽不了（Gemini 沒設定或暫時不能用），請打字回覆。"

    fun createdKeyboard(inboxId: String, items: List<Item>): InlineKeyboard? {
        val buttons = items.mapIndexedNotNull { i, item ->
            if (item.task == null) null
            else Button(if (items.size == 1) "🗑 刪除" else "🗑 刪除 ${i + 1}", "${Callback.DELETE}:$inboxId:$i")
        }
        if (buttons.isEmpty()) return null
        val rows = buttons.chunked(4).toMutableList()
        val last = mutableListOf(Button("🏷 改主題", "${Callback.TOPIC_PICK}:$inboxId"))
        if (buttons.size > 1) last += Button("全部取消", "${Callback.UNDO}:$inboxId")
        rows += last
        return InlineKeyboard(rows)
    }

    /** 改主題的選單：每筆一行，列出目前的主題。 */
    fun topicPicker(prompt: String, tasks: List<Task>): String = buildString {
        append(prompt)
        tasks.forEachIndexed { i, t ->
            append('\n')
            if (tasks.size > 1) append(i + 1).append(". ")
            append(t.title).append("　〔").append(t.topic).append("〕")
        }
    }

    /** 每筆一排主題按鈕；好幾筆時按鈕前面加編號。 */
    fun topicKeyboard(tasks: List<Task>, topics: List<String>): InlineKeyboard = InlineKeyboard(
        tasks.mapIndexed { i, t ->
            topics.mapIndexed { j, topic ->
                val mark = if (t.topic == topic) "✓" else ""
                val label = if (tasks.size > 1) "${i + 1} $mark$topic" else "$mark$topic"
                Button(label, "${Callback.TOPIC_SET}:${t.id}:$j")
            }
        },
    )

    fun reminder(t: Task, l: Lookup): String = "⏰ 提醒\n" + line(t, l)

    /** 提醒訊息只放三個按鈕，其他選項按了再另外跳一則，不擠在同一個泡泡。 */
    fun reminderKeyboard(taskId: String) = InlineKeyboard(
        listOf(
            listOf(
                Button("✓ 完成", "${Callback.REMIND_DONE}:$taskId"),
                Button("⏰ 稍後", "${Callback.REMIND_LATER}:$taskId"),
                Button("⋯ 更多", "${Callback.REMIND_MORE}:$taskId"),
            ),
        ),
    )

    const val LATER_PROMPT = "什麼時候再提醒？"

    fun laterKeyboard(taskId: String) = InlineKeyboard(
        listOf(
            listOf(
                Button("10 分後", "${Callback.REMIND_SNOOZE}:$taskId"),
                Button("1 小時後", "${Callback.REMIND_HOUR}:$taskId"),
            ),
            listOf(
                Button("今晚 8 點", "${Callback.REMIND_TONIGHT}:$taskId"),
                Button("明天 9 點", "${Callback.REMIND_TOMORROW}:$taskId"),
            ),
            listOf(Button("📅 自己選時間", "${Callback.REMIND_DATE}:$taskId")),
        ),
    )

    const val MORE_PROMPT = "要怎麼處理？"

    fun moreKeyboard(taskId: String) = InlineKeyboard(
        listOf(
            listOf(
                Button("▶ 進行中", "${Callback.REMIND_DOING}:$taskId"),
                Button("⏳ 等待他人", "${Callback.REMIND_WAITING}:$taskId"),
            ),
            listOf(
                Button("📅 改日期", "${Callback.REMIND_DATE}:$taskId"),
                Button("🗑 刪除", "${Callback.REMIND_DELETE}:$taskId"),
            ),
        ),
    )

    fun editPromptKeyboard(taskId: String) = InlineKeyboard(listOf(listOf(Button("取消", "${Callback.REMIND_CANCEL}:$taskId"))))

    fun today(tasks: List<Task>, l: Lookup, now: Instant): String =
        if (tasks.isEmpty()) "今天沒有到期的待辦 🎉" else numbered("今天（${date(l.today)}）", tasks, l, now)

    fun all(tasks: List<Task>, total: Int, label: String?, l: Lookup, now: Instant): String {
        val name = if (label == null) "目前待辦" else "待辦｜$label"
        return when {
            tasks.isEmpty() -> if (label == null) "目前沒有未完成的待辦 🎉" else "$name：沒有符合的待辦"
            total > tasks.size -> numbered("$name（共 $total 項，先列最近的 ${tasks.size} 項）", tasks, l, now)
            else -> numbered("$name（$total 項）", tasks, l, now)
        }
    }

    /** 按了 ✓ 之後重畫清單，標題沿用原本那則。 */
    fun numbered(header: String, tasks: List<Task>, l: Lookup, now: Instant): String {
        return buildString {
            append(header)
            tasks.forEachIndexed { i, t ->
                append('\n').append(i + 1).append(". ")
                val at = t.dueAt ?: t.remindAt
                if (t.status.isOpen && at != null && at.isBefore(now) && at.atZone(l.zone).toLocalDate().isBefore(l.today)) append("⚠️逾期 ")
                append(line(t, l))
            }
            append("\n\n按 ✓ 完成，或傳 /done 編號。")
        }
    }

    fun todayKeyboard(tasks: List<Task>): InlineKeyboard? {
        if (tasks.isEmpty()) return null
        val buttons = tasks.mapIndexed { i, t ->
            Button(if (t.status == TaskStatus.DONE) "✅ ${i + 1}" else "✓ ${i + 1}", "${Callback.COMPLETE}:${t.id}")
        }
        return InlineKeyboard(buttons.chunked(5))
    }

    fun week(s: WeekStats, l: Lookup): String = buildString {
        append("本週（").append(date(s.weekStart)).append(" 起）\n")
        append("新增 ").append(s.created).append(" 項，完成 ").append(s.completed).append(" 項")
        s.completionRate?.let { append("，完成率 ").append(percent(it)) }
        append('\n')
        s.onTimeRate?.let { append("準時完成率 ").append(percent(it)).append("　") }
        append("逾期未完成 ").append(s.overdueOpen).append(" 項")
        if (s.completedByTopic.isNotEmpty()) {
            append("\n完成的主題：")
            append(s.completedByTopic.entries.sortedByDescending { it.value }.joinToString("、") { "${it.key} ${it.value}" })
        }
        if (s.completedByProject.isNotEmpty()) {
            append("\n專案推進：")
            append(s.completedByProject.entries.sortedByDescending { it.value }.joinToString("、") { (id, n) ->
                "${l.projects.firstOrNull { it.id == id }?.name ?: "（已刪除的專案）"} $n"
            })
        }
        val fromBot = s.createdBySource[SourceKind.TELEGRAM] ?: 0
        if (s.created > 0) append("\n新增來源：Bot ").append(fromBot).append("、APP ").append(s.created - fromBot)
    }

    private fun percent(v: Double) = "${Math.round(v * 100)}%"
}

/** 按鈕的 callback_data（Telegram 限 64 bytes，Firestore id 20 字）。 */
object Callback {
    /** d:{inboxId}:{第幾項} 刪除整理結果的一項。 */
    const val DELETE = "d"
    /** u:{inboxId} 整則取消。 */
    const val UNDO = "u"
    /** f:{inboxId} 沒找到待辦時硬建一筆。 */
    const val FORCE = "f"
    /** tp:{inboxId} 打開改主題選單。 */
    const val TOPIC_PICK = "tp"
    /** ts:{taskId}:{第幾個主題} 改主題。 */
    const val TOPIC_SET = "ts"
    /** dl:{6.8.9} 編號超出最近清單時，照上一張完整清單完成。 */
    const val DONE_FULL = "dl"
    /** mg:{新的 taskId}:{舊的 taskId} 把新的補到舊的備註。 */
    const val MERGE = "mg"
    /** kp:{taskId} 看起來重複但兩筆都留著。 */
    const val KEEP = "kp"
    /** td:{taskId} 時間已過排到明天的，改回今天。 */
    const val BACK_TO_TODAY = "td"
    /** c:{taskId} 從 /today 清單完成。 */
    const val COMPLETE = "c"
    /** 提醒訊息上的按鈕，資料是 {動作}:{taskId}。 */
    const val REMIND_DONE = "rc"
    const val REMIND_SNOOZE = "rs"
    const val REMIND_HOUR = "rh"
    const val REMIND_TONIGHT = "rn"
    const val REMIND_LATER = "rl"
    const val REMIND_MORE = "rm"
    const val REMIND_TOMORROW = "rt"
    const val REMIND_DOING = "rp"
    const val REMIND_WAITING = "rw"
    const val REMIND_DATE = "re"
    const val REMIND_DELETE = "rd"
    const val REMIND_CANCEL = "rx"
    val REMIND_ACTIONS = setOf(
        REMIND_DONE, REMIND_SNOOZE, REMIND_HOUR, REMIND_TONIGHT, REMIND_LATER, REMIND_MORE, REMIND_TOMORROW, REMIND_DOING, REMIND_WAITING, REMIND_DATE, REMIND_DELETE, REMIND_CANCEL,
    )

    /** 是提醒本身（有「稍後」按鈕），不是另外跳出的選項。 */
    fun isReminderMessage(keyboard: InlineKeyboard?): Boolean =
        keyboard?.rows?.flatten()?.any { it.data?.startsWith("$REMIND_LATER:") == true } == true

    /** 回覆提醒訊息時，從按鈕找回是哪一筆待辦。 */
    fun taskIdOf(keyboard: InlineKeyboard?): String? =
        keyboard?.rows?.flatten()?.firstNotNullOfOrNull { b ->
            val parts = b.data?.split(":") ?: return@firstNotNullOfOrNull null
            if (parts.size == 2 && parts[0] in REMIND_ACTIONS) parts[1] else null
        }

    /** 從 Bot 的整理結果訊息上的按鈕找回 inboxId。 */
    fun inboxIdOf(keyboard: InlineKeyboard?): String? =
        keyboard?.rows?.flatten()?.firstNotNullOfOrNull { b ->
            val parts = b.data?.split(":") ?: return@firstNotNullOfOrNull null
            if (parts.size >= 2 && (parts[0] == DELETE || parts[0] == UNDO || parts[0] == FORCE || parts[0] == TOPIC_PICK)) parts[1] else null
        }

    fun topicTaskIdsOf(keyboard: InlineKeyboard?): List<String> =
        keyboard?.rows?.flatten()?.mapNotNull { b ->
            b.data?.split(":")?.takeIf { it.size == 3 && it[0] == TOPIC_SET }?.get(1)
        }.orEmpty().distinct()

    fun completeIdsOf(keyboard: InlineKeyboard?): List<String> =
        keyboard?.rows?.flatten()?.mapNotNull { b ->
            b.data?.takeIf { it.startsWith("$COMPLETE:") }?.substringAfter(':')
        }.orEmpty()
}
