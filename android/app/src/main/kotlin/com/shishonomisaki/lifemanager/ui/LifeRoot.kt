package com.shishonomisaki.lifemanager.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.filled.ViewKanban
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.shishonomisaki.lifemanager.core.model.Defaults
import com.shishonomisaki.lifemanager.core.model.Task
import com.shishonomisaki.lifemanager.ui.all.AllScreen
import com.shishonomisaki.lifemanager.ui.components.LocalSelection
import com.shishonomisaki.lifemanager.ui.components.Lookup
import com.shishonomisaki.lifemanager.ui.components.QuickPreview
import com.shishonomisaki.lifemanager.ui.components.RescheduleDialog
import com.shishonomisaki.lifemanager.ui.components.Selection
import com.shishonomisaki.lifemanager.ui.components.TaskActions
import com.shishonomisaki.lifemanager.ui.components.TaskEditorSheet
import com.shishonomisaki.lifemanager.ui.projects.ProjectBoardScreen
import com.shishonomisaki.lifemanager.ui.projects.ProjectsScreen
import com.shishonomisaki.lifemanager.ui.review.ReviewScreen
import com.shishonomisaki.lifemanager.ui.settings.OnboardingScreen
import com.shishonomisaki.lifemanager.ui.settings.SettingsScreen
import com.shishonomisaki.lifemanager.ui.stats.StatsScreen
import com.shishonomisaki.lifemanager.ui.today.TodayScreen

private enum class Tab(val label: String, val icon: ImageVector) {
    TODAY("今天", Icons.Filled.Today),
    PROJECTS("專案", Icons.Filled.ViewKanban),
    ALL("全部", Icons.AutoMirrored.Filled.List),
    STATS("統計", Icons.Filled.BarChart),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LifeRoot(vm: AppViewModel, openTaskId: String?, onOpenTaskHandled: () -> Unit) {
    val user by vm.user.collectAsState()
    val settings by vm.settings.collectAsState()

    if (user == null) { SignInScreen(); return }
    if (!settings.onboardingDone) {
        OnboardingScreen(onDone = { vm.updateSettings { it.copy(onboardingDone = true) } })
        return
    }

    val tasksOrNull by vm.tasks.collectAsState()
    val projects by vm.projects.collectAsState()
    val tags by vm.tags.collectAsState()
    val tasks = tasksOrNull.orEmpty()
    val lookup = remember(tasks, projects, tags) {
        Lookup(
            tasks = tasks.associateBy { it.id },
            projects = projects.orEmpty().associateBy { it.id },
            tags = (tags ?: Defaults.TAGS).associateBy { it.id },
            topics = Defaults.TOPICS,
        )
    }

    var tab by rememberSaveable { mutableStateOf(Tab.TODAY) }
    var boardProjectId by rememberSaveable { mutableStateOf<String?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var rescheduling by remember { mutableStateOf<Task?>(null) }
    var showReview by rememberSaveable { mutableStateOf(false) }
    val selection = remember { Selection() }
    var confirmBulkDelete by remember { mutableStateOf(false) }
    // 今天頁和全部頁共用的主題篩選
    var topicFilter by rememberSaveable { mutableStateOf<String?>(null) }
    val reviewCount = tasks.count { it.needsReview && it.status.isOpen }
    val quickPreview = remember(vm) {
        QuickPreview(
            chips = { text, skip -> vm.previewChips(text, skip) },
            submit = { text, kind, skip -> vm.quickAdd(text, kind, skip = skip) },
        )
    }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(openTaskId) { if (openTaskId != null) { editingId = openTaskId; onOpenTaskHandled() } }

    val actions = remember(vm) {
        TaskActions(
            open = { editingId = it.id },
            complete = vm::complete,
            reopen = vm::reopen,
            reschedule = { rescheduling = it },
            setTopic = vm::setTopic,
            setPriority = vm::setPriority,
            setProject = vm::setProject,
            removeTag = vm::removeTag,
            acceptSuggestion = vm::acceptSuggestedProject,
            dismissSuggestion = vm::dismissSuggestedProject,
            confirmAuto = vm::confirmAuto,
        )
    }

    BackHandler(enabled = showSettings || showReview || boardProjectId != null || selection.active) {
        when {
            selection.active -> selection.clear()
            showSettings -> showSettings = false
            showReview -> showReview = false
            else -> boardProjectId = null
        }
    }

    if (showSettings) {
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { pad ->
            Box(Modifier.padding(pad)) {
                SettingsScreen(
                    email = user?.email,
                    settings = settings,
                    onUpdate = { vm.updateSettings(it); vm.message("已儲存") },
                    onSignOut = { showSettings = false; vm.signOut() },
                    onBack = { showSettings = false },
                )
            }
        }
        return
    }

    Scaffold(
        topBar = {
            if (selection.active) TopAppBar(
                title = { Text("已選 ${selection.ids.size} 項") },
                navigationIcon = { IconButton(onClick = { selection.clear() }) { Icon(Icons.Filled.Close, contentDescription = "取消多選") } },
                actions = {
                    IconButton(onClick = { vm.completeMany(selection.ids); selection.clear() }) {
                        Icon(Icons.Filled.DoneAll, contentDescription = "全部完成")
                    }
                    IconButton(onClick = { confirmBulkDelete = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "刪除", tint = MaterialTheme.colorScheme.error)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) else if (boardProjectId == null && !showReview) TopAppBar(
                title = { Text(tab.label, style = MaterialTheme.typography.titleLarge) },
                actions = {
                    if (reviewCount > 0) IconButton(onClick = { showReview = true }) {
                        BadgedBox(badge = { Badge { Text("$reviewCount") } }) {
                            Icon(Icons.Filled.FactCheck, contentDescription = "待確認 $reviewCount 項")
                        }
                    }
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Filled.Settings, contentDescription = "設定") }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t; boardProjectId = null; showReview = false; selection.clear() },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            if (tasksOrNull == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
                return@Box
            }
            val board = boardProjectId?.let { lookup.projects[it] }
            CompositionLocalProvider(LocalSelection provides selection.takeIf { !showReview && board == null }) {
                when {
                    showReview -> ReviewScreen(
                        tasks = tasks,
                        lookup = lookup,
                        actions = actions,
                        onConfirm = vm::confirmReview,
                        onBack = { showReview = false },
                    )
                    tab == Tab.PROJECTS && board != null -> ProjectBoardScreen(
                        project = board,
                        tasks = tasks,
                        lookup = lookup,
                        actions = actions,
                        onBack = { boardProjectId = null },
                        onSetStatus = vm::setStatus,
                        onQuickAdd = { text, kind, status -> vm.quickAdd(text, kind, projectId = board.id, status = status) },
                        onSaveProject = vm::saveProject,
                        onDeleteProject = vm::deleteProject,
                    )
                    tab == Tab.TODAY -> TodayScreen(
                        tasks, lookup, actions,
                        onQuickAdd = { text, kind -> vm.quickAdd(text, kind) },
                        preview = quickPreview,
                        topicFilter = topicFilter,
                        onTopicFilter = { topicFilter = it },
                        onOpenReview = { showReview = true },
                    )
                    tab == Tab.PROJECTS -> ProjectsScreen(
                        projects = projects.orEmpty(),
                        tasks = tasks,
                        onOpen = { boardProjectId = it.id },
                        onCreate = { boardProjectId = vm.createProject(it) },
                    )
                    tab == Tab.ALL -> AllScreen(
                        tasks, lookup, actions,
                        topic = topicFilter,
                        onTopic = { topicFilter = it },
                        onOpenReview = { showReview = true },
                    )
                    else -> StatsScreen(tasks, lookup, actions)
                }
            }
        }
    }

    editingId?.let { id -> lookup.tasks[id] }?.let { task ->
        TaskEditorSheet(
            original = task,
            lookup = lookup,
            actions = actions,
            onSave = { vm.save(it, task) },
            onDelete = vm::delete,
            onAddSubtask = { text -> vm.quickAdd(text, parentId = task.id) },
            onCreateTag = vm::createTag,
            onCreateProject = vm::createProject,
            onDismiss = { editingId = null },
        )
    }
    if (confirmBulkDelete) AlertDialog(
        onDismissRequest = { confirmBulkDelete = false },
        title = { Text("刪除 ${selection.ids.size} 項？") },
        text = { Text("子任務也會一起刪除，刪了不能復原。") },
        confirmButton = {
            TextButton(onClick = { vm.deleteMany(selection.ids); selection.clear(); confirmBulkDelete = false }) {
                Text("刪除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = { confirmBulkDelete = false }) { Text("取消") } },
    )
    rescheduling?.let { task ->
        RescheduleDialog(task, onDismiss = { rescheduling = null }, onPicked = { vm.reschedule(task, it); rescheduling = null })
    }
}
