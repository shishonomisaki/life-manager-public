package com.shishonomisaki.lifemanager.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.DonutLarge
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.shishonomisaki.lifemanager.core.model.Defaults
import com.shishonomisaki.lifemanager.core.model.SourceKind
import com.shishonomisaki.lifemanager.core.model.Task
import com.shishonomisaki.lifemanager.core.stats.WeekStats
import com.shishonomisaki.lifemanager.core.stats.WeeklyStats
import com.shishonomisaki.lifemanager.ui.Format
import com.shishonomisaki.lifemanager.ui.components.EmptyState
import com.shishonomisaki.lifemanager.ui.components.Lookup
import com.shishonomisaki.lifemanager.ui.components.SectionHeader
import com.shishonomisaki.lifemanager.ui.components.TaskActions
import com.shishonomisaki.lifemanager.ui.components.TaskRow
import com.shishonomisaki.lifemanager.ui.theme.TopicColors
import java.time.Instant
import java.time.LocalDate

/** 統計：本週數字、連續完成天數、完成趨勢、主題比例、各專案本週推進、每週回顧。 */
@Composable
fun StatsScreen(tasks: List<Task>, lookup: Lookup, actions: TaskActions) {
    val now = Instant.now()
    val history = remember(tasks) { WeeklyStats.history(tasks, now, Defaults.ZONE) }
    val week = history.last()
    val streak = remember(tasks) { WeeklyStats.streak(tasks, LocalDate.now(Defaults.ZONE), Defaults.ZONE) }
    val unsorted = tasks.filter { it.status.isOpen && it.projectId == null && it.tags.isEmpty() && it.parentId == null }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
        item {
            Text(
                "本週（${week.weekStart.monthValue}/${week.weekStart.dayOfMonth} 起）",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(16.dp),
            )
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat("新增", "${week.created}", Modifier.weight(1f))
                Stat("完成", "${week.completed}", Modifier.weight(1f))
                Stat("完成率", Format.percent(week.completionRate), Modifier.weight(1f))
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat("準時完成", Format.percent(week.onTimeRate), Modifier.weight(1f))
                Stat("逾期未完成", "${week.overdueOpen}", Modifier.weight(1f))
                Stat("語音／收件匣", sourceShare(week), Modifier.weight(1f))
            }
        }
        item { StreakCard(streak) }
        item { SectionHeader("完成趨勢（近 8 週）", icon = Icons.AutoMirrored.Filled.ShowChart) }
        item { TrendChart(history, Modifier.fillMaxWidth().height(180.dp).padding(horizontal = 16.dp)) }
        if (week.completedByTopic.isNotEmpty()) {
            item { SectionHeader("本週完成（依主題）", icon = Icons.Filled.DonutLarge) }
            item { TopicDonut(lookup.topics, week.completedByTopic) }
        }
        if (week.completedByProject.isNotEmpty()) {
            item { SectionHeader("各專案本週推進") }
            items(week.completedByProject.entries.sortedByDescending { it.value }.toList(), key = { it.key }) { (id, n) ->
                Text("${lookup.projects[id]?.name ?: "（已刪除的專案）"}：完成 $n 項", modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
            }
        }
        item { SectionHeader("每週回顧：沒有專案也沒有標籤", unsorted.size) }
        if (unsorted.isEmpty()) item { EmptyState(Icons.Filled.TaskAlt, "全部都歸類好了 👍") }
        items(unsorted, key = { it.id }) { TaskRow(it, lookup, actions) }
    }
}

private fun sourceShare(w: WeekStats): String {
    if (w.created == 0) return "—"
    val voice = w.createdBySource[SourceKind.VOICE] ?: 0
    // 收件匣：Telegram Bot 和分享進來的
    val bot = (w.createdBySource[SourceKind.TELEGRAM] ?: 0) + (w.createdBySource[SourceKind.SHARE] ?: 0)
    return "$voice／$bot"
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StreakCard(streak: Int) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("🔥", style = MaterialTheme.typography.headlineMedium)
            Column(Modifier.padding(start = 12.dp)) {
                Text(if (streak > 0) "連續 $streak 天有完成事情" else "今天完成一件，開始累積連續天數",
                    style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text("每天至少完成一項就算", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
            }
        }
    }
}

/** 每週完成數的折線，點上標數字；本週那點加大。 */
@Composable
private fun TrendChart(history: List<WeekStats>, modifier: Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val max = (history.maxOfOrNull { it.completed } ?: 0).coerceAtLeast(1)
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().weight(1f).padding(vertical = 8.dp)) {
            val n = history.size
            if (n == 0) return@Canvas
            val slot = size.width / n
            fun pt(i: Int) = Offset(slot * i + slot / 2, size.height * (1f - history[i].completed.toFloat() / max))
            drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
            val path = Path().apply {
                history.indices.forEach { i -> val p = pt(i); if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
            }
            val fill = Path().apply {
                addPath(path)
                lineTo(pt(n - 1).x, size.height)
                lineTo(pt(0).x, size.height)
                close()
            }
            drawPath(fill, Brush.verticalGradient(listOf(line.copy(alpha = 0.25f), Color.Transparent)))
            drawPath(path, line, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            history.indices.forEach { i ->
                drawCircle(line, radius = (if (i == n - 1) 6 else 4).dp.toPx(), center = pt(i))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            history.forEach { s ->
                Text(
                    "${s.completed}\n${s.weekStart.monthValue}/${s.weekStart.dayOfMonth}",
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 主題比例圓環，右邊列各主題數量。 */
@Composable
private fun TopicDonut(topics: List<String>, counts: Map<String, Int>) {
    val total = counts.values.sum().coerceAtLeast(1)
    val ordered = topics.filter { (counts[it] ?: 0) > 0 } + counts.keys.filter { it !in topics }
    val colors = ordered.map { TopicColors.of(it) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(120.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(120.dp)) {
                var start = -90f
                val stroke = 18.dp.toPx()
                ordered.forEachIndexed { i, t ->
                    val sweep = 360f * (counts[t] ?: 0) / total
                    drawArc(colors[i], start, sweep - 2f, useCenter = false,
                        topLeft = Offset(stroke / 2, stroke / 2), size = Size(size.width - stroke, size.height - stroke),
                        style = Stroke(width = stroke))
                    start += sweep
                }
            }
            Text("${counts.values.sum()}", style = MaterialTheme.typography.headlineSmall)
        }
        Column(Modifier.padding(start = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ordered.forEachIndexed { i, t ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(colors[i], CircleShape))
                    Text("  $t  ${counts[t]}（${Format.percent((counts[t] ?: 0).toDouble() / total)}）", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
