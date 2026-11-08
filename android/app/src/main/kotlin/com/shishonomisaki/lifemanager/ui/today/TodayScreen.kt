package com.shishonomisaki.lifemanager.ui.today

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.FiberNew
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shishonomisaki.lifemanager.core.model.Defaults
import com.shishonomisaki.lifemanager.core.model.SourceKind
import com.shishonomisaki.lifemanager.core.model.Task
import com.shishonomisaki.lifemanager.core.model.TaskStatus
import com.shishonomisaki.lifemanager.core.stats.TodaySummary
import com.shishonomisaki.lifemanager.ui.Format
import com.shishonomisaki.lifemanager.ui.components.EmptyState
import com.shishonomisaki.lifemanager.ui.components.Lookup
import com.shishonomisaki.lifemanager.ui.components.QuickInputBar
import com.shishonomisaki.lifemanager.ui.components.QuickPreview
import com.shishonomisaki.lifemanager.ui.components.SectionHeader
import com.shishonomisaki.lifemanager.ui.components.SwipeTaskRow
import com.shishonomisaki.lifemanager.ui.components.TaskActions
import com.shishonomisaki.lifemanager.ui.components.TopicFilterRow
import com.shishonomisaki.lifemanager.ui.theme.OverdueRed
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate

/** 今天：摘要卡、逾期、今天到期、沒排時間但進行中的，以及「等待他人」。 */
@Composable
fun TodayScreen(
    tasks: List<Task>,
    lookup: Lookup,
    actions: TaskActions,
    onQuickAdd: (String, SourceKind) -> Unit,
    preview: QuickPreview? = null,
    topicFilter: String? = null,
    onTopicFilter: (String?) -> Unit = {},
    onOpenReview: () -> Unit = {},
) {
    val today = LocalDate.now(Defaults.ZONE)
    val endOfToday = today.plusDays(1).atStartOfDay(Defaults.ZONE).toInstant()
    val startOfToday = today.atStartOfDay(Defaults.ZONE).toInstant()
    // 主題篩選：例如上班時只看工作
    val shown = if (topicFilter == null) tasks else tasks.filter { it.topic == topicFilter }
    val open = shown.filter { it.status == TaskStatus.TODO || it.status == TaskStatus.DOING }
    fun whenOf(t: Task) = t.dueAt ?: t.remindAt
    val overdue = open.filter { whenOf(it)?.isBefore(startOfToday) == true }.sortedBy { whenOf(it) }
    val dueToday = open.filter { val w = whenOf(it); w != null && !w.isBefore(startOfToday) && w.isBefore(endOfToday) }
        .sortedBy { whenOf(it) }
    val doingNoDate = open.filter { it.status == TaskStatus.DOING && whenOf(it) == null }
    val weekEnd = today.plusDays(8).atStartOfDay(Defaults.ZONE).toInstant()
    val upcoming = open.filter { val w = whenOf(it); w != null && !w.isBefore(endOfToday) && w.isBefore(weekEnd) }
        .sortedBy { whenOf(it) }
    // 今天剛新增、沒排時間的，不然新增完在這頁看不到
    val newNoDate = open.filter {
        it.status == TaskStatus.TODO && whenOf(it) == null && it.createdAt?.isBefore(startOfToday) == false
    }.sortedByDescending { it.createdAt }
    val waiting = shown.filter { it.status == TaskStatus.WAITING }.sortedBy { whenOf(it) ?: Instant.MAX }
    val doneToday = shown.filter { it.status == TaskStatus.DONE && it.completedAt?.isBefore(startOfToday) == false }
    val summary = TodaySummary.of(shown, Instant.now(), Defaults.ZONE)
    val reviewCount = tasks.count { it.needsReview && it.status.isOpen }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var collapsed by rememberSaveable { mutableStateOf(setOf("upcoming", "done")) }
    fun toggle(key: String) { collapsed = if (key in collapsed) collapsed - key else collapsed + key }

    fun LazyListScope.section(key: String, title: String, icon: ImageVector, list: List<Task>, tint: Color? = null) {
        if (list.isEmpty()) return
        item(key = "h-$key") {
            SectionHeader(title, list.size, icon, tint ?: MaterialTheme.colorScheme.primary,
                collapsed = key in collapsed, onToggle = { toggle(key) })
        }
        if (key !in collapsed) items(list, key = { "$key-" + it.id }) { SwipeTaskRow(it, lookup, actions, Modifier.animateItem()) }
    }

    Column(Modifier.fillMaxSize()) {
        QuickInputBar(onSubmit = onQuickAdd, preview = preview, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
        TopicFilterRow(lookup.topics, topicFilter, onTopicFilter)
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
            item(key = "summary") {
                SummaryCard(
                    today = today,
                    summary = summary,
                    reviewCount = reviewCount,
                    onOverdue = { scope.launch { listState.animateScrollToItem(1) } },
                    onReview = onOpenReview,
                    onNext = { summary.next?.let(actions.open) },
                )
            }
            section("overdue", "逾期", Icons.Filled.Warning, overdue, OverdueRed)
            item(key = "h-today") { SectionHeader("今天", dueToday.size, Icons.Filled.Today) }
            if (dueToday.isEmpty()) item(key = "empty-today") {
                // 逾期另外有一區，不影響「今天清空了」
                if (summary.done > 0) {
                    EmptyState(Icons.Filled.Celebration, "今天清空了 🎉", if (upcoming.isNotEmpty()) "接下來 7 天有 ${upcoming.size} 項" else null)
                } else {
                    EmptyState(Icons.Filled.Today, "今天沒有到期的事", "左滑完成、右滑改時間；在上面打字或按麥克風新增")
                }
            }
            items(dueToday, key = { "today-" + it.id }) { SwipeTaskRow(it, lookup, actions, Modifier.animateItem()) }
            section("doing", "進行中", Icons.Filled.PlayCircle, doingNoDate)
            section("new", "今天新增・未排時間", Icons.Filled.FiberNew, newNoDate)
            section("upcoming", "接下來 7 天", Icons.Filled.DateRange, upcoming)
            section("waiting", "等待他人", Icons.Filled.HourglassEmpty, waiting)
            section("done", "今天完成", Icons.Filled.CheckCircle, doneToday)
        }
    }
}

/** 頂部摘要：左邊完成進度圈，右邊逾期幾項、下一件事、待確認。 */
@Composable
private fun SummaryCard(
    today: LocalDate,
    summary: TodaySummary,
    reviewCount: Int,
    onOverdue: () -> Unit,
    onReview: () -> Unit,
    onNext: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = MaterialTheme.shapes.large,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(76.dp)) {
                CircularProgressIndicator(
                    progress = { summary.progress },
                    modifier = Modifier.size(76.dp),
                    strokeWidth = 8.dp,
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                    strokeCap = StrokeCap.Round,
                )
                Text("${summary.done}/${summary.total}", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Column(Modifier.weight(1f).padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${today.monthValue} 月 ${today.dayOfMonth} 日（${Format.weekday(today)}）",
                    style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                if (summary.overdue > 0) {
                    Text("⚠ 逾期 ${summary.overdue} 項", style = MaterialTheme.typography.bodyMedium, color = OverdueRed,
                        modifier = Modifier.clickable(onClick = onOverdue))
                }
                val next = summary.next
                val left = summary.total - summary.done - summary.overdue
                Text(
                    when {
                        next != null -> "下一件：${Format.dateTime(next.dueAt ?: next.remindAt!!).substringAfter(' ')} ${next.title}"
                        summary.total == 0 -> "今天沒有排定的事"
                        left > 0 -> "還有 $left 項，時間已經過了"
                        summary.overdue > 0 -> "今天的都完成了，剩逾期的"
                        else -> "今天的都完成了 🎉"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (next != null) Modifier.clickable(onClick = onNext) else Modifier,
                )
                if (reviewCount > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(onClick = onReview)) {
                        Icon(Icons.Filled.FactCheck, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.tertiary)
                        Text(" 待確認 $reviewCount 項，一次看完 ›", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.tertiary)
                    }
                }
            }
        }
    }
}
