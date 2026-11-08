package com.shishonomisaki.lifemanager.ui.review

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.shishonomisaki.lifemanager.core.model.Task
import com.shishonomisaki.lifemanager.ui.Format
import com.shishonomisaki.lifemanager.ui.components.EmptyState
import com.shishonomisaki.lifemanager.ui.components.Lookup
import com.shishonomisaki.lifemanager.ui.components.TaskActions
import com.shishonomisaki.lifemanager.ui.components.TaskChips
import com.shishonomisaki.lifemanager.ui.components.confirmTick
import com.shishonomisaki.lifemanager.ui.components.thresholdTick
import com.shishonomisaki.lifemanager.ui.theme.TopicColors

/**
 * 待確認集中處理：AI 沒把握的項目一張一張看。右滑確認、左滑修改；膠囊也可以直接點來改。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    tasks: List<Task>,
    lookup: Lookup,
    actions: TaskActions,
    onConfirm: (Task) -> Unit,
    onBack: () -> Unit,
) {
    val queue = tasks.filter { it.needsReview && it.status.isOpen }.sortedBy { it.createdAt }
    // 這次進來一共要看幾張，用來顯示進度
    var total by remember { mutableIntStateOf(queue.size) }
    if (queue.size > total) total = queue.size
    val view = LocalView.current

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
            Text("待確認", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (queue.isNotEmpty()) Text("${total - queue.size + 1} / $total", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 16.dp))
        }
        if (total > 0) {
            LinearProgressIndicator(
                progress = { (total - queue.size).toFloat() / total },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }
        val task = queue.firstOrNull()
        if (task == null) {
            Spacer(Modifier.height(48.dp))
            EmptyState(Icons.Filled.TaskAlt, "都確認好了 👍", "AI 沒把握的項目會集中到這裡")
            TextButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("回去") }
            return@Column
        }
        Text(
            "右滑確認、左滑修改。下面的分類點一下可以直接改。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        key(task.id) {
            val state = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
                when (v) {
                    SwipeToDismissBoxValue.StartToEnd -> { view.confirmTick(); onConfirm(task) }
                    SwipeToDismissBoxValue.EndToStart -> actions.open(task)
                    SwipeToDismissBoxValue.Settled -> Unit
                }
                false
            })
            LaunchedEffect(state.targetValue) { if (state.targetValue != SwipeToDismissBoxValue.Settled) view.thresholdTick() }
            SwipeToDismissBox(
                state = state,
                modifier = Modifier.padding(horizontal = 16.dp),
                backgroundContent = {
                    val confirm = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
                    Row(
                        Modifier.fillMaxSize()
                            .background(if (confirm) Color(0xFF2E7D32) else Color(0xFF1565C0), MaterialTheme.shapes.large)
                            .padding(horizontal = 24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = if (confirm) Arrangement.Start else Arrangement.End,
                    ) {
                        Icon(if (confirm) Icons.Filled.Check else Icons.Filled.Edit, null, tint = Color.White)
                        Text(if (confirm) " 確認" else " 修改", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    }
                },
            ) {
                ReviewCard(task, lookup, actions)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = { actions.open(task) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Edit, null, Modifier.size(18.dp)); Text(" 修改")
            }
            Button(onClick = { view.confirmTick(); onConfirm(task) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Check, null, Modifier.size(18.dp)); Text(" 沒問題")
            }
        }
    }
}

@Composable
private fun ReviewCard(task: Task, lookup: Lookup, actions: TaskActions) {
    Card(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(task.topic, style = MaterialTheme.typography.labelLarge, color = TopicColors.of(task.topic))
            Text(task.title, style = MaterialTheme.typography.headlineSmall)
            task.notes?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 6)
            }
            (task.dueAt ?: task.remindAt)?.let { Text("🕑 ${Format.dateTime(it)}", style = MaterialTheme.typography.bodyMedium) }
            TaskChips(task, lookup, actions)
            Text(
                "來源：${task.source.kind.label}" + (task.source.confidence?.let { " · AI 信心 ${Format.percent(it)}" } ?: ""),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
