package com.shishonomisaki.lifemanager.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.shishonomisaki.lifemanager.core.model.AutoField
import com.shishonomisaki.lifemanager.core.model.Priority
import com.shishonomisaki.lifemanager.core.model.Project
import com.shishonomisaki.lifemanager.core.model.ProjectStatus
import com.shishonomisaki.lifemanager.core.model.Task
import com.shishonomisaki.lifemanager.core.model.TaskStatus
import com.shishonomisaki.lifemanager.core.parse.Recurrence
import com.shishonomisaki.lifemanager.ui.Format
import com.shishonomisaki.lifemanager.ui.projects.ProjectDialog

/** 編輯一筆待辦：所有欄位、子任務、刪除。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TaskEditorSheet(
    original: Task,
    lookup: Lookup,
    actions: TaskActions,
    onSave: (Task) -> Unit,
    onDelete: (Task) -> Unit,
    onAddSubtask: (String) -> Unit,
    onCreateTag: (String) -> String,
    onCreateProject: (Project) -> String,
    onDismiss: () -> Unit,
) {
    var t by remember(original.id) { mutableStateOf(original) }
    var pickDue by remember { mutableStateOf(false) }
    var pickRemind by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var newTag by remember { mutableStateOf("") }
    var creatingProject by remember { mutableStateOf(false) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun dashed(field: String, changed: Boolean) = field in original.autoTagged && !changed

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        // 儲存、刪除固定在最上面，不用捲到底
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = { confirmDelete = true }) { Text("刪除", color = MaterialTheme.colorScheme.error) }
            Button(onClick = { onSave(t.copy(title = t.title.trim().ifEmpty { original.title })); onDismiss() }) { Text("儲存") }
        }
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 分成幾個可摺疊區塊，常用的放上面；AI 沒把握時分類區預設打開
            val subtasks = lookup.tasks.values.filter { it.parentId == original.id }.sortedBy { it.createdAt }
            var open by remember(original.id) {
                mutableStateOf(buildSet {
                    add("basic"); add("time")
                    if (original.needsReview || original.autoTagged.isNotEmpty()) add("class")
                    if (subtasks.isNotEmpty()) add("subs")
                })
            }
            fun toggle(k: String) { open = if (k in open) open - k else open + k }

            EditorSection("基本", Icons.Filled.EditNote, "basic" in open, { toggle("basic") }, summary = t.status.label) {
                OutlinedTextField(t.title, { t = t.copy(title = it) }, Modifier.fillMaxWidth(), label = { Text("待辦") })
                OutlinedTextField(t.notes.orEmpty(), { t = t.copy(notes = it.ifEmpty { null }) }, Modifier.fillMaxWidth(),
                    label = { Text("補充說明") }, minLines = 2)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TaskStatus.entries.forEach { s ->
                        FilterChip(selected = t.status == s, onClick = { t = t.copy(status = s) }, label = { Text(s.label) })
                    }
                }
                if (t.status == TaskStatus.WAITING) {
                    OutlinedTextField(t.waitingFor.orEmpty(), { t = t.copy(waitingFor = it.ifEmpty { null }) }, Modifier.fillMaxWidth(),
                        label = { Text("在等誰／等什麼") })
                }
            }

            EditorSection("時間", Icons.Filled.Schedule, "time" in open, { toggle("time") },
                summary = t.dueAt?.let { Format.dateTime(it) } ?: "未排時間") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickDue = true }) { Text("期限：" + (t.dueAt?.let { Format.dateTime(it) } ?: "無")) }
                    if (t.dueAt != null) TextButton(onClick = { t = t.copy(dueAt = null) }) { Text("清除") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickRemind = true }) { Text("提醒：" + (t.remindAt?.let { Format.dateTime(it) } ?: "無")) }
                    if (t.remindAt != null) TextButton(onClick = { t = t.copy(remindAt = null) }) { Text("清除") }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val presets = Recurrence.PRESETS.toMutableList()
                    if (t.recurrence != null && presets.none { it.first == t.recurrence }) presets += t.recurrence to Recurrence.label(t.recurrence)
                    presets.forEach { (rule, label) ->
                        FilterChip(selected = t.recurrence == rule, onClick = { t = t.copy(recurrence = rule) }, label = { Text(label) })
                    }
                }
            }

            val projectName = t.projectId?.let { lookup.projects[it]?.name }
            EditorSection("分類", Icons.Filled.Category, "class" in open, { toggle("class") },
                summary = listOfNotNull(t.topic, projectName).joinToString(" · ")) {
                Label("主題" + if (dashed(AutoField.TOPIC, t.topic != original.topic)) "（自動）" else "")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    lookup.topics.forEach { topic ->
                        FilterChip(selected = t.topic == topic, onClick = { t = t.copy(topic = topic) }, label = { Text(topic) })
                    }
                }

                Label("專案" + if (dashed(AutoField.PROJECT, t.projectId != original.projectId)) "（自動）" else "")
                var projectMenu by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { projectMenu = true }) { Text(projectName ?: "無專案") }
                    DropdownMenu(projectMenu, { projectMenu = false }) {
                        // 第 13 項：不用先跑去專案頁，直接在這裡開新專案
                        DropdownMenuItem(text = { Text("＋ 新增專案…", color = MaterialTheme.colorScheme.primary) },
                            onClick = { projectMenu = false; creatingProject = true })
                        DropdownMenuItem(text = { Text("無專案") }, onClick = { projectMenu = false; t = t.copy(projectId = null) })
                        lookup.projects.values
                            .filter { it.status == ProjectStatus.ACTIVE || it.id == t.projectId }
                            .sortedBy { it.name }
                            .forEach { p ->
                                DropdownMenuItem(text = { Text("${p.name}（${p.topic}）") }, onClick = {
                                    projectMenu = false
                                    t = t.copy(projectId = p.id, topic = p.topic)
                                })
                            }
                    }
                }

                Label("標籤" + if (dashed(AutoField.TAGS, t.tags != original.tags)) "（自動）" else "")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    lookup.tags.values.sortedByDescending { it.useCount }.forEach { tag ->
                        val on = tag.id in t.tags
                        FilterChip(selected = on, onClick = {
                            t = t.copy(tags = if (on) t.tags - tag.id else t.tags + tag.id)
                        }, label = { Text("#${tag.name}") })
                    }
                }
                OutlinedTextField(
                    newTag, { newTag = it }, Modifier.fillMaxWidth(),
                    label = { Text("新增標籤") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (newTag.isNotBlank()) {
                            val id = onCreateTag(newTag.trim().removePrefix("#"))
                            t = t.copy(tags = (t.tags + id).distinct())
                            newTag = ""
                        }
                    }),
                )

                Label("優先度")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (0..2).forEach { p ->
                        FilterChip(selected = t.priority == p, onClick = { t = t.copy(priority = p) }, label = { Text(Priority.label(p)) })
                    }
                }
            }

            if (original.parentId == null && original.id.isNotEmpty()) {
                EditorSection("子任務", Icons.Filled.Checklist, "subs" in open, { toggle("subs") },
                    summary = if (subtasks.isEmpty()) "無" else "${subtasks.count { it.status == TaskStatus.DONE }}/${subtasks.size}") {
                    subtasks.forEach { sub -> TaskRow(sub, lookup, actions, showChips = false) }
                    QuickInputBar(onSubmit = { text, _ -> onAddSubtask(text) }, placeholder = "新增子任務")
                }
            }

            original.source.let { src ->
                Text(
                    "來源：${src.kind.label}" + (src.confidence?.let { " · AI 信心 ${Format.percent(it)}" } ?: ""),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }

    if (pickDue) DateTimePickerDialog(t.dueAt, { pickDue = false }) {
        // 提醒原本跟期限一樣（或沒設）的話一起改
        t = t.copy(dueAt = it, remindAt = if (t.remindAt == null || t.remindAt == t.dueAt) it else t.remindAt)
        pickDue = false
    }
    if (pickRemind) DateTimePickerDialog(t.remindAt ?: t.dueAt, { pickRemind = false }) { t = t.copy(remindAt = it); pickRemind = false }
    if (creatingProject) ProjectDialog(
        existing = null,
        initialTopic = t.topic,
        onDismiss = { creatingProject = false },
        onSave = { p ->
            val id = onCreateProject(p)
            t = t.copy(projectId = id, topic = p.topic)
            creatingProject = false
        },
    )
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("刪除「${original.title}」？") },
        text = { Text("子任務也會一起刪除。") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete(original); onDismiss() }) { Text("刪除") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
    )
}

/** 編輯畫面的一個可摺疊區塊；收起時在標題旁顯示目前的值。 */
@Composable
private fun EditorSection(
    title: String,
    icon: ImageVector,
    expanded: Boolean,
    onToggle: () -> Unit,
    summary: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 10.dp))
                if (!expanded && !summary.isNullOrBlank()) {
                    Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp).weight(1f))
                } else Spacer(Modifier.weight(1f))
                Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = if (expanded) "收起" else "展開",
                    tint = MaterialTheme.colorScheme.outline)
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}
