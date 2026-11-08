package com.shishonomisaki.lifemanager.ui.all

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shishonomisaki.lifemanager.core.model.Task
import com.shishonomisaki.lifemanager.core.model.TaskStatus
import com.shishonomisaki.lifemanager.ui.components.EmptyState
import com.shishonomisaki.lifemanager.ui.components.Lookup
import com.shishonomisaki.lifemanager.ui.components.SectionHeader
import com.shishonomisaki.lifemanager.ui.components.SwipeTaskRow
import com.shishonomisaki.lifemanager.ui.components.TaskActions
import com.shishonomisaki.lifemanager.ui.components.TopicFilterRow
import java.time.Instant

/** 全部：依主題、專案、標籤篩選和搜尋；「待確認」置頂。 */
@Composable
fun AllScreen(
    tasks: List<Task>,
    lookup: Lookup,
    actions: TaskActions,
    topic: String? = null,
    onTopic: (String?) -> Unit = {},
    onOpenReview: () -> Unit = {},
) {
    var query by rememberSaveable { mutableStateOf("") }
    var projectId by rememberSaveable { mutableStateOf<String?>(null) }
    var tagId by rememberSaveable { mutableStateOf<String?>(null) }
    var showDone by rememberSaveable { mutableStateOf(false) }

    val q = query.trim()
    val fTopic = topic
    val fProject = projectId
    val fTag = tagId
    val filtered = tasks.filter { t ->
        (showDone || t.status.isOpen) &&
            (fTopic == null || t.topic == fTopic) &&
            (fProject == null || t.projectId == fProject) &&
            (fTag == null || fTag in t.tags) &&
            (q.isEmpty() || t.title.contains(q, ignoreCase = true) || t.notes?.contains(q, ignoreCase = true) == true)
    }
    val review = filtered.filter { it.needsReview && it.status.isOpen }
    val rest = (filtered - review.toSet()).sortedWith(
        compareBy<Task>({ !it.status.isOpen }, { it.dueAt ?: it.remindAt ?: Instant.MAX }, { -(it.priority) }, { it.createdAt ?: Instant.MAX }),
    )

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            query, { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            placeholder = { Text("搜尋") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            singleLine = true,
            shape = MaterialTheme.shapes.extraLarge,
        )
        TopicFilterRow(lookup.topics, topic, onTopic) {
            Picker(
                label = projectId?.let { lookup.projects[it]?.name } ?: "專案",
                selected = projectId != null,
                options = listOf<Pair<String?, String>>(null to "全部專案") + lookup.projects.values.sortedBy { it.name }.map { it.id to it.name },
                onPick = { projectId = it },
            )
            Picker(
                label = tagId?.let { lookup.tags[it]?.name?.let { n -> "#$n" } } ?: "標籤",
                selected = tagId != null,
                options = listOf<Pair<String?, String>>(null to "全部標籤") + lookup.tags.values.sortedByDescending { it.useCount }.map { it.id to "#${it.name}" },
                onPick = { tagId = it },
            )
            FilterChip(selected = showDone, onClick = { showDone = !showDone }, label = { Text("含完成") })
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (review.isNotEmpty()) {
                item(key = "h-review") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { SectionHeader("待確認（AI 沒把握）", review.size, Icons.Filled.FactCheck, MaterialTheme.colorScheme.tertiary) }
                        TextButton(onClick = onOpenReview, modifier = Modifier.padding(end = 8.dp, top = 8.dp)) { Text("一張一張看") }
                    }
                }
                items(review, key = { "r" + it.id }) { SwipeTaskRow(it, lookup, actions, Modifier.animateItem()) }
            }
            item(key = "h-all") { SectionHeader("全部", rest.size, Icons.AutoMirrored.Filled.List) }
            if (rest.isEmpty()) item(key = "empty") {
                EmptyState(Icons.Filled.Inbox, if (q.isNotEmpty() || fTopic != null || fProject != null || fTag != null) "沒有符合的待辦" else "還沒有待辦",
                    "可以在今天頁打字、按麥克風，或從 LINE、Telegram 分享進來")
            }
            items(rest, key = { it.id }) { SwipeTaskRow(it, lookup, actions, Modifier.animateItem()) }
        }
    }
}

@Composable
private fun Picker(label: String, selected: Boolean, options: List<Pair<String?, String>>, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(selected = selected, onClick = { open = true }, label = { Text(label) })
        DropdownMenu(open, { open = false }) {
            options.forEach { (id, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { open = false; onPick(id) }) }
        }
    }
}
