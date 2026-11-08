package com.shishonomisaki.lifemanager.core.parse

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Gemini API（Google AI Studio 免費額度）版。用 responseSchema 要求固定格式 JSON。
 * HTTP 由呼叫端注入，core 模組不綁 Android。
 */
class GeminiTaskParser(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val http: suspend (url: String, body: String) -> HttpResult,
) : TaskParser {

    data class HttpResult(val code: Int, val body: String)

    /** 附件（截圖、語音），Telegram 收件匣用。 */
    data class Media(val mimeType: String, val base64: String)

    override suspend fun parse(text: String, ctx: ParseContext): ParseResult = parse(text, ctx, emptyList())

    suspend fun parse(text: String, ctx: ParseContext, media: List<Media>): ParseResult {
        val prompt = buildString {
            appendLine("把下面的內容整理成待辦事項。一則訊息可能有好幾件事；如果是同一件事的好幾個步驟，放在同一筆的 subtasks，並在 suggestedProject 建議一個專案名稱。")
            appendLine("沒有要做的事就回傳空的 tasks。時間用台北時間，格式 yyyy-MM-ddTHH:mm；只有日期沒時刻用 09:00；沒提到時間就留空。")
            appendLine("confidence 是你對這筆判讀的把握（0–1）。")
            appendLine("有語音附件時，heard 填你聽到的原文。")
            appendLine("如果內容是在對待辦清單下指令而不是新的待辦，tasks 留空，command 填：list（列清單、看待辦）、today（今天要做什麼）、week（本週統計）、done 編號（完成第幾項，例如 done 1 2）、done all（全部完成）、edit 編號 修改內容（改清單上第幾項，例如「第7項改到早上」填 edit 7 改到早上，「9改名 寄報價」填 edit 9 改名 寄報價，「第3項取消完成」填 edit 3 取消完成）。")
            appendLine("純數字的日期時間：8 碼（例如 20261130）是西元日期，7 碼（例如 1151130）是民國年日期（年加 1911），4 碼（例如 1130）是 24 小時制時間；6 碼或其他看不出來的不要猜，時間留空並把 confidence 給低。")
            appendLine("看不出屬於哪個主題時不要硬猜，topic 留空。")
            appendLine("每一件需要使用者去做、去赴約或要記得的事都要各列一筆，不要只抓重點：例如對方說「下週二下午已經幫你預約好」就是一筆下週二下午的待辦；看不出約的是什麼就照字面寫，confidence 給低。")
            appendLine("來源自己講得不確定（「可能」「也許」「不一定有用」）的事，priority 給 0。")
            appendLine("跟「目前未完成的待辦」是同一件事的，不要再建一筆。")
            appendLine()
            appendLine("內容：")
            append(text.ifBlank { "（見附件）" })
        }
        val out = call(prompt, ctx, parseSchema, media)
        val parsed = json.decodeFromString<ParseOut>(out)
        val tasks = parsed.tasks.map { t ->
            val due = t.due?.let { time(it, ctx) }
            ParsedTask(
                title = t.title.trim(),
                notes = t.notes?.takeIf { it.isNotBlank() },
                dueAt = due,
                remindAt = t.remind?.let { time(it, ctx) } ?: due,
                recurrence = t.recurrence?.takeIf { it.startsWith("FREQ=") },
                classification = toClassification(t.topic, t.project, t.tags, t.priority, null, ctx),
                confidence = t.confidence?.coerceIn(0.0, 1.0) ?: 0.8,
                subtasks = t.subtasks.orEmpty().filter { it.isNotBlank() },
            )
        }
        return ParseResult(
            tasks, parsed.suggestedProject?.takeIf { it.isNotBlank() }, engine = "gemini",
            heard = parsed.heard?.trim()?.takeIf { it.isNotEmpty() },
            // 只把指令名稱轉小寫，edit 後面的內容（例如新標題）保留原樣
            command = parsed.command?.trim()?.let { c -> c.substringBefore(' ').lowercase() + c.substring(c.substringBefore(' ').length) }
                ?.takeIf { COMMAND.matches(it) },
        )
    }

    override suspend fun classify(title: String, notes: String?, ctx: ParseContext): Classification {
        val prompt = buildString {
            appendLine("幫這筆待辦選主題、專案、標籤和優先度。專案和標籤只能從清單裡選，沒有適合的就留空；覺得該開新專案時在 suggestedProject 寫名稱。")
            appendLine("待辦：$title")
            if (!notes.isNullOrBlank()) appendLine("補充：$notes")
        }
        val out = json.decodeFromString<ClassifyOut>(call(prompt, ctx, classifySchema, emptyList()))
        return toClassification(out.topic, out.project, out.tags, out.priority, out.suggestedProject, ctx)
    }

    private fun toClassification(
        topic: String?, project: String?, tags: List<String>?, priority: Int?, suggested: String?, ctx: ParseContext,
    ): Classification {
        val p = project?.let { name -> ctx.projects.firstOrNull { it.name == name.trim() } }
        val tagIds = tags.orEmpty().mapNotNull { name -> ctx.tags.firstOrNull { it.name == name.trim() }?.id }.distinct()
        return Classification(
            topic = topic?.trim()?.takeIf { it in ctx.topics },
            projectId = p?.id,
            tagIds = tagIds,
            priority = priority?.coerceIn(0, 2),
            suggestedProject = suggested?.trim()?.takeIf { it.isNotEmpty() && p == null },
        )
    }

    private fun time(s: String, ctx: ParseContext) = runCatching {
        LocalDateTime.parse(s.trim().take(16), DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")).atZone(ctx.now.zone).toInstant()
    }.getOrNull()

    private suspend fun call(prompt: String, ctx: ParseContext, schema: JsonObject, media: List<Media>): String {
        val body = buildJsonObject {
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { addJsonObject { put("text", systemPrompt(ctx)) } }
            }
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        addJsonObject { put("text", prompt) }
                        media.forEach { m ->
                            addJsonObject {
                                putJsonObject("inline_data") {
                                    put("mime_type", m.mimeType)
                                    put("data", m.base64)
                                }
                            }
                        }
                    }
                }
            }
            putJsonObject("generationConfig") {
                put("temperature", 0.2)
                put("responseMimeType", "application/json")
                put("responseSchema", schema)
            }
        }
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        val res = http(url, body.toString())
        if (res.code == 429) throw QuotaExceededException("Gemini 額度用完")
        if (res.code !in 200..299) throw IllegalStateException("Gemini HTTP ${res.code}: ${res.body.take(300)}")
        return extractText(res.body)
    }

    private fun systemPrompt(ctx: ParseContext) = buildString {
        appendLine("你是個人待辦整理助理，使用繁體中文。現在時間：${ctx.now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm EEEE"))}（台北）。")
        appendLine("主題只能是：${ctx.topics.joinToString("、")}。")
        val projects = ctx.projects.joinToString("、") { it.name }
        appendLine("進行中的專案：${projects.ifEmpty { "（無）" }}。")
        appendLine("可用標籤：${ctx.tags.joinToString("、") { it.name }}。最多選 3 個。")
        appendLine("優先度：0 一般、1 重要、2 緊急。")
        appendLine("重複規則用 RRULE，只支援 FREQ=DAILY、FREQ=WEEKLY（可加 ;BYDAY=MO）、FREQ=MONTHLY（可加 ;BYMONTHDAY=5）。")
        if (ctx.openTasks.isNotEmpty()) {
            appendLine("目前未完成的待辦（避免重複建立）：")
            ctx.openTasks.take(20).forEach { appendLine("- ${it.title}") }
        }
        appendLine("使用者在開頭講了主題（例如「工作，…」）或寫了 #主題，一定照用。")
        if (ctx.guidance.isNotBlank()) {
            appendLine("使用者的分類習慣（優先照這個判斷）：")
            appendLine(ctx.guidance.trim())
        }
        append("標題要短、像一句待辦，例如「繳電話費」「回覆王經理報價」，不要把時間寫進標題。")
    }

    companion object {
        /** 平常用的模型（Lite，免費額度多）；複雜時升級見 [ModelRouting]。設定頁可改。 */
        const val DEFAULT_MODEL = ModelRouting.LITE

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
        private val COMMAND = Regex("^(list|today|week|done all|done( [1-9][0-9]?)+|edit [1-9][0-9]? .+)$")

        internal fun extractText(responseBody: String): String {
            val root = json.parseToJsonElement(responseBody).jsonObject
            val candidates = root["candidates"] as? JsonArray
                ?: throw IllegalStateException("Gemini 沒有回傳內容：${responseBody.take(300)}")
            val parts = candidates.first().jsonObject["content"]?.jsonObject?.get("parts")?.jsonArray
                ?: throw IllegalStateException("Gemini 回傳格式不對")
            return parts.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.content.orEmpty() }
        }

        private fun str(nullable: Boolean = true) = buildJsonObject { put("type", "STRING"); put("nullable", nullable) }
        private fun strArray() = buildJsonObject { put("type", "ARRAY"); put("items", buildJsonObject { put("type", "STRING") }) }
        private fun int() = buildJsonObject { put("type", "INTEGER"); put("nullable", true) }

        private val classifyProps = buildJsonObject {
            put("topic", str(false))
            put("project", str())
            put("tags", strArray())
            put("priority", int())
            put("suggestedProject", str())
        }

        private val classifySchema = buildJsonObject {
            put("type", "OBJECT")
            put("properties", classifyProps)
            putJsonArray("required") { add("topic"); add("tags") }
        }

        private val parseSchema = buildJsonObject {
            put("type", "OBJECT")
            putJsonObject("properties") {
                putJsonObject("tasks") {
                    put("type", "ARRAY")
                    putJsonObject("items") {
                        put("type", "OBJECT")
                        putJsonObject("properties") {
                            put("title", str(false))
                            put("notes", str())
                            put("due", str())
                            put("remind", str())
                            put("recurrence", str())
                            put("topic", str(false))
                            put("project", str())
                            put("tags", strArray())
                            put("priority", int())
                            putJsonObject("confidence") { put("type", "NUMBER") }
                            put("subtasks", strArray())
                        }
                        putJsonArray("required") { add("title"); add("confidence") }
                    }
                }
                put("suggestedProject", str())
                put("heard", str())
                put("command", str())
            }
            putJsonArray("required") { add("tasks") }
        }
    }

    @Serializable
    private data class ClassifyOut(
        val topic: String? = null,
        val project: String? = null,
        val tags: List<String>? = null,
        val priority: Int? = null,
        val suggestedProject: String? = null,
    )

    @Serializable
    private data class TaskOut(
        val title: String,
        val notes: String? = null,
        val due: String? = null,
        val remind: String? = null,
        val recurrence: String? = null,
        val topic: String? = null,
        val project: String? = null,
        val tags: List<String>? = null,
        val priority: Int? = null,
        val confidence: Double? = null,
        val subtasks: List<String>? = null,
    )

    @Serializable
    private data class ParseOut(
        val tasks: List<TaskOut> = emptyList(),
        val suggestedProject: String? = null,
        val heard: String? = null,
        val command: String? = null,
    )
}
