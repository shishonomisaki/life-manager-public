package com.shishonomisaki.lifemanager.ui.settings

import android.widget.Toast
import com.shishonomisaki.lifemanager.core.parse.ModelRouting
import com.shishonomisaki.lifemanager.reminder.ReminderScheduler
import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.shishonomisaki.lifemanager.LifeApp
import com.shishonomisaki.lifemanager.core.model.Defaults
import com.shishonomisaki.lifemanager.data.SettingsStore
import com.shishonomisaki.lifemanager.telegram.ClassifyHintsSection
import com.shishonomisaki.lifemanager.telegram.TelegramSection
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** 通知、準時提醒、電池、自動啟動。Zenfone 的省電管理會殺背景程式，不設定提醒可能不會響。 */
@Composable
fun PermissionsPanel() {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }

    val notifOk = remember(refresh) {
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    val exactOk = remember(refresh) { (context.applicationContext as LifeApp).scheduler.canScheduleExact() }
    val soundOk = remember(refresh) { notifOk && ReminderScheduler.channelAudible(context) }
    val batteryOk = remember(refresh) {
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Step("允許通知", "提醒要靠通知跳出來。", notifOk) {
            if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        Step("提醒會響", "「待辦提醒」通知要開聲音和震動。手機切成靜音或勿擾時也不會響。", soundOk) {
            ReminderScheduler.openChannelSettings(context)
        }
        Step("允許準時提醒", "讓鬧鐘在指定的那一分鐘響，不被系統延後。", exactOk) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
            }
        }
        Step("電池設為「不限制」", "ASUS 省電會關掉背景程式，提醒和 Bot 同步會失效。", batteryOk) {
            context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
        }
        Step("允許自動啟動", "到「手機管家 → 自動啟動管理」把「生活管理」打開；找不到就到應用程式資訊裡把電池改成不限制。", null) {
            openAsusAutoStart(context)
        }
        OutlinedButton(onClick = {
            ReminderScheduler.scheduleTest(context)
            Toast.makeText(context, "10 秒後會跳測試提醒，可以先把 APP 滑掉", Toast.LENGTH_LONG).show()
        }) { Text("測試提醒（10 秒後）") }
    }
}

private fun openAsusAutoStart(context: Context) {
    val candidates = listOf(
        Intent().setComponent(ComponentName("com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity")),
        Intent().setComponent(ComponentName("com.asus.mobilemanager", "com.asus.mobilemanager.MainActivity")),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
    )
    for (i in candidates) {
        val ok = runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        if (ok) return
    }
}

@Composable
private fun Step(title: String, desc: String, ok: Boolean?, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (ok) {
            true -> Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
            false -> Icon(Icons.Filled.ErrorOutline, null, tint = Color(0xFFEF6C00))
            null -> Icon(Icons.Filled.ErrorOutline, null, tint = MaterialTheme.colorScheme.outline)
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
        if (ok != true) OutlinedButton(onClick = onClick) { Text("設定") }
    }
}

/** 第一次登入後的引導。 */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("讓提醒準時響", style = MaterialTheme.typography.headlineSmall)
        Text("Zenfone 的省電管理比較嚴格，下面幾項設好，提醒和同步才不會被擋。之後也可以在設定頁再調。",
            style = MaterialTheme.typography.bodyMedium)
        PermissionsPanel()
        // 家人朋友第一次用：AI 金鑰不是必要的，先讓他們放心開始
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("AI 自動整理可以之後再設", style = MaterialTheme.typography.titleSmall)
                Text("不設也能用，分類會改用關鍵字規則。想要 AI 幫忙選主題、專案時，再到「設定 → 自動整理」按「取得金鑰」。",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("好了，開始用") }
    }
}

@Composable
fun SettingsScreen(
    email: String?,
    settings: SettingsStore.Settings,
    onUpdate: ((SettingsStore.Settings) -> SettingsStore.Settings) -> Unit,
    onSignOut: () -> Unit,
    onBack: () -> Unit,
) {
    var key by remember(settings.geminiKey) { mutableStateOf(settings.geminiKey) }
    var model by remember(settings.geminiModel) { mutableStateOf(settings.geminiModel) }
    var strongModel by remember(settings.geminiStrongModel) { mutableStateOf(settings.geminiStrongModel) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
            Text("設定", style = MaterialTheme.typography.titleLarge)
        }
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Section("帳號")
            Text(email ?: "未登入", style = MaterialTheme.typography.bodyMedium)

            HorizontalDivider()
            Section("外觀")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("跟著桌布換顏色", style = MaterialTheme.typography.bodyLarge)
                        Text("關掉就用固定的墨綠色。主題顏色（工作藍、家庭橘…）不受影響。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    }
                    Switch(checked = settings.dynamicColor, onCheckedChange = { on -> onUpdate { it.copy(dynamicColor = on) } })
                }
            }

            HorizontalDivider()
            Section("自動整理（Gemini 免費額度）")
            Text(
                "到 Google AI Studio 申請金鑰後貼在這裡，新增的待辦會自動選主題、專案和標籤。沒設定、沒網路或當天額度用完時，改用內建規則。金鑰只存在這支手機（加密）。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary,
            )
            if (!settings.geminiEnabled) GetKeyCard()
            OutlinedTextField(
                key, { key = it }, Modifier.fillMaxWidth(), label = { Text("Gemini API 金鑰") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text("平常用的模型") }, singleLine = true)
            OutlinedTextField(strongModel, { strongModel = it }, Modifier.fillMaxWidth(), label = { Text("升級用的模型") }, singleLine = true)
            Text(
                "截圖、長文、長語音，或平常的模型失敗、結果沒把握時，改用升級模型重做（每天最多自動 ${ModelRouting.DAILY_STRONG_CAP} 次）。升級欄清空就不升級。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onUpdate { it.copy(geminiKey = key, geminiModel = model, geminiStrongModel = strongModel) } }) { Text("儲存") }
                if (settings.geminiEnabled) TextButton(onClick = { onUpdate { it.copy(geminiKey = "") } }) { Text("移除金鑰") }
            }
            Text(if (settings.geminiEnabled) "狀態：已啟用" else "狀態：未設定（使用規則版）", style = MaterialTheme.typography.labelMedium)
            if (settings.geminiEnabled) UsageCard(strongOn = settings.geminiStrongModel.isNotBlank())

            Text("這些主題不送 Gemini（只用規則版）：", style = MaterialTheme.typography.bodyMedium)
            Defaults.TOPICS.forEach { topic ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = topic in settings.privateTopics, onCheckedChange = { on ->
                        onUpdate { s -> s.copy(privateTopics = if (on) s.privateTopics + topic else s.privateTopics - topic) }
                    })
                    Text(topic)
                }
            }

            HorizontalDivider()
            Section("分類小幫手")
            ClassifyHintsSection()

            HorizontalDivider()
            Section("提醒與省電")
            PermissionsPanel()

            HorizontalDivider()
            Section("Telegram 收件匣")
            TelegramSection()

            HorizontalDivider()
            OutlinedButton(onClick = onSignOut) { Text("登出") }
            Text("生活管理 v0.1.0", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(bottom = 24.dp))
        }
    }
}

/** 還沒設金鑰時：逐步說明＋直接開 AI Studio 的按鈕（給第一次用的家人看）。 */
@Composable
private fun GetKeyCard() {
    val context = LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("怎麼取得金鑰（約 2 分鐘，免費、不用信用卡）", style = MaterialTheme.typography.titleSmall)
            listOf(
                "按下面「取得金鑰」，會用瀏覽器打開 Google AI Studio 的金鑰頁面（英文介面）。",
                "登入 Google 帳號。右上角的頭像可以切換帳號，建議用登入這個 APP 的同一個，之後比較好找。",
                "第一次使用會跳出服務條款，勾選同意後按「Continue」。",
                "在「API Keys」頁面，按右上角的「Create API key」。",
                "如果要你選專案，選現有的或按「Create project」建一個新的（名稱隨意，例如 life-manager），再按建立。",
                "金鑰是一長串英文、數字和符號（開頭每個人不一樣，例如「AIza」或「AQ.」都正常），按旁邊的複製圖示，整串複製。",
                "回到這個 APP，貼到下面「Gemini API 金鑰」欄位，按「儲存」，狀態變成「已啟用」就完成了。",
            ).forEachIndexed { i, step ->
                Text("${i + 1}. $step", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "忘了用哪個帳號申請的：在 AI Studio 右上角切換帳號，金鑰清單裡有東西的那個就是。舊的金鑰放著不會扣錢。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f),
            )
            FilledTonalButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey"))) }
            }) { Text("取得金鑰") }
        }
    }
}

/** 今天用了幾次 Gemini。Google 不提供查剩餘額度，這是 APP（含 Bot）自己數的。 */
@Composable
private fun UsageCard(strongOn: Boolean) {
    val app = LocalContext.current.applicationContext as LifeApp
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val day = remember(tick) { LocalDate.now(SettingsStore.QUOTA_ZONE) }
    val normal = remember(tick) { app.settings.normalUsed(day) }
    val strong = remember(tick) { app.settings.strongUsed(day) }
    val normalOut = remember(tick) { app.settings.quotaExhaustedToday(LocalDate.now(Defaults.ZONE)) }
    val strongOut = remember(tick) { app.settings.strongExhausted(day) }
    val reset = remember(day) {
        day.plusDays(1).atStartOfDay(SettingsStore.QUOTA_ZONE).withZoneSameInstant(Defaults.ZONE)
            .format(DateTimeFormatter.ofPattern("HH:mm"))
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("今天的用量", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { tick++ }) { Text("重新整理") }
            }
            UsageLine("平常的模型", normal, NORMAL_DAILY_FREE, normalOut)
            if (strongOn) UsageLine("升級的模型", strong, STRONG_DAILY_FREE, strongOut)
            Text(
                "這是這支手機（含 Bot）自己數的次數，Google 沒有提供查剩幾次。免費上限是 Google 公布的大約數字，每天 $reset 歸零。用完時會先改用內建規則，隔天自動恢復。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

@Composable
private fun UsageLine(label: String, used: Int, limit: Int, exhausted: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                if (exhausted) "今天額度用完了" else "$used / 約 $limit 次",
                style = MaterialTheme.typography.labelLarge,
                color = if (exhausted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        }
        LinearProgressIndicator(
            progress = { if (exhausted) 1f else (used.toFloat() / limit).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
            color = if (exhausted || used >= limit * 0.9) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
    }
}

// Google 公布的免費額度（每天請求數）：Flash-Lite 約 500、Flash 約 20
private const val NORMAL_DAILY_FREE = 500
private const val STRONG_DAILY_FREE = 20

@Composable
private fun Section(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}
