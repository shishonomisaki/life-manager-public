package com.shishonomisaki.lifemanager.share

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.shishonomisaki.lifemanager.LifeApp
import com.shishonomisaki.lifemanager.MainActivity
import com.shishonomisaki.lifemanager.core.model.SourceKind
import com.shishonomisaki.lifemanager.core.model.Task
import com.shishonomisaki.lifemanager.core.model.TaskSource
import com.shishonomisaki.lifemanager.core.parse.ParsedTask
import com.shishonomisaki.lifemanager.core.parse.TimeParser
import com.shishonomisaki.lifemanager.reminder.ReminderSyncWorker
import com.shishonomisaki.lifemanager.telegram.AppBrain
import com.shishonomisaki.lifemanager.telegram.FirestoreInboxStore
import com.shishonomisaki.lifemanager.telegram.InboxEntry
import com.shishonomisaki.lifemanager.telegram.Organizer
import com.shishonomisaki.lifemanager.telegram.Replies
import com.shishonomisaki.lifemanager.ui.theme.LifeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 從 LINE、Telegram、相簿等「分享」進來：文字、截圖、語音、PDF、聊天紀錄 .txt。
 * 跟 Telegram 收件匣用同一套整理（Gemini 為主、規則版備援），馬上建立待辦，可以當場刪掉整理錯的。
 */
class ShareActivity : ComponentActivity() {

    private sealed interface UiState {
        data object Working : UiState
        data class Done(
            val lines: List<Pair<Task, String>>,
            val deleted: Set<String> = emptySet(),
            val note: String?,
            val inbox: InboxEntry,
            val text: String,
        ) : UiState
        data class Message(val text: String) : UiState
    }

    private val state = MutableStateFlow<UiState>(UiState.Working)
    private val app get() = application as LifeApp
    private val store by lazy { FirestoreInboxStore(app) }
    private val organizer by lazy { Organizer(store, AppBrain(app)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!app.repository.isSignedIn) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }
        setContent { LifeTheme { Sheet() } }
        if (savedInstanceState == null) start(intent)
    }

    private fun start(intent: Intent) {
        lifecycleScope.launch {
            // 分享來的檔案權限只在這個畫面開著時有效，先全部讀進來
            val (text, files) = withContext(Dispatchers.IO) { read(intent) }
            if (text.isBlank() && files.isEmpty()) {
                state.value = UiState.Message("這種內容還不能整理。目前看得懂文字、截圖和照片、語音、PDF。")
                return@launch
            }
            // 整理放在 APP 層級，畫面先關掉也會做完
            app.appScope.launch {
                state.value = runCatching {
                    val out = organizer.organize(Organizer.Input(text, null, files, SourceKind.SHARE, "分享來的"))
                    ReminderSyncWorker.runOnce(app)
                    val l = Replies.Lookup(out.ctx.projects, out.ctx.tags, out.ctx.now.zone, out.ctx.now.toLocalDate())
                    UiState.Done(out.items.map { (t, subs) -> t to describe(t, subs, l) }, note = out.notice, inbox = out.inbox, text = text)
                }.getOrElse { UiState.Message("整理失敗：${it.message}") }
            }
        }
    }

    private fun describe(t: Task, subs: List<Task>, l: Replies.Lookup) = buildString {
        append(Replies.line(t, l))
        if (subs.isNotEmpty()) append("\n└ ").append(subs.joinToString("、") { it.title })
        if (t.needsReview) append("\n⚠️ 不太確定，APP 裡標成「待確認」")
        t.suggestedProject?.let { append("\n💡 建議開專案「").append(it).append("」") }
    }

    private fun read(intent: Intent): Pair<String, List<Organizer.Attachment>> {
        val texts = mutableListOf<String>()
        val body = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim()?.takeIf { it.isNotEmpty() && it !in body }?.let { texts += it }
        if (body.isNotEmpty()) texts += body
        val files = mutableListOf<Organizer.Attachment>()
        for (uri in streams(intent)) {
            val mime = contentResolver.getType(uri) ?: intent.type ?: continue
            val bytes = runCatching {
                contentResolver.openInputStream(uri)?.use { readAtMost(it, Organizer.MAX_FILE_BYTES.toInt() + 1) }
            }.getOrNull() ?: continue
            if (bytes.size > Organizer.MAX_FILE_BYTES) continue
            if (mime.startsWith("text/")) {
                // 例如 LINE「傳送聊天紀錄」的 .txt
                texts += String(bytes, Charsets.UTF_8).take(20_000)
                continue
            }
            val kind = Organizer.Kind.of(mime) ?: continue
            files += Organizer.Attachment(mime, kind) { bytes }
        }
        return texts.joinToString("\n").trim() to files
    }

    private fun readAtMost(input: java.io.InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (out.size() < limit) {
            val n = input.read(buf, 0, minOf(buf.size, limit - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    @Suppress("DEPRECATION")
    private fun streams(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND ->
            listOfNotNull(
                if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_STREAM),
            )
        Intent.ACTION_SEND_MULTIPLE ->
            (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()
        else -> emptyList()
    }

    private fun delete(task: Task) {
        lifecycleScope.launch { store.deleteTask(task.id) }
        val s = state.value as? UiState.Done ?: return
        state.value = s.copy(deleted = s.deleted + task.id)
    }

    /** 整理不出待辦時，整段當一筆。 */
    private fun forceCreate(s: UiState.Done) {
        lifecycleScope.launch {
            val ctx = store.parseContext(java.time.ZonedDateTime.now(com.shishonomisaki.lifemanager.core.model.Defaults.ZONE))
            val first = s.text.lines().firstOrNull { it.isNotBlank() }?.trim()
            val parsed = first?.let { TimeParser.parse(it, ctx.now) }
            val at = parsed?.at?.toInstant()
            val p = ParsedTask(
                title = parsed?.title?.take(40) ?: "看分享來的內容",
                notes = s.text.takeIf { it.length > (first?.length ?: 0) },
                dueAt = at, remindAt = at,
                classification = AppBrain(app).rules.classify(s.text.ifEmpty { "內容" }, null, ctx),
                confidence = 0.5,
            )
            val (task, _) = organizer.build(p, TaskSource(SourceKind.SHARE, s.inbox.id), null, null)
            store.createTask(task)
            store.saveInbox(s.inbox.copy(status = InboxEntry.PARSED, taskIds = listOf(task.id)))
            val l = Replies.Lookup(ctx.projects, ctx.tags, ctx.now.zone, ctx.now.toLocalDate())
            state.value = s.copy(lines = listOf(task to describe(task, emptyList(), l)))
        }
    }

    @Composable
    private fun Sheet() {
        val s by state.collectAsState()
        Box(
            Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null) { finish() },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, null) {},
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            ) {
                Column(
                    Modifier.padding(20.dp).navigationBarsPadding().heightIn(max = 520.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when (val st = s) {
                        UiState.Working -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(24.dp))
                            Text("整理中…", Modifier.padding(start = 12.dp), style = MaterialTheme.typography.titleMedium)
                        }
                        is UiState.Message -> {
                            Text(st.text, style = MaterialTheme.typography.bodyLarge)
                            Button(onClick = { finish() }) { Text("關閉") }
                        }
                        is UiState.Done -> Result(st)
                    }
                }
            }
        }
    }

    @Composable
    private fun ColumnScope.Result(s: UiState.Done) {
        val live = s.lines.filter { it.first.id !in s.deleted }
        Text(
            when {
                s.lines.isEmpty() -> "看起來沒有要做的事"
                live.isEmpty() -> "已全部刪除"
                else -> "已建立 ${live.size} 項"
            },
            style = MaterialTheme.typography.titleMedium,
        )
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            live.forEach { (task, line) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(line, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    IconButton(onClick = { delete(task) }) { Icon(Icons.Filled.Delete, contentDescription = "刪除") }
                }
            }
            s.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (s.lines.isEmpty()) {
                Button(onClick = { forceCreate(s) }) { Text("還是建立成待辦") }
                OutlinedButton(onClick = { finish() }) { Text("不用了") }
            } else {
                Button(onClick = { finish() }) { Text("好") }
                OutlinedButton(onClick = {
                    startActivity(Intent(this@ShareActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    finish()
                }) { Text("開啟 APP 修改") }
            }
        }
    }
}
