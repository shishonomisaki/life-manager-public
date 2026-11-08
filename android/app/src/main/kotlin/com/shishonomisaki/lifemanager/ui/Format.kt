package com.shishonomisaki.lifemanager.ui

import com.shishonomisaki.lifemanager.core.model.Defaults
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object Format {
    private val time = DateTimeFormatter.ofPattern("HH:mm")
    private val md = DateTimeFormatter.ofPattern("M/d")
    private val weekdays = listOf("一", "二", "三", "四", "五", "六", "日")

    fun weekday(d: LocalDate) = weekdays[d.dayOfWeek.value - 1]

    /** 「今天 10:00」「明天 09:00」「10/8(三) 10:00」。括號用半形，膠囊裡比較短。 */
    fun dateTime(i: Instant, today: LocalDate = LocalDate.now(Defaults.ZONE)): String {
        val z = i.atZone(Defaults.ZONE)
        val d = z.toLocalDate()
        val day = when (d) {
            today -> "今天"
            today.plusDays(1) -> "明天"
            today.plusDays(2) -> "後天"
            today.minusDays(1) -> "昨天"
            else -> "${md.format(d)}(${weekday(d)})"
        }
        return "$day ${time.format(z)}"
    }

    /** 逾期的短說法：今天的寫「已過 16:00」，更早的寫「逾期 9 天 · 10/11」。 */
    fun overdue(i: Instant, today: LocalDate = LocalDate.now(Defaults.ZONE)): String {
        val z = i.atZone(Defaults.ZONE)
        val days = java.time.temporal.ChronoUnit.DAYS.between(z.toLocalDate(), today)
        return if (days <= 0) "已過 ${time.format(z)}" else "逾期 $days 天 · ${md.format(z)}"
    }

    fun date(i: Instant): String {
        val d = i.atZone(Defaults.ZONE).toLocalDate()
        return "${md.format(d)}(${weekday(d)})"
    }

    fun percent(v: Double?): String = v?.let { "${Math.round(it * 100)}%" } ?: "—"
}
