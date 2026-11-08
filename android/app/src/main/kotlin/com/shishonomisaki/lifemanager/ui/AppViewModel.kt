package com.shishonomisaki.lifemanager.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.shishonomisaki.lifemanager.LifeApp
import com.shishonomisaki.lifemanager.core.model.AutoField
import com.shishonomisaki.lifemanager.core.model.Defaults
import com.shishonomisaki.lifemanager.core.model.Project
import com.shishonomisaki.lifemanager.core.model.SourceKind
import com.shishonomisaki.lifemanager.core.model.Tag
import com.shishonomisaki.lifemanager.core.model.Task
import com.shishonomisaki.lifemanager.core.model.TaskSource
import com.shishonomisaki.lifemanager.core.model.TaskStatus
import com.shishonomisaki.lifemanager.core.model.Priority
import com.shishonomisaki.lifemanager.core.parse.ParseContext
import com.shishonomisaki.lifemanager.core.parse.Recurrence
import com.shishonomisaki.lifemanager.core.preview.InputPreview
import com.shishonomisaki.lifemanager.core.preview.PreviewField
import com.shishonomisaki.lifemanager.core.preview.without
import com.shishonomisaki.lifemanager.ui.components.PreviewChip
import com.shishonomisaki.lifemanager.core.parse.TimeParser
import com.shishonomisaki.lifemanager.data.SettingsStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZonedDateTime

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as LifeApp
    private val repo = app.repository
    private val hub = app.hub

    val user = hub.user
    val tasks: StateFlow<List<Task>?> = hub.tasks
    val projects: StateFlow<List<Project>?> = hub.projects
    val tags: StateFlow<List<Tag>?> = hub.tags
    val settings: StateFlow<SettingsStore.Settings> = app.settings.state

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** 給畫面下方 Snackbar 的訊息。 */
    val messages: SharedFlow<String> = _messages

    private fun say(msg: String) { _messages.tryEmit(msg) }

    private fun parseContext() = ParseContext(
        now = ZonedDateTime.now(Defaults.ZONE),
        projects = projects.value.orEmpty().filter { it.status.value == "active" },
        tags = tags.value ?: Defaults.TAGS,
    )

    /**
     * 快速輸入：先抓時間、用規則版選好標籤立刻存，接著背景交給 Gemini 補強（若有設定金鑰）。
     */
    fun quickAdd(
        text: String,
        kind: SourceKind = SourceKind.MANUAL,
        projectId: String? = null,
        parentId: String? = null,
        status: TaskStatus = TaskStatus.TODO,
        skip: Set<PreviewField> = emptySet(),
    ) {
        val line = text.trim()
        if (line.isEmpty()) return
        // 用 APP 層級的 scope：快速新增視窗送出後馬上關掉，不能跟著被取消
        app.appScope.launch {
            val ctx = parseContext()
            // 使用者在預覽點掉時間：整句原文當標題，不排時間
            val t = if (PreviewField.TIME in skip) TimeParser.Result(line, null, false, null) else TimeParser.parse(line, ctx.now)
            val at = t.at?.toInstant()
            val parent = parentId?.let { id -> tasks.value?.firstOrNull { it.id == id } }
            val project = (projectId ?: parent?.projectId)?.let { id -> projects.value?.firstOrNull { it.id == id } }
            var task = Task(
                id = repo.newTaskId(),
                title = t.title,
                topic = project?.topic ?: parent?.topic ?: Defaults.DEFAULT_TOPIC,
                projectId = project?.id,
                parentId = parentId,
                status = status,
                dueAt = at,
                remindAt = at,
                recurrence = t.recurrence,
                source = TaskSource(kind),
            )
            val classifier = app.classifier
            val rules = classifier.rules.classify(task.title, null, ctx).without(skip)
            task = classifier.apply(task, rules, firstTime = project == null && parent == null)
            repo.createTask(task)
            // 點掉分類就是看過了，不再讓 AI 把它加回來
            val reviewed = skip.any { it != PreviewField.TIME }
            if (!reviewed && classifier.canUseAi(task.topic)) classifier.enqueueAi(task.id)
            say(buildString {
                append("已新增「${task.title}」")
                at?.let { append("，${Format.dateTime(it)}") }
            })
        }
    }

    /** 輸入框下方的預覽膠囊，跟 [quickAdd] 用同一套規則。 */
    suspend fun previewChips(text: String, skip: Set<PreviewField>): List<PreviewChip> {
        val p = InputPreview.of(text, parseContext(), skip)
        val c = p.classification
        return buildList {
            p.time?.let { t ->
                val label = buildString {
                    t.at?.let { append(Format.dateTime(it.toInstant())) }
                    t.recurrence?.let { if (isNotEmpty()) append(" · "); append("↻ ").append(Recurrence.label(it)) }
                }
                add(PreviewChip(PreviewField.TIME, "🕑 $label"))
            }
            c.topic?.let { add(PreviewChip(PreviewField.TOPIC, it, topic = it)) }
            c.projectId?.let { id -> projects.value?.firstOrNull { it.id == id } }?.let { add(PreviewChip(PreviewField.PROJECT, "▣ ${it.name}")) }
            if (c.tagIds.isNotEmpty()) {
                val all = (tags.value ?: Defaults.TAGS).associateBy { it.id }
                add(PreviewChip(PreviewField.TAGS, c.tagIds.mapNotNull { all[it]?.name }.joinToString(" ") { "#$it" }))
            }
            c.priority?.takeIf { it > 0 }?.let { add(PreviewChip(PreviewField.PRIORITY, Priority.label(it))) }
        }
    }

    /** 編輯畫面儲存。被使用者改過的欄位從 autoTagged 移除（虛線變實線）。 */
    fun save(edited: Task, original: Task) {
        val auto = edited.autoTagged.toMutableSet()
        if (edited.topic != original.topic) auto -= AutoField.TOPIC
        if (edited.projectId != original.projectId) auto -= AutoField.PROJECT
        if (edited.tags != original.tags) auto -= AutoField.TAGS
        if (edited.priority != original.priority) auto -= AutoField.PRIORITY
        var t = edited.copy(autoTagged = auto)
        // 人看過、改過就不再是「待確認」
        if (t.needsReview) t = t.copy(source = t.source.copy(confidence = 1.0))
        if (t.projectId != null) t = t.copy(suggestedProject = null)
        val latest = tasks.value?.firstOrNull { it.id == original.id }
        if (latest != null && latest.version != original.version) say("這筆已被其他裝置更新，已用你的修改覆蓋")
        val wasDone = original.status == TaskStatus.DONE
        when {
            t.status == TaskStatus.DONE && !wasDone -> {
                repo.saveTask(t.copy(status = original.status))
                repo.complete(t)
            }
            t.status != TaskStatus.DONE && wasDone -> repo.saveTask(t.copy(completedAt = null))
            else -> repo.saveTask(t)
        }
        (t.tags - original.tags.toSet()).forEach { repo.bumpTag(it) }
    }

    /** 標籤膠囊上直接改（不經過編輯畫面）。 */
    // 從膠囊選了值（就算跟原本一樣）也算使用者確認過，虛線變實線
    fun setTopic(task: Task, topic: String) = save(task.copy(topic = topic, autoTagged = task.autoTagged - AutoField.TOPIC), task)
    fun setPriority(task: Task, p: Int) = save(task.copy(priority = p, autoTagged = task.autoTagged - AutoField.PRIORITY), task)
    /** 待確認一張一張看：整筆確認，自動選的分類全部算數。 */
    fun confirmReview(task: Task) = save(task.copy(autoTagged = emptySet()), task)
    fun confirmAuto(task: Task, field: String) = save(task.copy(autoTagged = task.autoTagged - field), task)
    fun removeTag(task: Task, tagId: String) = save(task.copy(tags = task.tags - tagId), task)
    fun setProject(task: Task, projectId: String?) = save(task.copy(projectId = projectId), task)

    fun complete(task: Task) {
        repo.complete(task)
        say("完成「${task.title}」" + if (task.recurrence != null) "，已排下一次" else "")
    }

    fun reopen(task: Task) = repo.setStatus(task, TaskStatus.TODO)

    fun setStatus(task: Task, status: TaskStatus) {
        if (status == task.status) return
        repo.setStatus(task, status)
    }

    /** 改時間：提醒和期限一起移。 */
    fun reschedule(task: Task, at: Instant) {
        repo.saveTask(task.copy(dueAt = at, remindAt = at))
        say("「${task.title}」改到 ${Format.dateTime(at)}")
    }

    fun delete(task: Task) {
        repo.deleteTask(task, tasks.value.orEmpty())
        say("已刪除「${task.title}」")
    }

    /** 多選後整批刪除。 */
    fun deleteMany(ids: Set<String>) {
        val all = tasks.value.orEmpty()
        val chosen = all.filter { it.id in ids }
        chosen.forEach { repo.deleteTask(it, all) }
        say("已刪除 ${chosen.size} 項")
    }

    /** 多選後整批完成（已完成的跳過）。 */
    fun completeMany(ids: Set<String>) {
        val chosen = tasks.value.orEmpty().filter { it.id in ids && it.status != TaskStatus.DONE }
        chosen.forEach { repo.complete(it) }
        say("完成 ${chosen.size} 項")
    }

    fun acceptSuggestedProject(task: Task) {
        val name = task.suggestedProject ?: return
        val id = repo.createProject(Project(name = name, topic = task.topic))
        repo.saveTask(task.copy(projectId = id, suggestedProject = null, autoTagged = task.autoTagged - AutoField.PROJECT))
        say("已建立專案「$name」")
    }

    fun dismissSuggestedProject(task: Task) = repo.updateTaskFields(task.id, mapOf("suggestedProject" to null))

    fun createProject(project: Project): String = repo.createProject(project)
    fun saveProject(project: Project) = repo.saveProject(project)
    fun deleteProject(project: Project) {
        repo.deleteProject(project, tasks.value.orEmpty())
        say("已刪除專案「${project.name}」，待辦保留")
    }

    fun createTag(name: String): String {
        val palette = listOf("#1E88E5", "#E53935", "#FB8C00", "#00897B", "#8E24AA", "#43A047", "#6D4C41", "#3949AB", "#D81B60")
        val existing = tags.value.orEmpty().firstOrNull { it.name == name.trim() }
        return existing?.id ?: repo.createTag(name.trim(), palette[(tags.value?.size ?: 0) % palette.size])
    }

    fun deleteTag(tag: Tag) = repo.deleteTag(tag.id)

    fun updateSettings(block: (SettingsStore.Settings) -> SettingsStore.Settings) = app.settings.update(block)

    fun signOut() = repo.signOut()

    fun message(msg: String) = say(msg)
}
