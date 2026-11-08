package com.shishonomisaki.lifemanager.core.preview

import com.shishonomisaki.lifemanager.core.parse.Classification
import com.shishonomisaki.lifemanager.core.parse.ParseContext
import com.shishonomisaki.lifemanager.core.parse.RuleTaskParser
import com.shishonomisaki.lifemanager.core.parse.TimeParser

/** 輸入框下方預覽可以點掉的欄位。 */
enum class PreviewField { TIME, TOPIC, PROJECT, TAGS, PRIORITY }

/**
 * 打字時即時預覽：用本機規則（時間＋關鍵字），不花 Gemini 額度。
 * 送出時跟 APP 快速新增走同一套規則，所以預覽看到的就是存進去的（AI 之後還可能補強）。
 */
data class InputPreview(
    val title: String,
    val time: TimeParser.Result?,
    val classification: Classification,
) {
    val isEmpty: Boolean
        get() = time == null && classification.topic == null && classification.projectId == null &&
            classification.tagIds.isEmpty() && (classification.priority ?: 0) == 0

    companion object {
        private val rules = RuleTaskParser()

        suspend fun of(text: String, ctx: ParseContext, skip: Set<PreviewField> = emptySet()): InputPreview {
            val line = text.trim()
            val t = TimeParser.parse(line, ctx.now)
            val hasTime = t.at != null || t.recurrence != null
            val useTime = hasTime && PreviewField.TIME !in skip
            val title = if (useTime) t.title else line
            val c = rules.classify(title, null, ctx).without(skip)
            return InputPreview(title = title, time = t.takeIf { useTime }, classification = c)
        }
    }
}

/** 把使用者點掉的欄位拿掉。 */
fun Classification.without(skip: Set<PreviewField>): Classification = copy(
    topic = topic.takeUnless { PreviewField.TOPIC in skip },
    projectId = projectId.takeUnless { PreviewField.PROJECT in skip },
    tagIds = if (PreviewField.TAGS in skip) emptyList() else tagIds,
    priority = priority.takeUnless { PreviewField.PRIORITY in skip },
)
