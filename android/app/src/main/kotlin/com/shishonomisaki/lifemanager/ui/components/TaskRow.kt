package com.shishonomisaki.lifemanager.ui.components

import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shishonomisaki.lifemanager.core.model.AutoField
import com.shishonomisaki.lifemanager.core.model.Priority
import com.shishonomisaki.lifemanager.core.model.Project
import com.shishonomisaki.lifemanager.core.model.Tag
import com.shishonomisaki.lifemanager.core.model.Task
import com.shishonomisaki.lifemanager.core.model.TaskStatus
import com.shishonomisaki.lifemanager.ui.Format
import com.shishonomisaki.lifemanager.ui.theme.OverdueRed
import com.shishonomisaki.lifemanager.ui.theme.ReviewYellow
import com.shishonomisaki.lifemanager.ui.theme.ReviewYellowDark
import com.shishonomisaki.lifemanager.ui.theme.TopicColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant

/** 畫面上需要的對照表。 */
data class Lookup(
    val tasks: Map<String, Task> = emptyMap(),
    val projects: Map<String, Project> = emptyMap(),
    val tags: Map<String, Tag> = emptyMap(),
    val topics: List<String> = emptyList(),
)

/** 待辦上各種操作，從 ViewModel 接進來。 */
data class TaskActions(
    val open: (Task) -> Unit,
    val complete: (Task) -> Unit,
    val reopen: (Task) -> Unit,
    val reschedule: (Task) -> Unit,
    val setTopic: (Task, String) -> Unit,
    val setPriority: (Task, Int) -> Unit,
    val setProject: (Task, String?) -> Unit,
    val removeTag: (Task, String) -> Unit,
    val acceptSuggestion: (Task) -> Unit,
    val dismissSuggestion: (Task) -> Unit,
    /** 確認系統自動選的欄位（虛線變實線）。 */
    val confirmAuto: (Task, String) -> Unit,
)

fun parseColor(hex: String): Color = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color.Gray)

/** 標籤膠囊：系統自動選的用虛線框，使用者改過的用實線。 */
@Composable
fun LabelChip(text: String, auto: Boolean, color: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    val base = Modifier
        .padding(end = 4.dp, bottom = 4.dp)
        .let {
            if (auto) it.drawBehind {
                drawRoundRect(
                    color = color,
                    cornerRadius = CornerRadius(size.height / 2),
                    style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f))),
                )
            } else it.border(1.dp, color, shape)
        }
        .background(color.copy(alpha = 0.08f), shape)
        .clickable(onClick = onClick)
        .padding(horizontal = 8.dp, vertical = 2.dp)
    Text(text, modifier = base, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskChips(task: Task, lookup: Lookup, actions: TaskActions, showProject: Boolean = true) {
    val neutral = MaterialTheme.colorScheme.secondary
    FlowRow(Modifier.fillMaxWidth()) {
        // 主題
        var topicMenu by remember { mutableStateOf(false) }
        Box {
            LabelChip(task.topic, AutoField.TOPIC in task.autoTagged, TopicColors.of(task.topic)) { topicMenu = true }
            DropdownMenu(topicMenu, { topicMenu = false }) {
                lookup.topics.forEach { t ->
                    DropdownMenuItem(text = { Text(if (t == task.topic) "✓ $t" else t) }, onClick = { topicMenu = false; actions.setTopic(task, t) })
                }
            }
        }
        // 專案
        task.projectId?.takeIf { showProject }?.let { lookup.projects[it] }?.let { p ->
            var menu by remember { mutableStateOf(false) }
            Box {
                LabelChip("▣ ${p.name}", AutoField.PROJECT in task.autoTagged, neutral) { menu = true }
                DropdownMenu(menu, { menu = false }) {
                    if (AutoField.PROJECT in task.autoTagged) {
                        DropdownMenuItem(text = { Text("✓ 確認") }, onClick = { menu = false; actions.confirmAuto(task, AutoField.PROJECT) })
                    }
                    DropdownMenuItem(text = { Text("移出專案") }, onClick = { menu = false; actions.setProject(task, null) })
                    DropdownMenuItem(text = { Text("編輯…") }, onClick = { menu = false; actions.open(task) })
                }
            }
        }
        // 標籤
        task.tags.mapNotNull { lookup.tags[it] }.forEach { tag ->
            var menu by remember { mutableStateOf(false) }
            Box {
                LabelChip("#${tag.name}", AutoField.TAGS in task.autoTagged, parseColor(tag.color)) { menu = true }
                DropdownMenu(menu, { menu = false }) {
                    if (AutoField.TAGS in task.autoTagged) {
                        DropdownMenuItem(text = { Text("✓ 確認標籤") }, onClick = { menu = false; actions.confirmAuto(task, AutoField.TAGS) })
                    }
                    DropdownMenuItem(text = { Text("移除 #${tag.name}") }, onClick = { menu = false; actions.removeTag(task, tag.id) })
                    DropdownMenuItem(text = { Text("改標籤…") }, onClick = { menu = false; actions.open(task) })
                }
            }
        }
        // 優先度
        if (task.priority > 0) {
            var menu by remember { mutableStateOf(false) }
            Box {
                LabelChip(Priority.label(task.priority), AutoField.PRIORITY in task.autoTagged,
                    if (task.priority == Priority.URGENT) OverdueRed else Color(0xFFEF6C00)) { menu = true }
                DropdownMenu(menu, { menu = false }) {
                    (0..2).forEach { p ->
                        DropdownMenuItem(text = { Text(if (p == task.priority) "✓ ${Priority.label(p)}" else Priority.label(p)) }, onClick = { menu = false; actions.setPriority(task, p) })
                    }
                }
            }
        }
        // AI 建議開新專案
        task.suggestedProject?.let { name ->
            var menu by remember { mutableStateOf(false) }
            Box {
                LabelChip("＋ 開專案「$name」？", true, Color(0xFF6A1B9A)) { menu = true }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("建立專案並放進去") }, onClick = { menu = false; actions.acceptSuggestion(task) })
                    DropdownMenuItem(text = { Text("不用") }, onClick = { menu = false; actions.dismissSuggestion(task) })
                }
            }
        }
    }
}

/** 一筆待辦的圓角卡片：左邊一條主題色，時間在標題下面，逾期用紅色小膠囊。長按可以多選。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TaskRow(
    task: Task,
    lookup: Lookup,
    actions: TaskActions,
    modifier: Modifier = Modifier,
    showChips: Boolean = true,
    now: Instant = Instant.now(),
) {
    val done = task.status == TaskStatus.DONE
    val overdue = !done && task.dueAt?.isBefore(now) == true
    val bg = when {
        task.needsReview && !done -> if (isSystemInDarkTheme()) ReviewYellowDark else ReviewYellow
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    // 打勾先播小動畫再真的完成，不會一按就從眼前消失
    var checking by remember(task.id, task.status) { mutableStateOf(false) }
    val checkScale by animateFloatAsState(if (checking) 1.25f else 1f, label = "check")
    val shownDone = done || checking
    val selection = LocalSelection.current
    val selecting = selection?.active == true
    val selected = selection != null && task.id in selection.ids

    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(MaterialTheme.shapes.medium)
            .combinedClickable(
                onClick = { if (selecting) selection!!.toggle(task.id) else actions.open(task) },
                onLongClick = selection?.let { s -> { view.thresholdTick(); s.toggle(task.id) } },
            ),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else bg,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(5.dp).fillMaxHeight().background(TopicColors.of(task.topic).copy(alpha = if (done) 0.35f else 1f)))
            Column(Modifier.weight(1f).padding(start = 2.dp, end = 12.dp, top = 6.dp, bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (selecting) IconButton(onClick = { selection!!.toggle(task.id) }) {
                        Icon(
                            if (selected) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                            contentDescription = if (selected) "取消選取" else "選取",
                            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        )
                    } else IconButton(onClick = {
                        if (done) actions.reopen(task)
                        else if (!checking) {
                            checking = true
                            view.confirmTick()
                            scope.launch { delay(350); actions.complete(task) }
                        }
                    }) {
                        Icon(
                            if (shownDone) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                            contentDescription = if (done) "改回未完成" else "完成",
                            tint = if (shownDone) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            modifier = Modifier.scale(checkScale),
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            task.title,
                            style = MaterialTheme.typography.bodyLarge,
                            textDecoration = if (shownDone) TextDecoration.LineThrough else null,
                            color = if (shownDone) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val meta = buildList {
                            if (task.needsReview && !done) add("待確認")
                            task.parentId?.let { lookup.tasks[it] }?.let { add("↳ ${it.title}") }
                            if (task.status == TaskStatus.WAITING) add("等：${task.waitingFor ?: "他人"}")
                            if (task.status == TaskStatus.DOING) add("進行中")
                            val subs = lookup.tasks.values.filter { it.parentId == task.id }
                            if (subs.isNotEmpty()) add("子任務 ${subs.count { it.status == TaskStatus.DONE }}/${subs.size}")
                        }
                        if (meta.isNotEmpty()) {
                            Text(meta.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        // 時間放在標題下面一行，標題才有整行寬度；字放大時也不會被擠成「準…」
                        (task.dueAt ?: task.remindAt)?.let { at ->
                            TimePill(at, overdue, task.recurrence != null, Modifier.padding(top = 2.dp))
                        }
                    }
                }
                if (showChips) Row { Spacer(Modifier.width(48.dp)); TaskChips(task, lookup, actions) }
            }
        }
    }
}

/** 標題下面的時間；逾期改成紅色小膠囊。 */
@Composable
private fun TimePill(at: Instant, overdue: Boolean, repeat: Boolean, modifier: Modifier = Modifier) {
    // 逾期用淡紅底紅字，不用整條實心紅，免得比標題還搶眼
    val red = if (isSystemInDarkTheme()) Color(0xFFFF8A80) else OverdueRed
    val color = if (overdue) red else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .background(if (overdue) red.copy(alpha = 0.14f) else Color.Transparent, RoundedCornerShape(50))
            .padding(horizontal = if (overdue) 8.dp else 0.dp, vertical = 2.dp),
    ) {
        Icon(if (repeat) Icons.Filled.Repeat else Icons.Filled.Schedule, null, Modifier.padding(end = 3.dp).size(14.dp), tint = color)
        Text(if (overdue) Format.overdue(at) else Format.dateTime(at), style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
    }
}

/** 完成、確認時的短震動。直接叫震動馬達：Zenfone 關了「觸控回饋」時系統的觸感回饋不會震。 */
fun View.confirmTick() = buzz(40)

/** 滑過門檻時的輕震。 */
fun View.thresholdTick() = buzz(15)

private fun View.buzz(ms: Long) {
    val vibrator = if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)
    }
    if (vibrator?.hasVibrator() != true) return
    runCatching { vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)) }
}

/** 左滑完成、右滑改時間。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeTaskRow(task: Task, lookup: Lookup, actions: TaskActions, modifier: Modifier = Modifier) {
    val view = LocalView.current
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
        when (v) {
            SwipeToDismissBoxValue.EndToStart -> { view.confirmTick(); actions.complete(task) }
            SwipeToDismissBoxValue.StartToEnd -> actions.reschedule(task)
            SwipeToDismissBoxValue.Settled -> Unit
        }
        false
    })
    LaunchedEffect(state.targetValue) { if (state.targetValue != SwipeToDismissBoxValue.Settled) view.thresholdTick() }
    // 多選時不能滑，免得選的時候誤觸完成
    val selecting = LocalSelection.current?.active == true
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        enableDismissFromStartToEnd = !selecting,
        enableDismissFromEndToStart = !selecting,
        backgroundContent = {
            val toEnd = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            val armed = state.targetValue != SwipeToDismissBoxValue.Settled
            val color = if (toEnd) Color(0xFF1565C0) else Color(0xFF2E7D32)
            Row(
                Modifier.fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .background(color.copy(alpha = if (armed) 1f else 0.7f), MaterialTheme.shapes.medium)
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (toEnd) Arrangement.Start else Arrangement.End,
            ) {
                Icon(if (toEnd) Icons.Filled.Schedule else Icons.Filled.Check, null, tint = Color.White,
                    modifier = Modifier.padding(end = 6.dp).scale(if (armed) 1.2f else 1f))
                Text(if (toEnd) "改時間" else "完成", color = Color.White, style = MaterialTheme.typography.titleSmall)
            }
        },
    ) {
        TaskRow(task, lookup, actions)
    }
}

/** 分區標題：小圖示、標題、數量圓點；可以摺疊。 */
@Composable
fun SectionHeader(
    text: String,
    count: Int? = null,
    icon: ImageVector? = null,
    tint: Color = MaterialTheme.colorScheme.primary,
    collapsed: Boolean? = null,
    onToggle: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth()
            .let { if (onToggle != null) it.clickable(onClick = onToggle) else it }
            .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { Icon(it, null, Modifier.padding(end = 6.dp).size(18.dp), tint = tint) }
        Text(text, style = MaterialTheme.typography.titleSmall, color = tint)
        count?.let {
            Text(
                "$it",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(50))
                    .padding(horizontal = 7.dp, vertical = 1.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        collapsed?.let {
            Icon(if (it) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess, contentDescription = if (it) "展開" else "收起",
                tint = MaterialTheme.colorScheme.outline)
        }
    }
}

/** 空狀態：大圖示加一句引導。 */
@Composable
fun EmptyState(icon: ImageVector, title: String, hint: String? = null, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        hint?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
