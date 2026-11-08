package com.shishonomisaki.lifemanager.core.parse

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/**
 * 從一行中文抓出時間，例如「明天 10 點打給王經理」「週五前交報告」「下午3點半開會」「每週一倒垃圾」。
 * 抓到的字會從標題拿掉；抓不到就只回傳原標題。
 */
object TimeParser {

    data class Result(
        val title: String,
        /** 抓到的時間；只有日期沒有時刻時設為當天 [DEFAULT_TIME]。 */
        val at: ZonedDateTime?,
        /** 是否明確說了幾點。 */
        val hasTime: Boolean,
        /** RRULE，例如 FREQ=WEEKLY;BYDAY=MO。 */
        val recurrence: String?,
        /** 只講幾點、今天那個時間已經過了，所以排到明天。 */
        val rolledOver: Boolean = false,
    )

    val DEFAULT_TIME: LocalTime = LocalTime.of(9, 0)

    private const val NUM = "[0-9一二兩三四五六七八九十〇零]+"
    private const val WEEKDAY = "[一二三四五六日天]"
    private const val WEEK = "(?:週|周|星期|禮拜|礼拜)"
    private const val PERIOD = "(?:早上|上午|中午|下午|傍晚|晚上|今晚|明早|明晚|凌晨|半夜)"
    private const val BEFORE = "(?:之前|以前|前)?"

    private val reEveryDay = Regex("每天|每日")
    private val reEveryWeekday = Regex("每$WEEK($WEEKDAY)")
    private val reEveryWeek = Regex("每$WEEK")
    private val reEveryMonthDay = Regex("每(?:個)?月($NUM)(?:號|日)")
    private val reEveryMonth = Regex("每(?:個)?月")

    private val reMinutesLater = Regex("($NUM)\\s*(?:分鐘|分)(?:之)?後")
    private val reHoursLater = Regex("($NUM|半)\\s*(?:個)?(?:小時|鐘頭)(?:之)?後")

    private val reRelativeDay = Regex("(大後天|後天|明天|明日|今天|今日)$BEFORE")
    private val reWeekday = Regex("(下下|下|這|这|本)?(?:個)?$WEEK($WEEKDAY)$BEFORE")
    private val reMonthDay = Regex("($NUM)\\s*月\\s*($NUM)\\s*(?:日|號)?$BEFORE")
    private val reSlashDate = Regex("(?<![0-9/])(1[0-2]|0?[1-9])/(3[01]|[12][0-9]|0?[1-9])(?![0-9/])$BEFORE")
    private val reDayOnly = Regex("(?<![月0-9])($NUM)\\s*(?:號|日)$BEFORE")

    private val reColonTime = Regex("($PERIOD)?\\s*([01]?[0-9]|2[0-3])[:：]([0-5][0-9])")
    private val reHourTime = Regex("($PERIOD)?\\s*($NUM)\\s*(?:點|点|時)(?:\\s*(半|整|($NUM)\\s*分?))?")
    private val rePeriodOnly = Regex(PERIOD)

    /** 數字後面接這些字就不是日期時間，例如「3000元」「2026年」「1100人」。 */
    private const val NOT_TIME = "(?![0-9/:：.]|\\s*[元塊块個个人件張张次%％年號号樓楼萬万千百台本份箱位名組组碼码])"
    private const val NUM_START = "(?<![0-9/:：.$#＃第])"
    /** 8 碼西元日期：20261130。 */
    private val reCompactDate = Regex("${NUM_START}(20[0-9]{2})(0[1-9]|1[0-2])(0[1-9]|[12][0-9]|3[01])$NOT_TIME")
    /** 7 碼民國日期：1151130（115 年 = 2026 年）。 */
    private val reRocDate = Regex("${NUM_START}(1[0-9]{2})(0[1-9]|1[0-2])(0[1-9]|[12][0-9]|3[01])$NOT_TIME")
    /** 4 碼 24 小時制時間：1130、0930。 */
    private val reCompactTime = Regex("${NUM_START}([01][0-9]|2[0-3])([0-5][0-9])$NOT_TIME")
    /** 看不出是日期還是時間的純數字：6 碼，或不是合法時間的 4 碼。 */
    private val reBareDigits = Regex("${NUM_START}([0-9]{6}|[0-9]{4})$NOT_TIME")

    /**
     * 不確定意思的純數字（例如「251130」「2560」），回傳原字串；呼叫端要問使用者，不要猜。
     * 8 碼西元、7 碼民國日期和 4 碼合法時間不算（[parse] 認得）。
     */
    fun ambiguousNumbers(input: String): List<String> =
        reBareDigits.findAll(halfWidth(input)).map { it.groupValues[1] }
            .filterNot { it.length == 4 && reCompactTime.matches(it) }
            .toList()

    private fun halfWidth(s: String) = s.map { c -> if (c in '０'..'９') '0' + (c - '０') else c }.joinToString("")

    fun parse(input: String, now: ZonedDateTime): Result {
        val text = halfWidth(input)
        val removed = mutableListOf<IntRange>()
        fun take(m: MatchResult) { removed += m.range }
        fun free(m: MatchResult) = removed.none { it.first <= m.range.last && m.range.first <= it.last }
        fun findFree(re: Regex): MatchResult? = re.findAll(text).firstOrNull { free(it) }

        val today = now.toLocalDate()
        var date: LocalDate? = null
        var time: LocalTime? = null
        var period: String? = null
        var recurrence: String? = null
        var absolute: ZonedDateTime? = null

        // 重複
        findFree(reEveryWeekday)?.let { m ->
            val dow = weekday(m.groupValues[1])
            recurrence = "FREQ=WEEKLY;BYDAY=${byDay(dow)}"
            date = today.with(TemporalAdjusters.nextOrSame(dow))
            take(m)
        }
        if (recurrence == null) findFree(reEveryMonthDay)?.let { m ->
            val d = cnToInt(m.groupValues[1])
            if (d != null && d in 1..31) {
                recurrence = "FREQ=MONTHLY;BYMONTHDAY=$d"
                date = nextMonthDay(today, d)
                take(m)
            }
        }
        if (recurrence == null) findFree(reEveryDay)?.let { recurrence = "FREQ=DAILY"; take(it) }
        if (recurrence == null) findFree(reEveryWeek)?.let { recurrence = "FREQ=WEEKLY"; take(it) }
        if (recurrence == null) findFree(reEveryMonth)?.let { recurrence = "FREQ=MONTHLY"; take(it) }

        // 幾分鐘 / 幾小時後
        findFree(reMinutesLater)?.let { m ->
            cnToInt(m.groupValues[1])?.let { absolute = now.plusMinutes(it.toLong()); take(m) }
        }
        if (absolute == null) findFree(reHoursLater)?.let { m ->
            val g = m.groupValues[1]
            val minutes = if (g == "半") 30L else cnToInt(g)?.toLong()?.times(60)
            if (minutes != null) { absolute = now.plusMinutes(minutes); take(m) }
        }

        // 日期
        if (date == null) findFree(reCompactDate)?.let { m ->
            val (y, mo, d) = m.destructured
            runCatching { LocalDate.of(y.toInt(), mo.toInt(), d.toInt()) }.getOrNull()?.let { date = it; take(m) }
        }
        if (date == null) findFree(reRocDate)?.let { m ->
            val (y, mo, d) = m.destructured
            runCatching { LocalDate.of(y.toInt() + 1911, mo.toInt(), d.toInt()) }.getOrNull()?.let { date = it; take(m) }
        }
        if (date == null) findFree(reRelativeDay)?.let { m ->
            date = when (m.groupValues[1]) {
                "今天", "今日" -> today
                "明天", "明日" -> today.plusDays(1)
                "後天" -> today.plusDays(2)
                else -> today.plusDays(3)
            }
            take(m)
        }
        if (date == null) findFree(reWeekday)?.let { m ->
            val dow = weekday(m.groupValues[2])
            val thisWeekMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            date = when (m.groupValues[1]) {
                "下" -> thisWeekMonday.plusWeeks(1).with(TemporalAdjusters.nextOrSame(dow))
                "下下" -> thisWeekMonday.plusWeeks(2).with(TemporalAdjusters.nextOrSame(dow))
                "這", "这", "本" -> thisWeekMonday.with(TemporalAdjusters.nextOrSame(dow))
                else -> today.with(TemporalAdjusters.nextOrSame(dow))
            }
            take(m)
        }
        if (date == null) findFree(reMonthDay)?.let { m ->
            val mo = cnToInt(m.groupValues[1]); val d = cnToInt(m.groupValues[2])
            if (mo != null && d != null) monthDay(today, mo, d)?.let { date = it; take(m) }
        }
        if (date == null) findFree(reSlashDate)?.let { m ->
            monthDay(today, m.groupValues[1].toInt(), m.groupValues[2].toInt())?.let { date = it; take(m) }
        }
        if (date == null) findFree(reDayOnly)?.let { m ->
            val d = cnToInt(m.groupValues[1])
            if (d != null && d in 1..31) { date = nextMonthDay(today, d); take(m) }
        }

        // 時刻
        findFree(reColonTime)?.let { m ->
            period = m.groupValues[1].ifEmpty { null }
            time = adjustHour(m.groupValues[2].toInt(), m.groupValues[3].toInt(), period)
            take(m)
        }
        // 「1130」：24 小時制，不另外猜上午下午
        if (time == null) findFree(reCompactTime)?.let { m ->
            time = LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].toInt())
            take(m)
        }
        if (time == null) reHourTime.findAll(text).firstOrNull { m ->
            if (!free(m)) return@firstOrNull false
            val hourText = m.groupValues[2]
            val hasPeriod = m.groupValues[1].isNotEmpty()
            val hasMinutePart = m.groupValues[3].isNotEmpty()
            // 「一點點」「多一點」之類不是時間：中文數字要有時段或分鐘才算
            val arabic = hourText.all { it.isDigit() }
            arabic || hasPeriod || hasMinutePart || date != null
        }?.let { m ->
            val h = cnToInt(m.groupValues[2])
            val minute = when (val g = m.groupValues[3]) {
                "", "整" -> 0
                "半" -> 30
                else -> cnToInt(m.groupValues[4]) ?: cnToInt(g.trimEnd('分')) ?: 0
            }
            if (h != null && h in 0..24 && minute in 0..59) {
                period = m.groupValues[1].ifEmpty { null }
                time = adjustHour(h % 24, minute, period)
                take(m)
            }
        }
        if (time == null) findFree(rePeriodOnly)?.let { m ->
            period = m.value
            time = when (m.value) {
                "早上", "上午", "明早" -> LocalTime.of(9, 0)
                "中午" -> LocalTime.of(12, 0)
                "下午" -> LocalTime.of(14, 0)
                "傍晚" -> LocalTime.of(17, 0)
                "凌晨", "半夜" -> LocalTime.of(0, 0)
                else -> LocalTime.of(20, 0)
            }
            take(m)
        }
        // 「明早」「明晚」「今晚」本身也帶日期
        if (date == null) when (period) {
            "明早", "明晚" -> date = today.plusDays(1)
            "今晚" -> date = today
        }

        val title = cleanTitle(text, removed)
        val d = date
        val t = time
        val abs = absolute
        val rolled = abs == null && d == null && t != null && ZonedDateTime.of(today, t, now.zone).isBefore(now)
        val at: ZonedDateTime? = when {
            abs != null -> abs
            d != null -> ZonedDateTime.of(d, t ?: DEFAULT_TIME, now.zone)
            t != null -> ZonedDateTime.of(today, t, now.zone).let { if (rolled) it.plusDays(1) else it }
            else -> null
        }
        return Result(title.ifBlank { input.trim() }, at, abs != null || t != null, recurrence, rolled)
    }

    private fun adjustHour(hour: Int, minute: Int, period: String?): LocalTime {
        val h = when (period) {
            "下午", "傍晚", "晚上", "今晚", "明晚" -> if (hour < 12) hour + 12 else hour
            "中午" -> if (hour in 1..2) hour + 12 else hour
            "凌晨", "半夜" -> if (hour == 12) 0 else hour
            "早上", "上午", "明早" -> if (hour == 12) 0 else hour
            // 沒講時段的 1～6 點，待辦通常是下午
            else -> if (hour in 1..6) hour + 12 else hour
        }
        return LocalTime.of(h % 24, minute)
    }

    private fun monthDay(today: LocalDate, month: Int, day: Int): LocalDate? {
        if (month !in 1..12 || day !in 1..31) return null
        val thisYear = runCatching { LocalDate.of(today.year, month, day) }.getOrNull() ?: return null
        return if (thisYear.isBefore(today)) runCatching { LocalDate.of(today.year + 1, month, day) }.getOrNull() else thisYear
    }

    private fun nextMonthDay(today: LocalDate, day: Int): LocalDate {
        var m = today.withDayOfMonth(1)
        repeat(13) {
            if (day <= m.lengthOfMonth()) {
                val d = m.withDayOfMonth(day)
                if (!d.isBefore(today)) return d
            }
            m = m.plusMonths(1)
        }
        return today
    }

    private fun cleanTitle(text: String, removed: List<IntRange>): String {
        val sb = StringBuilder()
        text.forEachIndexed { i, c -> if (removed.none { i in it }) sb.append(c) }
        return sb.toString()
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim { it in "，,、。.:：的 " }
            .trim()
    }

    fun weekday(s: String): DayOfWeek = when (s) {
        "一" -> DayOfWeek.MONDAY
        "二" -> DayOfWeek.TUESDAY
        "三" -> DayOfWeek.WEDNESDAY
        "四" -> DayOfWeek.THURSDAY
        "五" -> DayOfWeek.FRIDAY
        "六" -> DayOfWeek.SATURDAY
        else -> DayOfWeek.SUNDAY
    }

    fun byDay(d: DayOfWeek): String = d.name.take(2)

    /** 「十二」→12、「兩」→2、「23」→23；不認得回傳 null。 */
    fun cnToInt(s: String): Int? {
        if (s.isEmpty()) return null
        if (s.all { it.isDigit() }) return s.toIntOrNull()
        val digit = mapOf('〇' to 0, '零' to 0, '一' to 1, '二' to 2, '兩' to 2, '三' to 3, '四' to 4,
            '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
        val i = s.indexOf('十')
        if (i < 0) {
            if (s.length == 1) return digit[s[0]]
            // 「二三」這種直接逐字
            return s.fold(0) { acc, c -> acc * 10 + (digit[c] ?: return null) }
        }
        val tens = if (i == 0) 1 else digit[s[i - 1]] ?: return null
        if (i > 1) return null
        val ones = if (i == s.lastIndex) 0 else if (i + 2 == s.length) digit[s[i + 1]] ?: return null else return null
        return tens * 10 + ones
    }
}
