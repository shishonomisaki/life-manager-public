package com.shishonomisaki.lifemanager.telegram

import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** 設定頁的「Telegram 收件匣」：貼上 Bot 金鑰 → 連結 → 之後就自動收訊息。 */
@Composable
fun TelegramSection() {
    val context = LocalContext.current
    val service = remember { TelegramService.get(context) }
    val s by service.settings.state.collectAsState()
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val hint = MaterialTheme.colorScheme.secondary

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "在 LINE、Telegram、相簿按「分享」→「加入待辦」就能直接整理成待辦，不用設定 Bot。\n\nBot 是選用的：想從電腦傳，或在 Telegram 對話裡改，再設定。APP 開著時馬上處理，背景時大約每 15 分鐘一次。",
            style = MaterialTheme.typography.bodySmall, color = hint,
        )

        if (!s.hasToken) {
            Text(
                "1. 在 Telegram 搜尋 @BotFather，傳 /newbot。\n" +
                    "2. 照指示取名字，帳號名稱要用 bot 結尾（例如 my_inbox_bot）。\n" +
                    "3. 它會回一串金鑰（像 123456789:AAH...），整串複製貼在下面。",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                token, { token = it }, Modifier.fillMaxWidth(), label = { Text("Bot 金鑰") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            Button(enabled = token.isNotBlank() && !busy, onClick = {
                busy = true
                message = null
                scope.launch {
                    message = runCatching { service.connect(token) }.fold(
                        { "已連上 @$it，下一步按「連結 Telegram」。" },
                        { e -> if (e is TelegramException && e.badToken) "金鑰不對，請整串重新複製。" else "連不上 Telegram：${e.message}" },
                    )
                    token = ""
                    busy = false
                }
            }) { Text(if (busy) "檢查中…" else "儲存") }
        } else {
            Text("Bot：@${s.botUsername}", style = MaterialTheme.typography.bodyMedium)
            if (!s.linked) {
                val codeValid = s.linkCode != null && System.currentTimeMillis() < s.linkCodeExpiresAt
                Button(onClick = {
                    val code = service.settings.newLinkCode()
                    val open = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/${s.botUsername}?start=$code"))
                    runCatching { context.startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }) { Text("連結 Telegram") }
                if (codeValid) {
                    Text(
                        "在 Telegram 按「開始」後回到這個 APP 就完成連結。沒有自動帶入的話，傳 /link ${s.linkCode} 給 Bot（10 分鐘內有效）。",
                        style = MaterialTheme.typography.bodySmall, color = hint,
                    )
                } else {
                    Text("會打開你的 Bot，按「開始」就好。", style = MaterialTheme.typography.bodySmall, color = hint)
                }
            } else {
                Text("狀態：已連結 ✅", style = MaterialTheme.typography.labelMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = s.remindInTelegram, onCheckedChange = { service.settings.setRemindInTelegram(it) })
                    Text("提醒響時也發到 Telegram（可在 Bot 裡按完成或延後）", style = MaterialTheme.typography.bodyMedium)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = s.alwaysOn, onCheckedChange = { on ->
                        service.settings.setAlwaysOn(on)
                        if (on) InboxConnectionService.start(context) else InboxConnectionService.stop(context)
                    })
                    Text("持續連線：從筆電傳訊息、按按鈕幾秒內就處理（通知列會常駐一個圖示）", style = MaterialTheme.typography.bodyMedium)
                }
            }
            val last = if (s.lastPollAt == 0L) "還沒收過" else DateUtils.getRelativeTimeSpanString(s.lastPollAt).toString()
            Text("上次收訊息：$last", style = MaterialTheme.typography.bodySmall, color = hint)
            s.lastError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFFEF6C00)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    TelegramWorker.runOnce(context)
                    message = "已在背景收訊息。"
                }) { Text("立即收訊息") }
                if (s.linked) TextButton(onClick = { InboxConnectionService.stop(context); service.settings.unlink() }) { Text("解除連結") }
                TextButton(onClick = { InboxConnectionService.stop(context); service.settings.clearBot() }) { Text("移除 Bot") }
            }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
