package com.shishonomisaki.lifemanager.core.stats

import com.shishonomisaki.lifemanager.core.model.Task
import com.shishonomisaki.lifemanager.core.model.TaskStatus
import java.time.Instant
import java.time.ZoneId

/** 今天頁最上面的摘要卡：進度圈、逾期幾項、下一件事。 */
data class TodaySummary(
    /** 今天完成的。 */
    val done: Int,
    /** 今天要做完的總數：今天完成的＋今天到期還沒做的＋逾期的。 */
    val total: Int,
    val overdue: Int,
    /** 現在之後最近的一件（今天內），沒有就是 null。 */
    val next: Task?,
) {
    val progress: Float get() = if (total == 0) 0f else done.toFloat() / total

    companion object {
        fun of(tasks: List<Task>, now: Instant, zone: ZoneId): TodaySummary {
            val today = now.atZone(zone).toLocalDate()
            val start = today.atStartOfDay(zone).toInstant()
            val end = today.plusDays(1).atStartOfDay(zone).toInstant()
            fun whenOf(t: Task) = t.dueAt ?: t.remindAt
            val open = tasks.filter { it.status == TaskStatus.TODO || it.status == TaskStatus.DOING }
            val overdue = open.count { whenOf(it)?.isBefore(start) == true }
            val dueToday = open.filter { val w = whenOf(it); w != null && !w.isBefore(start) && w.isBefore(end) }
            val done = tasks.count { it.status == TaskStatus.DONE && it.completedAt?.let { c -> !c.isBefore(start) } == true }
            val next = dueToday.filter { !whenOf(it)!!.isBefore(now) }.minByOrNull { whenOf(it)!! }
            return TodaySummary(done = done, total = done + dueToday.size + overdue, overdue = overdue, next = next)
        }
    }
}
