package com.shishonomisaki.lifemanager.telegram

import com.shishonomisaki.lifemanager.core.parse.ParseContext

/**
 * 使用者自己給的分類線索，存在手機上：每個主題的關鍵字（設定頁填），和以前在 Bot 改過的分類。
 * 判斷順序：使用者指定的（[Directives]）> 這裡 > Gemini > 內建規則。
 */
data class TopicHints(
    /** 主題 → 關鍵字，例如 工作 → 僑商處、王經理。 */
    val keywords: Map<String, List<String>> = emptyMap(),
    /** 改過分類的待辦標題 → 主題，新的在前。 */
    val learned: List<Pair<String, String>> = emptyList(),
) {
    /** 從線索猜主題；沒有把握回傳 null。 */
    fun guess(text: String, topics: List<String>): String? {
        val byKeyword = keywords.filterKeys { it in topics }
            .mapValues { (_, words) -> words.count { it.length >= 2 && text.contains(it, ignoreCase = true) } }
            .filterValues { it > 0 }
            .maxByOrNull { it.value }?.key
        if (byKeyword != null) return byKeyword
        return learned.firstOrNull { (title, topic) -> topic in topics && sharesWord(title, text) }?.second
    }

    /** 給 Gemini 的說明。 */
    fun guidance(): String = buildString {
        keywords.filterValues { it.isNotEmpty() }.forEach { (topic, words) ->
            appendLine("- 提到 ${words.joinToString("、")} 的是「$topic」")
        }
        if (learned.isNotEmpty()) {
            appendLine("- 使用者改過的分類：")
            learned.take(20).forEach { (title, topic) -> appendLine("  「$title」→ $topic") }
        }
    }.trim()

    companion object {
        /** 太常見、不能拿來判斷的字。 */
        private val COMMON = Regex("[的了要在是和跟給把我你他她們提醒一下今明後天點時再去來做好跟與及]")

        /** 有 3 個字以上的詞一樣（排除「的」「要」這類常見字），例如兩句都有「僑商處」。 */
        internal fun sharesWord(a: String, b: String): Boolean {
            if (a.length < 3) return false
            return (0..a.length - 3).any { i ->
                val w = a.substring(i, i + 3)
                !COMMON.containsMatchIn(w) && w.none { it.isWhitespace() || it.isDigit() } && b.contains(w, ignoreCase = true)
            }
        }
    }
}

/**
 * 使用者在內容裡直接指定的分類：開頭「工作，」「工作：」，或任何地方的「#工作」「#標籤」「@專案」。
 * 指定的一定照做，不算自動判斷。
 */
data class Directives(
    /** 拿掉指定語法後的內容。 */
    val text: String,
    val topic: String? = null,
    val tagIds: List<String> = emptyList(),
    val projectId: String? = null,
    /** 「@名稱」但還沒有這個專案：由呼叫的人決定要不要建立。 */
    val newProject: String? = null,
) {
    val any: Boolean get() = topic != null || tagIds.isNotEmpty() || projectId != null

    companion object {
        fun extract(raw: String, ctx: ParseContext): Directives {
            var text = raw
            var topic: String? = null
            var projectId: String? = null
            var newProject: String? = null
            val tagIds = mutableListOf<String>()

            val topics = ctx.topics.sortedByDescending { it.length }
            if (topics.isNotEmpty()) {
                val names = topics.joinToString("|") { Regex.escape(it) }
                Regex("^\\s*(?:(?:分類|主題)\\s*(?:是|為|:|：)?\\s*)?($names)\\s*(?:的事|的)?\\s*[，,：:、。]\\s*")
                    .find(text)?.let { m ->
                        topic = m.groupValues[1]
                        text = text.substring(m.range.last + 1)
                    }
            }

            // #名稱、@專案：名稱後面不一定有空白，取最長的符合
            val marker = Regex("[#＃@＠]")
            var i = 0
            val out = StringBuilder()
            while (i < text.length) {
                val c = text[i]
                if (!marker.matches(c.toString())) { out.append(c); i++; continue }
                val rest = text.substring(i + 1)
                val atProject = c == '@' || c == '＠'
                val candidates = buildList {
                    ctx.projects.forEach { add(it.name to "p:${it.id}") }
                    if (!atProject) {
                        ctx.topics.forEach { add(it to "t:$it") }
                        ctx.tags.forEach { add(it.name to "g:${it.id}") }
                    }
                }.filter { it.first.isNotBlank() && rest.startsWith(it.first, ignoreCase = true) }
                    .maxByOrNull { it.first.length }
                if (candidates == null) {
                    // @ 在開頭或空白、標點後面才算（避免 email）
                    val startsWord = i == 0 || !text[i - 1].isLetterOrDigit()
                    val name = if (atProject && startsWord && newProject == null && projectId == null) NEW_NAME.find(rest)?.value else null
                    if (name == null) { out.append(c); i++; continue }
                    newProject = name
                    i += 1 + name.length
                    continue
                }
                val (name, ref) = candidates
                when (ref[0]) {
                    't' -> topic = topic ?: ref.substring(2)
                    'g' -> tagIds += ref.substring(2)
                    'p' -> projectId = projectId ?: ref.substring(2)
                }
                i += 1 + name.length
            }
            text = out.toString().replace(Regex("[ \\t]{2,}"), " ").trim()
            // 專案有自己的主題
            if (topic == null && projectId != null) topic = ctx.projects.firstOrNull { it.id == projectId }?.topic
            return Directives(text, topic, tagIds.distinct(), projectId, newProject.takeIf { projectId == null })
        }

        private val NEW_NAME = Regex("^[^\\s#＃@＠，,。：:、；;！!？?「」()（）]{1,20}")
    }
}
