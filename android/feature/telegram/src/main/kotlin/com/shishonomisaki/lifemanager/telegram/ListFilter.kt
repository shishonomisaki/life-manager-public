package com.shishonomisaki.lifemanager.telegram

import com.shishonomisaki.lifemanager.core.model.Defaults
import com.shishonomisaki.lifemanager.core.model.Task
import com.shishonomisaki.lifemanager.core.model.TaskStatus
import com.shishonomisaki.lifemanager.core.parse.ParseContext
import com.shishonomisaki.lifemanager.core.stats.WeeklyStats
import java.time.Instant
import java.time.ZonedDateTime

/** /list 後面的條件，全部都要符合。認不出的字當成標題關鍵字。 */
class ListFilter private constructor(
    private val checks: List<(Task) -> Boolean>,
    /** 給標題用，例如「今天・工作」；沒條件時是 null。 */
    val label: String?,
) {
    fun matches(t: Task) = checks.all { it(t) }

    companion object {
        fun parse(arg: String, ctx: ParseContext, now: ZonedDateTime): ListFilter {
            val checks = mutableListOf<(Task) -> Boolean>()
            val labels = mutableListOf<String>()
            val zone = now.zone
            val today = now.toLocalDate()
            fun startOf(daysFromToday: Long): Instant = today.plusDays(daysFromToday).atStartOfDay(zone).toInstant()
            fun due(t: Task) = t.dueAt ?: t.remindAt

            for (raw in arg.split(Regex("[\\s,，、]+"))) {
                val w = raw.trim().removePrefix("/").removePrefix("#")
                if (w.isEmpty()) continue
                when (w.lowercase()) {
                    "today", "今天", "今日" -> { checks += { t -> due(t)?.isBefore(startOf(1)) == true }; labels += "今天" }
                    "tomorrow", "明天" -> {
                        checks += { t -> due(t)?.let { !it.isBefore(startOf(1)) && it.isBefore(startOf(2)) } == true }
                        labels += "明天"
                    }
                    "week", "本週", "本周", "這週", "這周" -> {
                        val end = WeeklyStats.weekStart(today, Defaults.WEEK_STARTS_ON).plusDays(7).atStartOfDay(zone).toInstant()
                        checks += { t -> due(t)?.isBefore(end) == true }
                        labels += "本週"
                    }
                    "overdue", "逾期", "過期" -> { checks += { t -> due(t)?.isBefore(now.toInstant()) == true }; labels += "逾期" }
                    "nodate", "沒日期", "無日期", "沒時間" -> { checks += { t -> due(t) == null }; labels += "沒日期" }
                    "doing", "進行中" -> { checks += { t -> t.status == TaskStatus.DOING }; labels += "進行中" }
                    "waiting", "等待", "等待他人", "等人" -> { checks += { t -> t.status == TaskStatus.WAITING }; labels += "等待他人" }
                    else -> {
                        val project = ctx.projects.firstOrNull { it.name.equals(w, ignoreCase = true) }
                            ?: ctx.projects.firstOrNull { it.name.contains(w, ignoreCase = true) }
                        val tag = ctx.tags.firstOrNull { it.name.equals(w, ignoreCase = true) }
                        when {
                            w in ctx.topics -> checks += { t -> t.topic == w }
                            project != null -> checks += { t -> t.projectId == project.id }
                            tag != null -> checks += { t -> tag.id in t.tags }
                            else -> checks += { t -> t.title.contains(w, ignoreCase = true) }
                        }
                        labels += project?.name ?: w
                    }
                }
            }
            return ListFilter(checks, labels.distinct().joinToString("・").ifEmpty { null })
        }
    }
}
