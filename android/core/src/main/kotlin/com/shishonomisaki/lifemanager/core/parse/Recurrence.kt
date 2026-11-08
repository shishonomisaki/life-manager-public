package com.shishonomisaki.lifemanager.core.parse

import java.time.DayOfWeek
import java.time.ZonedDateTime

/** v1 支援的 RRULE 子集：每天、每週（可指定星期幾）、每月（可指定幾號）。 */
object Recurrence {
    val PRESETS = listOf(
        null to "不重複",
        "FREQ=DAILY" to "每天",
        "FREQ=WEEKLY" to "每週",
        "FREQ=MONTHLY" to "每月",
    )

    fun label(rule: String?): String {
        if (rule == null) return "不重複"
        val parts = parts(rule)
        return when (parts["FREQ"]) {
            "DAILY" -> "每天"
            "WEEKLY" -> parts["BYDAY"]?.split(",")?.joinToString("、", prefix = "每週") { dayLabel(it) } ?: "每週"
            "MONTHLY" -> parts["BYMONTHDAY"]?.let { "每月 $it 號" } ?: "每月"
            else -> rule
        }
    }

    /** 下一次的時間（嚴格晚於 [from]），不認得的規則回傳 null。 */
    fun next(rule: String, from: ZonedDateTime): ZonedDateTime? {
        val parts = parts(rule)
        return when (parts["FREQ"]) {
            "DAILY" -> from.plusDays(1)
            "WEEKLY" -> {
                val days = parts["BYDAY"]?.split(",")?.mapNotNull { day(it) }?.toSet().orEmpty()
                if (days.isEmpty()) from.plusWeeks(1)
                else (1..7).map { from.plusDays(it.toLong()) }.first { it.dayOfWeek in days }
            }
            "MONTHLY" -> {
                val d = parts["BYMONTHDAY"]?.toIntOrNull() ?: from.dayOfMonth
                var m = from.plusMonths(1)
                while (d > m.toLocalDate().lengthOfMonth()) m = m.plusMonths(1)
                m.withDayOfMonth(d)
            }
            else -> null
        }
    }

    private fun parts(rule: String) = rule.split(";").mapNotNull {
        val kv = it.split("=", limit = 2)
        if (kv.size == 2) kv[0].uppercase() to kv[1].uppercase() else null
    }.toMap()

    private fun day(code: String): DayOfWeek? = DayOfWeek.entries.firstOrNull { it.name.take(2) == code }

    private fun dayLabel(code: String) = when (code) {
        "MO" -> "一"; "TU" -> "二"; "WE" -> "三"; "TH" -> "四"; "FR" -> "五"; "SA" -> "六"; "SU" -> "日"
        else -> code
    }
}
