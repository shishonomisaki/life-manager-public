package com.shishonomisaki.lifemanager.core.parse

import java.time.ZonedDateTime

/**
 * 平常用便宜的模型（Lite），內容複雜或 Lite 結果不可靠時才升級強模型（Flash）。
 * 規則和門檻見 plan/model-routing.md；原因代碼（A1、B2…）會記在 inbox，之後拿來調門檻。
 */
object ModelRouting {
    const val LITE = "gemini-flash-lite-latest"
    const val STRONG = "gemini-flash-latest"

    /** Flash 免費每天 20 次，自動升級用到這裡就停，留一點給手動「重整」。 */
    const val DAILY_STRONG_CAP = 18
    const val LONG_TEXT = 600
    const val LONG_VOICE_SECONDS = 45
    const val LOW_CONFIDENCE = 0.6

    /** 送出前就看得出來的複雜內容，直接用強模型；不需要回傳 null。 */
    fun upfront(text: String, hasImage: Boolean, voiceSeconds: Int): String? = when {
        hasImage -> "A1"
        text.length > LONG_TEXT -> "A2"
        voiceSeconds > LONG_VOICE_SECONDS -> "A3"
        else -> null
    }

    /**
     * Lite 做完後檢查結果，不可靠就回傳原因，呼叫端改用強模型重做。
     * [topicGiven]：使用者自己指定了主題，主題的部分不算。
     */
    fun recheck(text: String, result: ParseResult, now: ZonedDateTime, topicGiven: Boolean = false): String? {
        if (result.command != null) return null
        val tasks = result.tasks
        return when {
            tasks.any { it.confidence < LOW_CONFIDENCE } -> "B2"
            !topicGiven && tasks.any { it.classification.topic == null } -> "B3"
            tasks.isEmpty() && text.trim().length > 15 -> "B4"
            tasks.isNotEmpty() && TIME_WORDS.containsMatchIn(text) && timeMissingOrPast(result, now) -> "B5"
            else -> null
        }
    }

    private fun timeMissingOrPast(result: ParseResult, now: ZonedDateTime): Boolean {
        val times = result.tasks.mapNotNull { it.dueAt ?: it.remindAt }
        if (times.isEmpty()) return true
        // 講了時間卻排到過去（多給一小時，「剛剛」「等一下」這種不算）
        return times.all { it.isBefore(now.minusHours(1).toInstant()) }
    }

    private val TIME_WORDS = Regex(
        "今天|今晚|明天|明早|明晚|後天|大後天|下週|下周|下禮拜|下星期|這週|這周|週[一二三四五六日天]|周[一二三四五六日天]|" +
            "禮拜[一二三四五六日天]|星期[一二三四五六日天]|月底|月初|早上|中午|下午|晚上|" +
            "(?<![快多少早晚慢好大小有差])[0-9０-９一二三四五六七八九十兩]{1,3}\\s*[點点](?![點点])|[0-9]{1,2}[:：][0-9]{2}|[0-9]{1,2}/[0-9]{1,2}|[0-9]{1,2}\\s*[月號号]",
    )
}
