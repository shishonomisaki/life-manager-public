package com.shishonomisaki.lifemanager.core.stats

import com.shishonomisaki.lifemanager.core.model.SourceKind
import com.shishonomisaki.lifemanager.core.model.Task
import com.shishonomisaki.lifemanager.core.model.TaskStatus
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** 每週統計（規格第 3 節），在 APP 端即時算，不另存。 */
data class WeekStats(
    val weekStart: LocalDate,
    val created: Int,
    val completed: Int,
    /** 本週相關（本週新增、到期或完成）的待辦中完成的比例，沒有資料時為 null。 */
    val completionRate: Double?,
    /** 有期限的完成項目裡，準時完成的比例。 */
    val onTimeRate: Double?,
    val overdueOpen: Int,
    val completedByTopic: Map<String, Int>,
    val createdBySource: Map<SourceKind, Int>,
    val completedByProject: Map<String, Int>,
)

object WeeklyStats {

    fun weekStart(date: LocalDate, weekStartsOn: Int = 1): LocalDate =
        date.with(TemporalAdjusters.previousOrSame(DayOfWeek.of(weekStartsOn)))

    fun compute(
        tasks: List<Task>,
        weekStart: LocalDate,
        now: Instant,
        zone: ZoneId,
    ): WeekStats {
        val start = weekStart.atStartOfDay(zone).toInstant()
        val end = weekStart.plusWeeks(1).atStartOfDay(zone).toInstant()
        fun inWeek(i: Instant?) = i != null && !i.isBefore(start) && i.isBefore(end)

        val live = tasks.filter { it.status != TaskStatus.CANCELLED }
        val created = live.filter { inWeek(it.createdAt) }
        val completed = live.filter { it.status == TaskStatus.DONE && inWeek(it.completedAt) }
        val relevant = live.filter { inWeek(it.createdAt) || inWeek(it.dueAt) || (it.status == TaskStatus.DONE && inWeek(it.completedAt)) }
        val relevantDone = relevant.count { it.status == TaskStatus.DONE && it.completedAt != null && it.completedAt.isBefore(end) }
        val withDue = completed.filter { it.dueAt != null }
        val onTime = withDue.count { !it.completedAt!!.isAfter(it.dueAt) }
        // 逾期只看到週末（或現在，取較早者）為止
        val cutoff = if (now.isBefore(end)) now else end
        val overdue = live.count { it.status.isOpen && it.dueAt != null && it.dueAt.isBefore(cutoff) && (it.createdAt == null || it.createdAt.isBefore(end)) }

        return WeekStats(
            weekStart = weekStart,
            created = created.size,
            completed = completed.size,
            completionRate = if (relevant.isEmpty()) null else relevantDone.toDouble() / relevant.size,
            onTimeRate = if (withDue.isEmpty()) null else onTime.toDouble() / withDue.size,
            overdueOpen = overdue,
            completedByTopic = completed.groupingBy { it.topic }.eachCount(),
            createdBySource = created.groupingBy { it.source.kind }.eachCount(),
            completedByProject = completed.mapNotNull { it.projectId }.groupingBy { it }.eachCount(),
        )
    }

    /** 最近 [weeks] 週（含本週），由舊到新。 */
    fun history(tasks: List<Task>, now: Instant, zone: ZoneId, weekStartsOn: Int = 1, weeks: Int = 8): List<WeekStats> {
        val thisWeek = weekStart(now.atZone(zone).toLocalDate(), weekStartsOn)
        return (weeks - 1 downTo 0).map { compute(tasks, thisWeek.minusWeeks(it.toLong()), now, zone) }
    }

    /** 連續完成天數：從今天（今天還沒完成就從昨天）往回數，每天至少完成一項。 */
    fun streak(tasks: List<Task>, today: LocalDate, zone: ZoneId): Int {
        val days = tasks.filter { it.status == TaskStatus.DONE }
            .mapNotNull { it.completedAt?.atZone(zone)?.toLocalDate() }
            .toSet()
        var d = if (today in days) today else today.minusDays(1)
        var n = 0
        while (d in days) { n++; d = d.minusDays(1) }
        return n
    }
}
