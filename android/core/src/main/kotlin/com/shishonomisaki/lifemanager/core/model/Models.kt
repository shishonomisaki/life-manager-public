package com.shishonomisaki.lifemanager.core.model

import java.time.Instant
import java.time.ZoneId

/** 資料模型，對應 docs/schema.md（Firestore `users/{uid}/...`）。APP 和 Telegram 收件匣共用。 */

enum class TaskStatus(val value: String, val label: String) {
    TODO("todo", "待辦"),
    DOING("doing", "進行中"),
    WAITING("waiting", "等待他人"),
    DONE("done", "完成"),
    CANCELLED("cancelled", "取消");

    val isOpen: Boolean get() = this == TODO || this == DOING || this == WAITING

    companion object {
        fun from(value: String?): TaskStatus = entries.firstOrNull { it.value == value } ?: TODO
        /** 看板上的四欄。 */
        val board = listOf(TODO, DOING, WAITING, DONE)
    }
}

enum class SourceKind(val value: String, val label: String) {
    MANUAL("manual", "手動"),
    VOICE("voice", "語音"),
    TELEGRAM("telegram", "Bot"),
    /** 從其他 APP 分享進來（Android 分享選單）。 */
    SHARE("share", "分享");

    companion object {
        fun from(value: String?): SourceKind = entries.firstOrNull { it.value == value } ?: MANUAL
    }
}

enum class ProjectStatus(val value: String, val label: String) {
    ACTIVE("active", "進行中"),
    PAUSED("paused", "暫停"),
    DONE("done", "完成"),
    ARCHIVED("archived", "封存");

    companion object {
        fun from(value: String?): ProjectStatus = entries.firstOrNull { it.value == value } ?: ACTIVE
    }
}

data class TaskSource(
    val kind: SourceKind = SourceKind.MANUAL,
    val inboxId: String? = null,
    /** AI 判讀信心 0–1，低於 [Task.NEEDS_REVIEW_BELOW] 在 APP 標記「待確認」。 */
    val confidence: Double? = null,
)

data class Task(
    val id: String = "",
    val title: String,
    val notes: String? = null,
    val topic: String = Defaults.DEFAULT_TOPIC,
    val projectId: String? = null,
    val parentId: String? = null,
    val tags: List<String> = emptyList(),
    /** 系統自動選的欄位（[AutoField]），使用者改過就移除。 */
    val autoTagged: Set<String> = emptySet(),
    val priority: Int = 0,
    val status: TaskStatus = TaskStatus.TODO,
    val waitingFor: String? = null,
    val dueAt: Instant? = null,
    val remindAt: Instant? = null,
    val recurrence: String? = null,
    val completedAt: Instant? = null,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
    val source: TaskSource = TaskSource(),
    val version: Long = 0,
    /** AI 覺得該開新專案時的建議名稱，使用者按一下才建立。 */
    val suggestedProject: String? = null,
) {
    val needsReview: Boolean get() = (source.confidence ?: 1.0) < NEEDS_REVIEW_BELOW

    companion object {
        const val NEEDS_REVIEW_BELOW = 0.6
    }
}

object AutoField {
    const val TOPIC = "topic"
    const val PROJECT = "projectId"
    const val TAGS = "tags"
    const val PRIORITY = "priority"
}

object Priority {
    const val NORMAL = 0
    const val IMPORTANT = 1
    const val URGENT = 2
    fun label(p: Int) = when (p) {
        URGENT -> "緊急"
        IMPORTANT -> "重要"
        else -> "一般"
    }
}

data class Project(
    val id: String = "",
    val name: String,
    val topic: String = Defaults.DEFAULT_TOPIC,
    val goal: String? = null,
    val status: ProjectStatus = ProjectStatus.ACTIVE,
    val dueAt: Instant? = null,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
)

data class Tag(
    val id: String,
    val name: String,
    val color: String = "#607D8B",
    val useCount: Long = 0,
)

object Defaults {
    const val DEFAULT_TOPIC = "個人"
    val TOPICS = listOf("工作", "家庭", "個人", "知識")
    val ZONE: ZoneId = ZoneId.of("Asia/Taipei")
    const val WEEK_STARTS_ON = 1 // 1 = 週一

    /** 預設標籤，id 固定，方便 APP 和 Bot 對應。 */
    val TAGS = listOf(
        Tag("call", "打電話", "#1E88E5"),
        Tag("pay", "付款", "#E53935"),
        Tag("errand", "出門辦", "#FB8C00"),
        Tag("online", "線上辦", "#00897B"),
        Tag("waiting", "等回覆", "#8E24AA"),
        Tag("quick", "5 分鐘可做", "#43A047"),
        Tag("discuss", "要討論", "#6D4C41"),
    )
}
