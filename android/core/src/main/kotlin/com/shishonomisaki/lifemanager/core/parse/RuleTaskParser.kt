package com.shishonomisaki.lifemanager.core.parse

import com.shishonomisaki.lifemanager.core.model.Priority

/** 離線、不花錢的規則版：時間詞＋關鍵字。 */
class RuleTaskParser : TaskParser {

    private val topicKeywords = linkedMapOf(
        "工作" to listOf("會議", "開會", "報價", "客戶", "主管", "經理", "老闆", "同事", "報告", "簡報", "提案", "合約",
            "公司", "專案", "出差", "email", "Email", "mail", "信件", "週報", "月報", "面試", "廠商", "訂單", "請款"),
        "家庭" to listOf("繳", "帳單", "電費", "水費", "瓦斯", "電話費", "保險", "房租", "管理費", "小孩", "兒子", "女兒",
            "老婆", "太太", "老公", "爸", "媽", "家裡", "回家", "學校", "接送", "買菜", "家長", "牙醫", "看醫生", "全家"),
        "知識" to listOf("學習", "讀書", "看書", "書", "課程", "上課", "筆記", "研究", "文章", "影片", "podcast", "Podcast",
            "教學", "論文", "知識", "整理資料"),
    )

    private val tagKeywords = linkedMapOf(
        "call" to listOf("打電話", "打給", "致電", "回電", "電話聯絡", "call"),
        "pay" to listOf("繳", "付款", "付錢", "轉帳", "匯款", "帳單", "刷卡", "費"),
        "errand" to listOf("去", "買", "領", "寄", "郵局", "銀行", "超商", "取件", "跑一趟"),
        "online" to listOf("線上", "網路", "上網", "網購", "訂票", "申請", "預約", "填表", "表單"),
        "waiting" to listOf("等", "回覆", "回信", "確認"),
        "discuss" to listOf("討論", "開會", "商量", "會議"),
    )

    override suspend fun classify(title: String, notes: String?, ctx: ParseContext): Classification {
        val text = listOfNotNull(title, notes).joinToString(" ")
        val topic = topicKeywords.entries
            .filter { it.key in ctx.topics }
            .maxByOrNull { (_, words) -> words.count { it in text } }
            ?.takeIf { (_, words) -> words.any { it in text } }
            ?.key
        val knownTagIds = ctx.tags.map { it.id }.toSet()
        val tagIds = tagKeywords.filter { (id, words) -> id in knownTagIds && words.any { it in text } }.keys.toMutableList()
        // 使用者自己加的標籤：名稱出現在文字裡就選
        ctx.tags.filter { it.id !in tagKeywords && it.name.length >= 2 && it.name in text }.forEach { tagIds += it.id }
        val project = ctx.projects.filter { it.name.isNotBlank() && it.name in text }.maxByOrNull { it.name.length }
        val priority = when {
            listOf("緊急", "馬上", "立刻", "立即", "今天一定", "急").any { it in text } -> Priority.URGENT
            listOf("重要", "務必", "一定要", "別忘").any { it in text } -> Priority.IMPORTANT
            else -> null
        }
        return Classification(
            topic = project?.topic ?: topic,
            projectId = project?.id,
            tagIds = tagIds.distinct().take(3),
            priority = priority,
        )
    }

    override suspend fun parse(text: String, ctx: ParseContext): ParseResult {
        val lines = text.lines().map { it.trim().trimStart('-', '•', '*', '・', '·').trim() }.filter { it.isNotEmpty() }
        val items = if (lines.size in 2..10) lines else listOf(text.trim())
        val tasks = items.map { line ->
            val t = TimeParser.parse(line, ctx.now)
            val at = t.at?.toInstant()
            ParsedTask(
                title = t.title.take(120),
                notes = if (line.length > 120 || items.size == 1 && text.length > t.title.length + 20) text else null,
                dueAt = at,
                remindAt = at,
                recurrence = t.recurrence,
                classification = classify(t.title, null, ctx),
                // 規則版只能猜，信心固定給中等，多行拆分的更不確定
                confidence = if (items.size > 1) 0.5 else 0.7,
            )
        }
        return ParseResult(tasks, engine = "rules")
    }
}
