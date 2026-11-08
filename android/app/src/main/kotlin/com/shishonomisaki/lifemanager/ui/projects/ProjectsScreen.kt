package com.shishonomisaki.lifemanager.ui.projects

import android.content.ClipData
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ViewKanban
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shishonomisaki.lifemanager.core.model.Defaults
import com.shishonomisaki.lifemanager.core.model.Project
import com.shishonomisaki.lifemanager.core.model.ProjectStatus
import com.shishonomisaki.lifemanager.core.model.SourceKind
import com.shishonomisaki.lifemanager.core.model.Task
import com.shishonomisaki.lifemanager.core.model.TaskStatus
import com.shishonomisaki.lifemanager.ui.Format
import com.shishonomisaki.lifemanager.ui.components.DateTimePickerDialog
import com.shishonomisaki.lifemanager.ui.components.EmptyState
import com.shishonomisaki.lifemanager.ui.components.Lookup
import com.shishonomisaki.lifemanager.ui.components.QuickInputBar
import com.shishonomisaki.lifemanager.ui.components.TaskActions
import com.shishonomisaki.lifemanager.ui.components.TaskChips
import com.shishonomisaki.lifemanager.ui.theme.OverdueRed
import com.shishonomisaki.lifemanager.ui.theme.TopicColors
import java.time.Instant

data class ProjectProgress(val project: Project, val done: Int, val total: Int, val next: Task?)

fun progressOf(project: Project, tasks: List<Task>): ProjectProgress {
    val mine = tasks.filter { it.projectId == project.id && it.status != TaskStatus.CANCELLED }
    val open = mine.filter { it.status.isOpen }
    val next = open.minWithOrNull(compareBy<Task>({ it.dueAt ?: Instant.MAX }, { it.createdAt ?: Instant.MAX }))
    return ProjectProgress(project, mine.count { it.status == TaskStatus.DONE }, mine.size, next)
}

/** 專案列表：進度條、期限、下一步。 */
@Composable
fun ProjectsScreen(
    projects: List<Project>,
    tasks: List<Task>,
    onOpen: (Project) -> Unit,
    onCreate: (Project) -> Unit,
) {
    var showDone by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    val visible = projects.filter { if (showDone) true else it.status == ProjectStatus.ACTIVE || it.status == ProjectStatus.PAUSED }
        .map { progressOf(it, tasks) }
        .sortedWith(compareBy({ it.project.status != ProjectStatus.ACTIVE }, { it.project.dueAt ?: Instant.MAX }))

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("專案 ${visible.size}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    FilterChip(selected = showDone, onClick = { showDone = !showDone }, label = { Text("含已完成") })
                }
            }
            if (visible.isEmpty()) item {
                EmptyState(Icons.Filled.ViewKanban, "還沒有專案",
                    "像「搬家」「Q4 報價」這種要好幾步的事，開成專案拆子任務比較好追。按右下角 ＋ 新增。")
            }
            items(visible, key = { it.project.id }) { p -> ProjectCard(p) { onOpen(p.project) } }
        }
        FloatingActionButton(onClick = { creating = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            Icon(Icons.Filled.Add, contentDescription = "新增專案")
        }
    }
    if (creating) ProjectDialog(null, onDismiss = { creating = false }, onSave = { onCreate(it); creating = false })
}

@Composable
private fun ProjectCard(p: ProjectProgress, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = MaterialTheme.shapes.large,
    ) {
        val topicColor = TopicColors.of(p.project.topic)
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(5.dp).fillMaxHeight().background(topicColor))
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.project.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(p.project.topic, style = MaterialTheme.typography.labelMedium, color = topicColor)
                }
                p.project.goal?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }
                LinearProgressIndicator(
                    progress = { if (p.total == 0) 0f else p.done.toFloat() / p.total },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    color = topicColor,
                    trackColor = topicColor.copy(alpha = 0.18f),
                )
                Row {
                    Text("${p.done}/${p.total} 完成", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    p.project.dueAt?.let {
                        Text("期限 ${Format.date(it)}", style = MaterialTheme.typography.labelMedium,
                            color = if (it.isBefore(Instant.now()) && p.project.status == ProjectStatus.ACTIVE) OverdueRed else MaterialTheme.colorScheme.secondary)
                    }
                    if (p.project.status != ProjectStatus.ACTIVE) {
                        Text(" · ${p.project.status.label}", style = MaterialTheme.typography.labelMedium)
                    }
                }
                p.next?.let { Text("下一步：${it.title}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProjectDialog(
    existing: Project?,
    onDismiss: () -> Unit,
    onSave: (Project) -> Unit,
    onDelete: (() -> Unit)? = null,
    initialTopic: String? = null,
) {
    var p by remember { mutableStateOf(existing ?: Project(name = "", topic = initialTopic ?: Defaults.DEFAULT_TOPIC)) }
    var pickDue by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "新增專案" else "編輯專案") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(p.name, { p = p.copy(name = it) }, label = { Text("名稱") }, singleLine = true)
                OutlinedTextField(p.goal.orEmpty(), { p = p.copy(goal = it.ifEmpty { null }) }, label = { Text("要達成什麼（選填）") })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Defaults.TOPICS.forEach { t -> FilterChip(selected = p.topic == t, onClick = { p = p.copy(topic = t) }, label = { Text(t) }) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { pickDue = true }) { Text("期限：" + (p.dueAt?.let { Format.date(it) } ?: "無")) }
                    if (p.dueAt != null) TextButton(onClick = { p = p.copy(dueAt = null) }) { Text("清除") }
                }
                if (existing != null) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ProjectStatus.entries.forEach { s -> FilterChip(selected = p.status == s, onClick = { p = p.copy(status = s) }, label = { Text(s.label) }) }
                }
                if (onDelete != null) TextButton(onClick = onDelete) { Text("刪除專案（待辦保留）", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(enabled = p.name.isNotBlank(), onClick = { onSave(p.copy(name = p.name.trim())) }) { Text("儲存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
    if (pickDue) DateTimePickerDialog(p.dueAt, { pickDue = false }) { p = p.copy(dueAt = it); pickDue = false }
}

/** 專案看板：長按卡片拖到別欄改狀態，點卡片編輯。 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ProjectBoardScreen(
    project: Project,
    tasks: List<Task>,
    lookup: Lookup,
    actions: TaskActions,
    onBack: () -> Unit,
    onSetStatus: (Task, TaskStatus) -> Unit,
    onQuickAdd: (String, SourceKind, TaskStatus) -> Unit,
    onSaveProject: (Project) -> Unit,
    onDeleteProject: (Project) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    val mine = tasks.filter { it.projectId == project.id && it.status != TaskStatus.CANCELLED }
    val progress = progressOf(project, tasks)

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
            Column(Modifier.weight(1f)) {
                Text(project.name, style = MaterialTheme.typography.titleLarge)
                Text(
                    "${project.topic} · ${progress.done}/${progress.total} 完成" + (project.dueAt?.let { " · 期限 ${Format.date(it)}" } ?: ""),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary,
                )
            }
            IconButton(onClick = { editing = true }) { Icon(Icons.Filled.Edit, contentDescription = "編輯專案") }
        }
        QuickInputBar(
            onSubmit = { text, kind -> onQuickAdd(text, kind, TaskStatus.TODO) },
            placeholder = "新增到「${project.name}」",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
        Row(
            Modifier.fillMaxSize().horizontalScroll(rememberScrollState()).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TaskStatus.board.forEach { status ->
                val column = mine.filter { it.status == status }
                    .sortedWith(compareBy({ it.dueAt ?: Instant.MAX }, { it.createdAt ?: Instant.MAX }))
                BoardColumn(status, column, lookup, actions, onAdd = { onQuickAdd(it, SourceKind.MANUAL, status) }, onDropTask = { id ->
                    mine.firstOrNull { it.id == id }?.let { if (it.status != status) onSetStatus(it, status) }
                })
            }
        }
    }
    if (editing) ProjectDialog(
        project,
        onDismiss = { editing = false },
        onSave = { onSaveProject(it); editing = false },
        onDelete = { editing = false; onDeleteProject(project); onBack() },
    )
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun BoardColumn(
    status: TaskStatus,
    tasks: List<Task>,
    lookup: Lookup,
    actions: TaskActions,
    onAdd: (String) -> Unit,
    onDropTask: (String) -> Unit,
) {
    var hovering by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    val target = remember(onDropTask) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                hovering = false
                val id = event.toAndroidDragEvent().clipData?.getItemAt(0)?.text?.toString() ?: return false
                onDropTask(id)
                return true
            }
            override fun onEntered(event: DragAndDropEvent) { hovering = true }
            override fun onExited(event: DragAndDropEvent) { hovering = false }
            override fun onEnded(event: DragAndDropEvent) { hovering = false }
        }
    }
    Column(
        Modifier.width(240.dp).fillMaxHeight()
            .background(
                if (hovering) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                RoundedCornerShape(12.dp),
            )
            .dragAndDropTarget(
                shouldStartDragAndDrop = { e -> e.mimeTypes().contains(android.content.ClipDescription.MIMETYPE_TEXT_PLAIN) },
                target = target,
            )
            .padding(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${status.label}  ${tasks.size}", style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f).padding(4.dp))
            IconButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, contentDescription = "新增卡片到${status.label}") }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(tasks, key = { it.id }) { task ->
                Card(
                    Modifier.fillMaxWidth().dragAndDropSource {
                        detectTapGestures(
                            onTap = { actions.open(task) },
                            onLongPress = {
                                startTransfer(DragAndDropTransferData(ClipData.newPlainText("task", task.id)))
                            },
                        )
                    },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Text(task.title, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        val meta = buildList {
                            (task.dueAt ?: task.remindAt)?.let { add(Format.dateTime(it)) }
                            if (status == TaskStatus.WAITING) task.waitingFor?.let { add("等：$it") }
                            val subs = lookup.tasks.values.filter { it.parentId == task.id }
                            if (subs.isNotEmpty()) add("子任務 ${subs.count { it.status == TaskStatus.DONE }}/${subs.size}")
                        }
                        if (meta.isNotEmpty()) Text(meta.joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary)
                        TaskChips(task, lookup, actions, showProject = false)
                    }
                }
            }
        }
    }
    if (adding) AddCardDialog(status.label, onDismiss = { adding = false }, onAdd = { onAdd(it); adding = false })
}

@Composable
private fun AddCardDialog(columnLabel: String, onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新增到「$columnLabel」") },
        text = {
            OutlinedTextField(text, { text = it }, placeholder = { Text("例如「週五前交報價單」") }, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onAdd(text.trim()) }) { Text("新增") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
