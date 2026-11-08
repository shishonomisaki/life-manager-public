package com.shishonomisaki.lifemanager.core

import com.shishonomisaki.lifemanager.core.model.Project
import com.shishonomisaki.lifemanager.core.model.SourceKind
import com.shishonomisaki.lifemanager.core.model.Task
import com.shishonomisaki.lifemanager.core.model.TaskSource
import com.shishonomisaki.lifemanager.core.model.TaskStatus
import com.shishonomisaki.lifemanager.core.parse.GeminiTaskParser
import com.shishonomisaki.lifemanager.core.parse.ParseContext
import com.shishonomisaki.lifemanager.core.parse.QuotaExceededException
import com.shishonomisaki.lifemanager.core.parse.Recurrence
import com.shishonomisaki.lifemanager.core.parse.RuleTaskParser
import com.shishonomisaki.lifemanager.core.stats.WeeklyStats
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ParsersTest {
    private val zone = ZoneId.of("Asia/Taipei")
    private val now = ZonedDateTime.of(2026, 10, 6, 14, 30, 0, 0, zone)
    private val ctx = ParseContext(now = now, projects = listOf(Project(id = "p1", name = "Q4 報價", topic = "工作")))

    @Test fun rulesClassify() = runBlocking {
        val c = RuleTaskParser().classify("繳電話費", null, ctx)
        assertEquals("家庭", c.topic)
        assertTrue("pay" in c.tagIds)

        val w = RuleTaskParser().classify("回覆王經理 Q4 報價", null, ctx)
        assertEquals("p1", w.projectId)
        assertEquals("工作", w.topic)
    }

    @Test fun rulesParseMultiLine() = runBlocking {
        val r = RuleTaskParser().parse("- 明天打電話給房東\n- 週五繳管理費", ctx)
        assertEquals(2, r.tasks.size)
        assertEquals("打電話給房東", r.tasks[0].title)
        assertTrue("call" in r.tasks[0].classification.tagIds)
    }

    @Test fun geminiClassifyMapsNamesToIds() = runBlocking {
        val reply = """{"candidates":[{"content":{"parts":[{"text":"{\"topic\":\"工作\",\"project\":\"Q4 報價\",\"tags\":[\"等回覆\",\"不存在\"],\"priority\":1}"}]}}]}"""
        var sent = ""
        val g = GeminiTaskParser("k", http = { _, body -> sent = body; GeminiTaskParser.HttpResult(200, reply) })
        val c = g.classify("回覆王經理報價", null, ctx)
        assertEquals("工作", c.topic)
        assertEquals("p1", c.projectId)
        assertEquals(listOf("waiting"), c.tagIds)
        assertEquals(1, c.priority)
        assertTrue("responseSchema" in sent)
    }

    @Test fun geminiParse() = runBlocking {
        val inner = """{"tasks":[{"title":"繳電話費","due":"2026-10-08T10:00","topic":"家庭","tags":["付款"],"confidence":0.9,"subtasks":[]}]}"""
        val reply = """{"candidates":[{"content":{"parts":[{"text":${kotlinx.serialization.json.JsonPrimitive(inner)}}]}}]}"""
        val g = GeminiTaskParser("k", http = { _, _ -> GeminiTaskParser.HttpResult(200, reply) })
        val r = g.parse("10/8 早上十點繳電話費", ctx)
        assertEquals(1, r.tasks.size)
        assertEquals(ZonedDateTime.of(2026, 10, 8, 10, 0, 0, 0, zone).toInstant(), r.tasks[0].dueAt)
        assertEquals(listOf("pay"), r.tasks[0].classification.tagIds)
    }

    @Test fun geminiQuota() {
        val g = GeminiTaskParser("k", http = { _, _ -> GeminiTaskParser.HttpResult(429, "{}") })
        assertFailsWith<QuotaExceededException> { runBlocking { g.classify("x", null, ctx) } }
    }

    @Test fun recurrenceNext() {
        assertEquals(now.plusDays(1), Recurrence.next("FREQ=DAILY", now))
        // 週二 → 下一個週一
        assertEquals(now.plusDays(6), Recurrence.next("FREQ=WEEKLY;BYDAY=MO", now))
        assertEquals(ZonedDateTime.of(2026, 11, 5, 14, 30, 0, 0, zone), Recurrence.next("FREQ=MONTHLY;BYMONTHDAY=5", now))
        assertEquals("每週一", Recurrence.label("FREQ=WEEKLY;BYDAY=MO"))
    }

    @Test fun weeklyStats() {
        val monday = LocalDate.of(2026, 10, 5)
        fun t(day: Int, h: Int) = ZonedDateTime.of(2026, 10, day, h, 0, 0, 0, zone).toInstant()
        val tasks = listOf(
            Task(id = "a", title = "a", createdAt = t(5, 9), dueAt = t(6, 9), completedAt = t(6, 8), status = TaskStatus.DONE, topic = "工作"),
            Task(id = "b", title = "b", createdAt = t(5, 9), dueAt = t(5, 12), completedAt = t(6, 8), status = TaskStatus.DONE, topic = "家庭",
                source = TaskSource(SourceKind.VOICE)),
            Task(id = "c", title = "c", createdAt = t(5, 9), dueAt = t(6, 10), status = TaskStatus.TODO),
            Task(id = "d", title = "d", createdAt = t(1, 9), status = TaskStatus.CANCELLED),
        )
        val s = WeeklyStats.compute(tasks, monday, t(6, 14), zone)
        assertEquals(3, s.created)
        assertEquals(2, s.completed)
        assertEquals(2.0 / 3, s.completionRate!!, 1e-9)
        assertEquals(0.5, s.onTimeRate!!, 1e-9)
        assertEquals(1, s.overdueOpen)
        assertEquals(1, s.createdBySource[SourceKind.VOICE])
        assertEquals(8, WeeklyStats.history(tasks, t(6, 14), zone).size)
    }
}
