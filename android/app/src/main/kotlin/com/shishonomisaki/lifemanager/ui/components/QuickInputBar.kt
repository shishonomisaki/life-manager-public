package com.shishonomisaki.lifemanager.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.core.content.ContextCompat
import com.shishonomisaki.lifemanager.core.model.SourceKind
import com.shishonomisaki.lifemanager.core.preview.PreviewField
import com.shishonomisaki.lifemanager.ui.theme.TopicColors
import kotlinx.coroutines.delay
import com.shishonomisaki.lifemanager.speech.SpeechInput

/** 輸入框下方的一顆預覽膠囊。[topic] 有值時用主題色。 */
data class PreviewChip(val field: PreviewField, val label: String, val topic: String? = null)

/**
 * 打字時即時預覽解析結果（本機規則，不花 Gemini 額度）。看起來不對可以點掉，送出時就不套用那一項。
 */
class QuickPreview(
    val chips: suspend (text: String, skip: Set<PreviewField>) -> List<PreviewChip>,
    val submit: (text: String, kind: SourceKind, skip: Set<PreviewField>) -> Unit,
)

/** 一行輸入框＋麥克風。語音轉出的文字先填進框裡，可以改完再送出。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickInputBar(
    onSubmit: (text: String, kind: SourceKind) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "新增待辦，例如「明天10點打給王經理」",
    autoFocus: Boolean = false,
    startVoice: Boolean = false,
    preview: QuickPreview? = null,
) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    var fromVoice by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    val speech = remember {
        SpeechInput(
            context,
            onPartial = { text = it; fromVoice = true },
            onFinal = { text = it; fromVoice = true },
            onStateChange = { listening = it },
            onError = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() },
        )
    }
    DisposableEffect(Unit) { onDispose { speech.release() } }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) speech.start() else Toast.makeText(context, "需要麥克風權限才能語音輸入", Toast.LENGTH_SHORT).show()
    }
    fun mic() {
        when {
            listening -> speech.stop()
            !speech.available -> Toast.makeText(context, "這支手機沒有語音辨識服務", Toast.LENGTH_SHORT).show()
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> speech.start()
            else -> permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    var skip by remember { mutableStateOf(emptySet<PreviewField>()) }
    var chips by remember { mutableStateOf(emptyList<PreviewChip>()) }
    if (preview != null) LaunchedEffect(text, skip) {
        if (text.isBlank()) { chips = emptyList(); skip = emptySet(); return@LaunchedEffect }
        delay(200)
        chips = runCatching { preview.chips(text, skip) }.getOrDefault(emptyList())
    }

    fun submit() {
        if (text.isBlank()) return
        val kind = if (fromVoice) SourceKind.VOICE else SourceKind.MANUAL
        if (preview != null) preview.submit(text, kind, skip) else onSubmit(text, kind)
        text = ""
        fromVoice = false
        skip = emptySet()
        chips = emptyList()
    }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (autoFocus) runCatching { focus.requestFocus() }
        if (startVoice) mic()
    }

    Column(modifier.fillMaxWidth()) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f).focusRequester(focus),
            placeholder = { Text(if (listening) "請說…" else placeholder, style = MaterialTheme.typography.bodyMedium) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            shape = MaterialTheme.shapes.extraLarge,
            trailingIcon = if (text.isNotBlank()) {
                { IconButton(onClick = { submit() }) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "新增", tint = MaterialTheme.colorScheme.primary) } }
            } else null,
        )
        // 語音按鈕放大，聽的時候變紅色並微微脈動
        val pulse = rememberInfiniteTransition(label = "mic")
        val micScale by pulse.animateFloat(1f, 1.12f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "micScale")
        FilledIconButton(
            onClick = { mic() },
            modifier = Modifier.padding(start = 8.dp).size(52.dp).scale(if (listening) micScale else 1f),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = if (listening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primaryContainer,
                contentColor = if (listening) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimaryContainer,
            ),
        ) {
            Icon(if (listening) Icons.Filled.Stop else Icons.Filled.Mic, contentDescription = if (listening) "停止" else "語音輸入",
                modifier = Modifier.size(26.dp))
        }
    }
    if (chips.isNotEmpty()) {
        FlowRow(Modifier.fillMaxWidth().padding(start = 8.dp, top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            chips.forEach { c ->
                val color = if (c.topic != null) TopicColors.of(c.topic) else MaterialTheme.colorScheme.onSurfaceVariant
                InputChip(
                    selected = false,
                    onClick = { skip = skip + c.field },
                    label = { Text(c.label, style = MaterialTheme.typography.labelMedium) },
                    trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "不要這項", Modifier.size(14.dp)) },
                    colors = InputChipDefaults.inputChipColors(labelColor = color, trailingIconColor = color),
                    modifier = Modifier.height(28.dp),
                )
            }
        }
    }
    }
}
