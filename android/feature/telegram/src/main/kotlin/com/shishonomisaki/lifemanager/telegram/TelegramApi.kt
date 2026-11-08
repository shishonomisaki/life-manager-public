package com.shishonomisaki.lifemanager.telegram

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Telegram Bot API 的最小用法（https://core.telegram.org/bots/api）。
 * 階段 0 不架伺服器：APP 自己用 getUpdates 拉訊息，再用 sendMessage 回覆。HTTP 由呼叫端注入。
 */
class TelegramApi(private val token: String, private val http: TelegramHttp) {

    interface TelegramHttp {
        /** POST JSON，回傳 HTTP 狀態碼和內容。 */
        suspend fun post(url: String, json: String, readTimeoutMs: Int): Pair<Int, String>

        /** 下載檔案內容。 */
        suspend fun download(url: String): ByteArray
    }

    suspend fun getMe(): TgUser = call("getMe", buildJsonObject { })

    /** [timeoutSec] > 0 是長輪詢：沒有新訊息時 Telegram 會等到有訊息或逾時才回。 */
    suspend fun getUpdates(offset: Long?, timeoutSec: Int): List<Update> = call(
        "getUpdates",
        buildJsonObject {
            if (offset != null) put("offset", offset)
            put("timeout", timeoutSec)
            put("limit", 50)
            putJsonArray("allowed_updates") { add("message"); add("callback_query") }
        },
        readTimeoutMs = (timeoutSec + 15) * 1000,
    )

    suspend fun sendMessage(chatId: Long, text: String, replyTo: Long? = null, keyboard: InlineKeyboard? = null): Message =
        call("sendMessage", buildJsonObject {
            put("chat_id", chatId)
            put("text", text.take(MAX_TEXT))
            if (replyTo != null) {
                put("reply_parameters", buildJsonObject {
                    put("message_id", replyTo)
                    put("allow_sending_without_reply", true)
                })
            }
            put("link_preview_options", buildJsonObject { put("is_disabled", true) })
            if (keyboard != null) put("reply_markup", json.encodeToJsonElement(keyboard))
        })

    suspend fun editMessageText(chatId: Long, messageId: Long, text: String, keyboard: InlineKeyboard? = null) {
        try {
            call<JsonElement>("editMessageText", buildJsonObject {
                put("chat_id", chatId)
                put("message_id", messageId)
                put("text", text.take(MAX_TEXT))
                put("link_preview_options", buildJsonObject { put("is_disabled", true) })
                put("reply_markup", json.encodeToJsonElement(keyboard ?: InlineKeyboard(emptyList())))
            })
        } catch (e: TelegramException) {
            // 內容沒變時 Telegram 會回錯，當作成功
            if ("not modified" !in e.description) throw e
        }
    }

    suspend fun deleteMessage(chatId: Long, messageId: Long) {
        call<JsonElement>("deleteMessage", buildJsonObject {
            put("chat_id", chatId)
            put("message_id", messageId)
        })
    }

    suspend fun answerCallbackQuery(id: String, text: String? = null) {
        call<JsonElement>("answerCallbackQuery", buildJsonObject {
            put("callback_query_id", id)
            if (text != null) put("text", text.take(190))
        })
    }

    /** 顯示「輸入中…」，Gemini 整理要幾秒。 */
    suspend fun sendTyping(chatId: Long) {
        call<JsonElement>("sendChatAction", buildJsonObject { put("chat_id", chatId); put("action", "typing") })
    }

    suspend fun setMyCommands(commands: List<Pair<String, String>>) {
        call<JsonElement>("setMyCommands", buildJsonObject {
            putJsonArray("commands") {
                commands.forEach { (c, d) -> add(buildJsonObject { put("command", c); put("description", d) }) }
            }
        })
    }

    /** 有設定 webhook 時 getUpdates 不能用，保險起見先清掉。 */
    suspend fun deleteWebhook() {
        call<JsonElement>("deleteWebhook", buildJsonObject { put("drop_pending_updates", false) })
    }

    /** Bot API 只能下載 20 MB 以內的檔案。 */
    suspend fun downloadFile(fileId: String): ByteArray {
        val f: TgFile = call("getFile", buildJsonObject { put("file_id", fileId) })
        val path = f.filePath ?: throw TelegramException(0, "檔案太大或已失效")
        return http.download("https://api.telegram.org/file/bot$token/$path")
    }

    private suspend inline fun <reified T> call(method: String, body: JsonObject, readTimeoutMs: Int = 30_000): T {
        val (code, text) = http.post("https://api.telegram.org/bot$token/$method", body.toString(), readTimeoutMs)
        val res = runCatching { json.decodeFromString<TgResponse<T>>(text) }.getOrNull()
        if (res == null || !res.ok || res.result == null) {
            throw TelegramException(res?.errorCode ?: code, res?.description ?: "HTTP $code")
        }
        return res.result
    }

    companion object {
        const val MAX_TEXT = 4096
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }
    }
}

class TelegramException(val code: Int, val description: String) : Exception("Telegram $code: $description") {
    /** 金鑰錯了或 Bot 被刪掉。 */
    val badToken: Boolean get() = code == 401 || code == 404
}

@Serializable
data class TgResponse<T>(
    val ok: Boolean,
    val result: T? = null,
    val description: String? = null,
    @SerialName("error_code") val errorCode: Int? = null,
)

@Serializable
data class Update(
    @SerialName("update_id") val updateId: Long,
    val message: Message? = null,
    @SerialName("callback_query") val callbackQuery: CallbackQuery? = null,
)

@Serializable
data class Message(
    @SerialName("message_id") val messageId: Long,
    val chat: Chat,
    val from: TgUser? = null,
    val date: Long = 0,
    val text: String? = null,
    val caption: String? = null,
    val photo: List<PhotoSize>? = null,
    val voice: Voice? = null,
    val audio: Audio? = null,
    val document: Document? = null,
    @SerialName("forward_origin") val forwardOrigin: MessageOrigin? = null,
    @SerialName("media_group_id") val mediaGroupId: String? = null,
    @SerialName("reply_to_message") val replyToMessage: Message? = null,
    @SerialName("reply_markup") val replyMarkup: InlineKeyboard? = null,
)

@Serializable
data class Chat(val id: Long, val type: String = "private", val title: String? = null)

@Serializable
data class TgUser(
    val id: Long,
    @SerialName("is_bot") val isBot: Boolean = false,
    @SerialName("first_name") val firstName: String = "",
    @SerialName("last_name") val lastName: String? = null,
    val username: String? = null,
) {
    val displayName: String get() = listOfNotNull(firstName, lastName).joinToString(" ").trim().ifEmpty { username.orEmpty() }
}

@Serializable
data class PhotoSize(
    @SerialName("file_id") val fileId: String,
    val width: Int = 0,
    val height: Int = 0,
    @SerialName("file_size") val fileSize: Long? = null,
)

@Serializable
data class Voice(
    @SerialName("file_id") val fileId: String,
    val duration: Int = 0,
    @SerialName("mime_type") val mimeType: String? = null,
    @SerialName("file_size") val fileSize: Long? = null,
)

@Serializable
data class Audio(
    @SerialName("file_id") val fileId: String,
    @SerialName("mime_type") val mimeType: String? = null,
    @SerialName("file_size") val fileSize: Long? = null,
)

@Serializable
data class Document(
    @SerialName("file_id") val fileId: String,
    @SerialName("mime_type") val mimeType: String? = null,
    @SerialName("file_name") val fileName: String? = null,
    @SerialName("file_size") val fileSize: Long? = null,
)

/** 轉傳來源（Bot API 7.0 起的 forward_origin）。 */
@Serializable
data class MessageOrigin(
    val type: String,
    @SerialName("sender_user") val senderUser: TgUser? = null,
    @SerialName("sender_user_name") val senderUserName: String? = null,
    @SerialName("sender_chat") val senderChat: Chat? = null,
    val chat: Chat? = null,
) {
    val name: String?
        get() = senderUser?.displayName ?: senderUserName ?: senderChat?.title ?: chat?.title
}

@Serializable
data class CallbackQuery(
    val id: String,
    val from: TgUser,
    val message: Message? = null,
    val data: String? = null,
)

@Serializable
data class InlineKeyboard(@SerialName("inline_keyboard") val rows: List<List<Button>>)

@Serializable
data class Button(
    val text: String,
    @SerialName("callback_data") val data: String? = null,
    val url: String? = null,
)

@Serializable
data class TgFile(
    @SerialName("file_id") val fileId: String,
    @SerialName("file_path") val filePath: String? = null,
)
