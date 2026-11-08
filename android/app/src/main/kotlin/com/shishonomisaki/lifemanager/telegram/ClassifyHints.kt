package com.shishonomisaki.lifemanager.telegram

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.shishonomisaki.lifemanager.core.model.Defaults
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 分類線索，只存在這支手機：每個主題的關鍵字（設定頁填），和在 Bot 改過的分類（自動記）。
 * Bot 和「分享給 APP」整理時會參考，見 [TopicHints]。
 */
class ClassifyHints private constructor(context: Context) {

    private val prefs = context.getSharedPreferences("classify_hints", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<TopicHints> = _state.asStateFlow()

    fun hints(): TopicHints = _state.value

    private fun read(): TopicHints {
        val keywords = Defaults.TOPICS.associateWith { topic -> split(prefs.getString(K_WORDS + topic, "").orEmpty()) }
            .filterValues { it.isNotEmpty() }
        val learned = prefs.getString(K_LEARNED, "").orEmpty().lines().mapNotNull { line ->
            val topic = line.substringBefore('\t', "")
            val title = line.substringAfter('\t', "")
            if (topic.isEmpty() || title.isEmpty()) null else title to topic
        }
        return TopicHints(keywords, learned)
    }

    fun setKeywords(topic: String, text: String) {
        prefs.edit().putString(K_WORDS + topic, split(text).joinToString("、")).apply()
        _state.value = read()
    }

    /** 記下一次改分類；同一個標題只留最新的，最多 50 筆。 */
    @Synchronized
    fun learn(title: String, topic: String) {
        val clean = title.replace('\n', ' ').replace('\t', ' ').trim().take(40)
        if (clean.isEmpty()) return
        val list = (listOf(clean to topic) + hints().learned.filter { it.first != clean }).take(50)
        prefs.edit().putString(K_LEARNED, list.joinToString("\n") { "${it.second}\t${it.first}" }).apply()
        _state.value = read()
    }

    fun clearLearned() {
        prefs.edit().remove(K_LEARNED).apply()
        _state.value = read()
    }

    companion object {
        private const val K_WORDS = "words_"
        private const val K_LEARNED = "learned"

        private fun split(text: String) = text.split(Regex("[、,，;；\\s]+")).map { it.trim() }.filter { it.length >= 2 }.distinct()

        @Volatile private var instance: ClassifyHints? = null

        fun get(context: Context): ClassifyHints =
            instance ?: synchronized(this) { instance ?: ClassifyHints(context.applicationContext).also { instance = it } }
    }
}

/** 設定頁「分類小幫手」：每個主題填幾個關鍵字，和 Bot 學到的分類。 */
@Composable
fun ClassifyHintsSection() {
    val context = LocalContext.current
    val store = remember { ClassifyHints.get(context) }
    val hints by store.state.collectAsState()
    val drafts = remember { mutableStateMapOf<String, String>() }
    var saved by remember { mutableStateOf(false) }
    val hint = MaterialTheme.colorScheme.secondary

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "填一些只有你知道的字，Bot 和分享整理時會優先照這個分類，例如工作填公司、客戶、同事的名字。用逗號或空白分開。\n" +
                "也可以在內容開頭講「工作，…」或加「#工作」直接指定。",
            style = MaterialTheme.typography.bodySmall, color = hint,
        )
        Defaults.TOPICS.forEach { topic ->
            OutlinedTextField(
                value = drafts[topic] ?: hints.keywords[topic].orEmpty().joinToString("、"),
                onValueChange = { drafts[topic] = it; saved = false },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("$topic 的關鍵字") },
                singleLine = true,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = drafts.isNotEmpty(), onClick = {
                drafts.forEach { (topic, text) -> store.setKeywords(topic, text) }
                drafts.clear()
                saved = true
            }) { Text("儲存關鍵字") }
            if (saved) Text("已儲存", style = MaterialTheme.typography.bodySmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (hints.learned.isEmpty()) "還沒有在 Bot 改過分類。" else "已記住 ${hints.learned.size} 筆你改過的分類，類似的內容會自動照做。",
                style = MaterialTheme.typography.bodySmall, color = hint, modifier = Modifier.weight(1f),
            )
            if (hints.learned.isNotEmpty()) TextButton(onClick = { store.clearLearned() }) { Text("清除") }
        }
    }
}
