package com.shishonomisaki.lifemanager.telegram

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom

/** Telegram Bot 金鑰和收件匣狀態，只存在這支手機（Android Keystore 加密）。 */
class TelegramSettings(context: Context) : BotState {

    data class State(
        val token: String = "",
        val botUsername: String = "",
        val linkedChatId: Long? = null,
        val linkCode: String? = null,
        val linkCodeExpiresAt: Long = 0,
        val lastPollAt: Long = 0,
        val lastError: String? = null,
        /** 手機提醒響時也發到 Telegram。 */
        val remindInTelegram: Boolean = true,
        /** 常駐連線：背景也即時收 Bot 訊息。 */
        val alwaysOn: Boolean = false,
    ) {
        val hasToken: Boolean get() = token.isNotBlank()
        val linked: Boolean get() = linkedChatId != null
    }

    private val prefs: SharedPreferences = runCatching {
        EncryptedSharedPreferences.create(
            context,
            "telegram_secure",
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse {
        context.deleteSharedPreferences("telegram_secure")
        context.getSharedPreferences("telegram_fallback", Context.MODE_PRIVATE)
    }

    private val _state = MutableStateFlow(read())
    val state: StateFlow<State> = _state.asStateFlow()
    val current: State get() = _state.value

    private fun read() = State(
        token = prefs.getString(K_TOKEN, "").orEmpty(),
        botUsername = prefs.getString(K_USERNAME, "").orEmpty(),
        linkedChatId = prefs.getLong(K_CHAT, 0).takeIf { it != 0L },
        linkCode = prefs.getString(K_CODE, null),
        linkCodeExpiresAt = prefs.getLong(K_CODE_EXPIRES, 0),
        lastPollAt = prefs.getLong(K_LAST_POLL, 0),
        lastError = prefs.getString(K_LAST_ERROR, null),
        remindInTelegram = prefs.getBoolean(K_REMIND, true),
        alwaysOn = prefs.getBoolean(K_ALWAYS_ON, false),
    )

    private fun edit(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).commit()
        _state.value = read()
    }

    /** 換一隻 Bot 時，之前的連結和進度都不算數。 */
    fun setBot(token: String, username: String) = edit {
        if (token != current.token) {
            remove(K_CHAT); remove(K_OFFSET); remove(K_CODE); remove(K_LAST_ERROR)
        }
        putString(K_TOKEN, token.trim())
        putString(K_USERNAME, username)
    }

    fun clearBot() = edit { clear() }

    fun unlink() = edit { remove(K_CHAT) }

    fun setRemindInTelegram(on: Boolean) = edit { putBoolean(K_REMIND, on) }

    fun setAlwaysOn(on: Boolean) = edit { putBoolean(K_ALWAYS_ON, on) }

    /** 產生 10 分鐘有效的 6 位數連結代碼。 */
    fun newLinkCode(): String {
        val code = "%06d".format(SecureRandom().nextInt(1_000_000))
        edit {
            putString(K_CODE, code)
            putLong(K_CODE_EXPIRES, System.currentTimeMillis() + 10 * 60_000)
            putInt(K_ATTEMPTS, 0)
        }
        return code
    }

    fun recordPoll(error: String?) = edit {
        putLong(K_LAST_POLL, System.currentTimeMillis())
        if (error == null) remove(K_LAST_ERROR) else putString(K_LAST_ERROR, error)
    }

    // ---------- BotState ----------

    override var nextOffset: Long
        get() = prefs.getLong(K_OFFSET, 0)
        set(value) { prefs.edit().putLong(K_OFFSET, value).commit() }

    override val linkedChatId: Long? get() = current.linkedChatId

    @Synchronized
    override fun tryLink(code: String, chatId: Long): Boolean {
        val s = current
        if (s.linkCode == null) return false
        val ok = code == s.linkCode && System.currentTimeMillis() < s.linkCodeExpiresAt
        if (ok) {
            edit { putLong(K_CHAT, chatId); remove(K_CODE); remove(K_CODE_EXPIRES) }
        } else {
            // 猜錯 5 次就作廢，避免別人亂試
            val attempts = prefs.getInt(K_ATTEMPTS, 0) + 1
            edit { if (attempts >= 5) { remove(K_CODE); remove(K_CODE_EXPIRES) } else putInt(K_ATTEMPTS, attempts) }
        }
        return ok
    }

    override var lastListed: List<String>
        get() = prefs.getString(K_LAST_LIST, "").orEmpty().split(',').filter { it.isNotEmpty() }
        set(value) { prefs.edit().putString(K_LAST_LIST, value.joinToString(",")).apply() }

    override var lastFullListed: List<String>
        get() = prefs.getString(K_FULL_LIST, "").orEmpty().split(',').filter { it.isNotEmpty() }
        set(value) { prefs.edit().putString(K_FULL_LIST, value.joinToString(",")).apply() }

    /** 「訊息編號=id,id;…」，新的在前。 */
    private fun messages(): List<Pair<Long, List<String>>> =
        prefs.getString(K_MESSAGES, "").orEmpty().split(';').mapNotNull { e ->
            val id = e.substringBefore('=').toLongOrNull() ?: return@mapNotNull null
            id to e.substringAfter('=').split(',').filter { it.isNotEmpty() }
        }

    @Synchronized
    override fun rememberMessage(messageId: Long, taskIds: List<String>) {
        if (taskIds.isEmpty()) return
        val all = listOf(messageId to taskIds) + messages().filter { it.first != messageId }
        prefs.edit().putString(K_MESSAGES, all.take(MAX_MESSAGES).joinToString(";") { (m, ids) -> "$m=${ids.joinToString(",")}" }).apply()
    }

    override fun tasksOfMessage(messageId: Long): List<String> = messages().firstOrNull { it.first == messageId }?.second.orEmpty()

    companion object {
        private const val K_TOKEN = "token"
        private const val K_USERNAME = "username"
        private const val K_CHAT = "chat_id"
        private const val K_OFFSET = "offset"
        private const val K_CODE = "link_code"
        private const val K_CODE_EXPIRES = "link_code_expires"
        private const val K_ATTEMPTS = "link_attempts"
        private const val K_LAST_POLL = "last_poll"
        private const val K_LAST_ERROR = "last_error"
        private const val K_LAST_LIST = "last_list"
        private const val K_FULL_LIST = "last_full_list"
        private const val K_MESSAGES = "message_tasks"
        private const val MAX_MESSAGES = 60
        private const val K_REMIND = "remind_in_telegram"
        private const val K_ALWAYS_ON = "always_on"
    }
}
