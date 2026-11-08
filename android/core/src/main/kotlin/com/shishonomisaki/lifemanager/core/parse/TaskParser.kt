package com.shishonomisaki.lifemanager.core.parse

import com.shishonomisaki.lifemanager.core.model.Defaults
import com.shishonomisaki.lifemanager.core.model.Project
import com.shishonomisaki.lifemanager.core.model.Tag
import com.shishonomisaki.lifemanager.core.model.Task
import java.time.Instant
import java.time.ZonedDateTime

/**
 * 整理邏輯（規格 1.5 節）。階段 0：[GeminiTaskParser] 為主、[RuleTaskParser] 為離線備援；
 * 升級時換成呼叫 Cloud Function（Claude），畫面和資料不動。
 */
interface TaskParser {
    /** 一段文字（可能含好幾件事）整理成待辦。Telegram 收件匣用。 */
    suspend fun parse(text: String, ctx: ParseContext): ParseResult

    /** 已建立的待辦自動選主題、專案、標籤、優先度。APP 快速輸入用。 */
    suspend fun classify(title: String, notes: String?, ctx: ParseContext): Classification
}

data class ParseContext(
    val now: ZonedDateTime = ZonedDateTime.now(Defaults.ZONE),
    val topics: List<String> = Defaults.TOPICS,
    val projects: List<Project> = emptyList(),
    val tags: List<Tag> = Defaults.TAGS,
    /** 近期未完成待辦，讓 AI 判斷是不是重複。 */
    val openTasks: List<Task> = emptyList(),
    /** 使用者自己的分類習慣（工作關鍵字、以前改過的分類），交給 AI 參考。 */
    val guidance: String = "",
)

data class Classification(
    val topic: String? = null,
    val projectId: String? = null,
    val tagIds: List<String> = emptyList(),
    val priority: Int? = null,
    /** 覺得該開新專案時的建議名稱，由使用者確認才建立。 */
    val suggestedProject: String? = null,
)

data class ParsedTask(
    val title: String,
    val notes: String? = null,
    val dueAt: Instant? = null,
    val remindAt: Instant? = null,
    val recurrence: String? = null,
    val classification: Classification = Classification(),
    val confidence: Double = 1.0,
    /** 子任務（只支援一層）。 */
    val subtasks: List<String> = emptyList(),
)

data class ParseResult(
    val tasks: List<ParsedTask>,
    val suggestedProject: String? = null,
    /** 哪一種整理器產生的，例如 "gemini"、"rules"。 */
    val engine: String,
    /** 語音聽寫出來的原文（只有 Gemini 有）。 */
    val heard: String? = null,
    /** 內容其實是在下指令（例如「列清單」「完成 1 2」），整理成 list、today、week、done 1 2、done all。 */
    val command: String? = null,
)

/** Gemini 額度用完（HTTP 429）。呼叫端當天改用規則版。 */
class QuotaExceededException(message: String) : Exception(message)

/** 先試 [primary]，失敗就用 [fallback]。 */
class FallbackTaskParser(private val primary: TaskParser, private val fallback: TaskParser) : TaskParser {
    override suspend fun parse(text: String, ctx: ParseContext): ParseResult =
        runCatching { primary.parse(text, ctx) }.getOrElse { fallback.parse(text, ctx) }

    override suspend fun classify(title: String, notes: String?, ctx: ParseContext): Classification =
        runCatching { primary.classify(title, notes, ctx) }.getOrElse { fallback.classify(title, notes, ctx) }
}
