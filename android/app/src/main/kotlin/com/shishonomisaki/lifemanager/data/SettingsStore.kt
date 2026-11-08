package com.shishonomisaki.lifemanager.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.shishonomisaki.lifemanager.core.parse.GeminiTaskParser
import com.shishonomisaki.lifemanager.core.parse.ModelRouting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate

/** 只存在這支手機的設定。金鑰用 Android Keystore 加密，不上傳。 */
class SettingsStore(context: Context) {

    data class Settings(
        val geminiKey: String = "",
        val geminiModel: String = GeminiTaskParser.DEFAULT_MODEL,
        /** 截圖、長文、平常的模型失敗或結果不可靠時升級用的模型。 */
        val geminiStrongModel: String = ModelRouting.STRONG,
        /** 這些主題的待辦不送 Gemini，只用規則版。 */
        val privateTopics: Set<String> = emptySet(),
        val onboardingDone: Boolean = false,
        /** 跟著桌布換色（Material You），關掉用固定的墨綠。 */
        val dynamicColor: Boolean = false,
    ) {
        val geminiEnabled: Boolean get() = geminiKey.isNotBlank()
    }

    private val prefs: SharedPreferences = runCatching {
        EncryptedSharedPreferences.create(
            context,
            "secure_settings",
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse {
        // Keystore 壞掉（極少見）時清掉重來，不讓 APP 打不開
        context.deleteSharedPreferences("secure_settings")
        context.getSharedPreferences("settings_fallback", Context.MODE_PRIVATE)
    }

    private val _state = MutableStateFlow(read())
    val state: StateFlow<Settings> = _state.asStateFlow()
    val current: Settings get() = _state.value

    private fun read() = Settings(
        geminiKey = prefs.getString(K_GEMINI_KEY, "").orEmpty(),
        geminiModel = prefs.getString(K_GEMINI_MODEL, null)
            // 舊版預設是 Flash：還沒存過升級模型的，平常改用 Lite、Flash 留給升級
            ?.takeUnless { it == ModelRouting.STRONG && !prefs.contains(K_GEMINI_STRONG_MODEL) }
            ?: GeminiTaskParser.DEFAULT_MODEL,
        geminiStrongModel = prefs.getString(K_GEMINI_STRONG_MODEL, null) ?: ModelRouting.STRONG,
        privateTopics = prefs.getStringSet(K_PRIVATE_TOPICS, emptySet()).orEmpty(),
        onboardingDone = prefs.getBoolean(K_ONBOARDING, false),
        dynamicColor = prefs.getBoolean(K_DYNAMIC_COLOR, false),
    )

    fun update(block: (Settings) -> Settings) {
        val s = block(_state.value)
        prefs.edit()
            .putString(K_GEMINI_KEY, s.geminiKey.trim())
            .putString(K_GEMINI_MODEL, s.geminiModel.trim().ifEmpty { GeminiTaskParser.DEFAULT_MODEL })
            .putString(K_GEMINI_STRONG_MODEL, s.geminiStrongModel.trim())
            .putStringSet(K_PRIVATE_TOPICS, s.privateTopics)
            .putBoolean(K_ONBOARDING, s.onboardingDone)
            .putBoolean(K_DYNAMIC_COLOR, s.dynamicColor)
            .apply()
        _state.value = read()
    }

    /** Gemini 今天額度用完了嗎（用完當天改用規則版）。 */
    fun quotaExhaustedToday(today: LocalDate): Boolean = prefs.getString(K_QUOTA_DAY, null) == today.toString()

    fun markQuotaExhausted(today: LocalDate) { prefs.edit().putString(K_QUOTA_DAY, today.toString()).apply() }

    /** 平常的模型今天（Google 的一天，太平洋時間）送了幾次請求。APP 自己數，Google 查不到剩幾次。 */
    fun normalUsed(day: LocalDate): Int =
        if (prefs.getString(K_NORMAL_DAY, null) == day.toString()) prefs.getInt(K_NORMAL_COUNT, 0) else 0

    @Synchronized
    fun addNormalUse(day: LocalDate) {
        prefs.edit().putString(K_NORMAL_DAY, day.toString()).putInt(K_NORMAL_COUNT, normalUsed(day) + 1).apply()
    }

    /** 強模型今天（Google 的一天，太平洋時間）用了幾次。 */
    fun strongUsed(day: LocalDate): Int =
        if (prefs.getString(K_STRONG_DAY, null) == day.toString()) prefs.getInt(K_STRONG_COUNT, 0) else 0

    fun addStrongUse(day: LocalDate) {
        prefs.edit().putString(K_STRONG_DAY, day.toString()).putInt(K_STRONG_COUNT, strongUsed(day) + 1).apply()
    }

    fun strongExhausted(day: LocalDate): Boolean = prefs.getString(K_STRONG_QUOTA_DAY, null) == day.toString()

    fun markStrongExhausted(day: LocalDate) { prefs.edit().putString(K_STRONG_QUOTA_DAY, day.toString()).apply() }

    companion object {
        private const val K_GEMINI_KEY = "gemini_key"
        private const val K_GEMINI_MODEL = "gemini_model"
        private const val K_PRIVATE_TOPICS = "private_topics"
        private const val K_ONBOARDING = "onboarding_done"
        private const val K_DYNAMIC_COLOR = "dynamic_color"
        private const val K_QUOTA_DAY = "gemini_quota_day"
        private const val K_GEMINI_STRONG_MODEL = "gemini_strong_model"
        private const val K_STRONG_DAY = "gemini_strong_day"
        private const val K_STRONG_COUNT = "gemini_strong_count"
        private const val K_STRONG_QUOTA_DAY = "gemini_strong_quota_day"
        private const val K_NORMAL_DAY = "gemini_normal_day"
        private const val K_NORMAL_COUNT = "gemini_normal_count"

        /** Gemini 免費額度的一天從太平洋時間午夜算起。 */
        val QUOTA_ZONE: java.time.ZoneId = java.time.ZoneId.of("America/Los_Angeles")
    }
}
