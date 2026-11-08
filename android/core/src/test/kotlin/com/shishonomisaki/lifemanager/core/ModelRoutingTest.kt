package com.shishonomisaki.lifemanager.core

import com.shishonomisaki.lifemanager.core.model.Defaults
import com.shishonomisaki.lifemanager.core.parse.Classification
import com.shishonomisaki.lifemanager.core.parse.ModelRouting
import com.shishonomisaki.lifemanager.core.parse.ParseResult
import com.shishonomisaki.lifemanager.core.parse.ParsedTask
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ModelRoutingTest {
    private val now = ZonedDateTime.of(2026, 10, 20, 10, 0, 0, 0, Defaults.ZONE)
    private fun result(vararg tasks: ParsedTask) = ParseResult(tasks.toList(), engine = "gemini")
    private fun task(topic: String? = "工作", confidence: Double = 0.9, hoursFromNow: Long? = null) = ParsedTask(
        title = "x", classification = Classification(topic = topic), confidence = confidence,
        dueAt = hoursFromNow?.let { now.plusHours(it).toInstant() },
    )

    @Test
    fun upfront() {
        assertEquals("A1", ModelRouting.upfront("", hasImage = true, voiceSeconds = 0))
        assertEquals("A2", ModelRouting.upfront("字".repeat(601), false, 0))
        assertEquals("A3", ModelRouting.upfront("", false, 46))
        assertNull(ModelRouting.upfront("明天交報告", false, 20))
    }

    @Test
    fun recheck() {
        assertNull(ModelRouting.recheck("交報告", result(task()), now))
        assertEquals("B2", ModelRouting.recheck("交報告", result(task(confidence = 0.5)), now))
        assertEquals("B3", ModelRouting.recheck("交報告", result(task(topic = null)), now))
        assertNull(ModelRouting.recheck("交報告", result(task(topic = null)), now, topicGiven = true))
        assertEquals("B4", ModelRouting.recheck("請記得把那份文件寄給王經理然後回覆", result(), now))
        assertNull(ModelRouting.recheck("哈哈好喔", result(), now))
        assertEquals("B5", ModelRouting.recheck("明天交報告", result(task()), now))
        assertEquals("B5", ModelRouting.recheck("下午3點開會", result(task(hoursFromNow = -5)), now))
        assertNull(ModelRouting.recheck("下午3點開會", result(task(hoursFromNow = 5)), now))
        // 「快一點」不是時間
        assertNull(ModelRouting.recheck("報告寫快一點", result(task()), now))
        assertNull(ModelRouting.recheck("列清單", result().copy(command = "list"), now))
    }
}
