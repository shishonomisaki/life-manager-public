package com.shishonomisaki.lifemanager.core

import com.shishonomisaki.lifemanager.core.model.Project
import com.shishonomisaki.lifemanager.core.model.Task
import com.shishonomisaki.lifemanager.core.model.TaskStatus
import com.shishonomisaki.lifemanager.core.parse.ParseContext
import com.shishonomisaki.lifemanager.core.preview.InputPreview
import com.shishonomisaki.lifemanager.core.preview.PreviewField
import com.shishonomisaki.lifemanager.core.stats.TodaySummary
import com.shishonomisaki.lifemanager.core.stats.WeeklyStats
import kotlinx.coroutines.runBlocking
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UiLogicTest {
    private val zone = ZoneId.of("Asia/Taipei")
    private val now = ZonedDateTime.of(2026, 10, 20, 14, 30, 0, 0, zone)
    private val ctx = ParseContext(now = now, projects = listOf(Project(id = "p1", name = "ICARD", topic = "工作")))
    private fun at(day: Int, hour: Int) = ZonedDateTime.of(2026, 10, day, hour, 0, 0, 0, zone).toInstant()

    @Test fun previewMatchesRules() = runBlocking {
        val p = InputPreview.of("明天10點打給ICARD窗口", ctx)
        assertEquals(at(21, 10), p.time?.at?.toInstant())
        assertEquals("p1", p.classification.projectId)
        assertEquals("工作", p.classification.topic)
        assertTrue("call" in p.classification.tagIds)
        assertTrue("明天" !in p.title)
    }

    @Test fun previewSkipKeepsTextAndDropsFields() = runBlocking {
        val p = InputPreview.of("明天10點打給ICARD窗口", ctx, setOf(PreviewField.TIME, PreviewField.PROJECT, PreviewField.TAGS))
        assertNull(p.time)
        assertEquals("明天10點打給ICARD窗口", p.title)
        assertNull(p.classification.projectId)
        assertTrue(p.classification.tagIds.isEmpty())
        assertEquals("工作", p.classification.topic)
    }

    @Test fun plainTextPreviewIsEmpty() = runBlocking {
        assertTrue(InputPreview.of("整理房間", ctx).isEmpty)
    }

    @Test fun todaySummary() {
        val tasks = listOf(
            Task(id = "a", title = "逾期", dueAt = at(18, 9)),
            Task(id = "b", title = "早上已過", dueAt = at(20, 9)),
            Task(id = "c", title = "下午", dueAt = at(20, 16)),
            Task(id = "d", title = "晚上", dueAt = at(20, 20)),
            Task(id = "e", title = "完成", status = TaskStatus.DONE, completedAt = at(20, 11)),
            Task(id = "f", title = "明天", dueAt = at(21, 9)),
        )
        val s = TodaySummary.of(tasks, now.toInstant(), zone)
        assertEquals(1, s.done)
        assertEquals(1, s.overdue)
        assertEquals(5, s.total)
        assertEquals("c", s.next?.id)
    }

    @Test fun streakCountsBackFromYesterdayWhenTodayEmpty() {
        fun done(day: Int) = Task(title = "x", status = TaskStatus.DONE, completedAt = at(day, 12))
        val today = now.toLocalDate()
        assertEquals(3, WeeklyStats.streak(listOf(done(19), done(18), done(17), done(15)), today, zone))
        assertEquals(4, WeeklyStats.streak(listOf(done(20), done(19), done(18), done(17)), today, zone))
        assertEquals(0, WeeklyStats.streak(listOf(done(17)), today, zone))
    }
}
