package com.shishonomisaki.lifemanager.core

import com.shishonomisaki.lifemanager.core.parse.TimeParser
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimeParserTest {
    private val zone = ZoneId.of("Asia/Taipei")
    // 2026-10-06 是週二
    private val now = ZonedDateTime.of(2026, 10, 6, 14, 30, 0, 0, zone)

    private fun at(m: Int, d: Int, h: Int, min: Int = 0) = ZonedDateTime.of(2026, m, d, h, min, 0, 0, zone)

    @Test fun tomorrowWithHour() {
        val r = TimeParser.parse("明天10點打給王經理", now)
        assertEquals("打給王經理", r.title)
        assertEquals(at(10, 7, 10), r.at)
        assertTrue(r.hasTime)
    }

    @Test fun afternoonHalf() {
        val r = TimeParser.parse("下午3點半開會", now)
        assertEquals("開會", r.title)
        assertEquals(at(10, 6, 15, 30), r.at)
    }

    @Test fun pastTimeTodayRollsToTomorrow() {
        val r = TimeParser.parse("早上9點 晨跑", now)
        assertEquals("晨跑", r.title)
        assertEquals(at(10, 7, 9), r.at)
    }

    @Test fun weekdayBeforeDeadline() {
        val r = TimeParser.parse("週五前交報告", now)
        assertEquals("交報告", r.title)
        assertEquals(at(10, 9, 9), r.at)
        assertFalse(r.hasTime)
    }

    @Test fun nextWeekday() {
        val r = TimeParser.parse("下週一 跟客戶開會", now)
        assertEquals("跟客戶開會", r.title)
        assertEquals(at(10, 12, 9), r.at)
    }

    @Test fun monthDayChinese() {
        val r = TimeParser.parse("繳電話費 10月8日 晚上8點", now)
        assertEquals("繳電話費", r.title)
        assertEquals(at(10, 8, 20), r.at)
    }

    @Test fun slashDateAndColon() {
        val r = TimeParser.parse("10/20 13:30 看牙醫", now)
        assertEquals("看牙醫", r.title)
        assertEquals(at(10, 20, 13, 30), r.at)
    }

    @Test fun chineseHour() {
        val r = TimeParser.parse("後天下午兩點去郵局", now)
        assertEquals("去郵局", r.title)
        assertEquals(at(10, 8, 14), r.at)
    }

    @Test fun minutesLater() {
        val r = TimeParser.parse("30分鐘後 回電", now)
        assertEquals("回電", r.title)
        assertEquals(now.plusMinutes(30), r.at)
    }

    @Test fun recurrenceWeekly() {
        val r = TimeParser.parse("每週一倒垃圾", now)
        assertEquals("倒垃圾", r.title)
        assertEquals("FREQ=WEEKLY;BYDAY=MO", r.recurrence)
        assertEquals(at(10, 12, 9), r.at)
    }

    @Test fun recurrenceMonthly() {
        val r = TimeParser.parse("每月5號繳房租", now)
        assertEquals("繳房租", r.title)
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=5", r.recurrence)
        assertEquals(at(11, 5, 9), r.at)
    }

    @Test fun tonight() {
        val r = TimeParser.parse("今晚 整理發票", now)
        assertEquals("整理發票", r.title)
        assertEquals(at(10, 6, 20), r.at)
    }

    @Test fun noTime() {
        val r = TimeParser.parse("多看一點書", now)
        assertEquals("多看一點書", r.title)
        assertNull(r.at)
    }

    @Test fun cnNumbers() {
        assertEquals(12, TimeParser.cnToInt("十二"))
        assertEquals(10, TimeParser.cnToInt("十"))
        assertEquals(25, TimeParser.cnToInt("二十五"))
        assertEquals(2, TimeParser.cnToInt("兩"))
    }

    @Test fun compactNumbers() {
        assertEquals(at(11, 30, 9), TimeParser.parse("20261130 交報告", now).at)
        assertEquals(at(11, 30, 9), TimeParser.parse("1151130交報告", now).at)
        val r = TimeParser.parse("1630開會", now)
        assertEquals("開會", r.title)
        assertEquals(at(10, 6, 16, 30), r.at)
        assertFalse(r.rolledOver)
        // 已經過了：排明天，並標出來
        val past = TimeParser.parse("1130 開會", now)
        assertEquals(at(10, 7, 11, 30), past.at)
        assertTrue(past.rolledOver)
        // 金額、人數不是時間
        assertNull(TimeParser.parse("準備1500元紅包", now).at)
        assertEquals(emptyList(), TimeParser.ambiguousNumbers("準備3000元紅包，2026年計畫"))
        assertEquals(listOf("251130", "2560"), TimeParser.ambiguousNumbers("251130 和 2560 是什麼"))
        assertEquals(emptyList(), TimeParser.ambiguousNumbers("20261130 1151130 0930"))
    }
}
