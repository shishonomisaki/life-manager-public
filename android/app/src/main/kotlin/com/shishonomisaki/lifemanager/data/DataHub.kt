package com.shishonomisaki.lifemanager.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.shishonomisaki.lifemanager.core.model.Project
import com.shishonomisaki.lifemanager.core.model.Tag
import com.shishonomisaki.lifemanager.core.model.Task
import com.shishonomisaki.lifemanager.reminder.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 整個 APP 共用的即時資料：登入狀態、待辦、專案、標籤。待辦一變就重排本機提醒。 */
@OptIn(ExperimentalCoroutinesApi::class)
class DataHub(
    auth: FirebaseAuth,
    private val repo: Repository,
    private val scheduler: ReminderScheduler,
    private val scope: CoroutineScope,
) {
    val user: StateFlow<FirebaseUser?> = callbackFlow {
        val l = FirebaseAuth.AuthStateListener { trySend(it.currentUser) }
        auth.addAuthStateListener(l)
        awaitClose { auth.removeAuthStateListener(l) }
    }.stateIn(scope, SharingStarted.Eagerly, auth.currentUser)

    private fun <T> whenSignedIn(source: () -> Flow<List<T>>): StateFlow<List<T>?> =
        user.flatMapLatest<FirebaseUser?, List<T>?> { if (it == null) flowOf(null) else source() }
            .stateIn(scope, SharingStarted.Eagerly, null)

    /** null 表示還在載入。 */
    val tasks: StateFlow<List<Task>?> = whenSignedIn { repo.tasksFlow() }
    val projects: StateFlow<List<Project>?> = whenSignedIn { repo.projectsFlow() }
    val tags: StateFlow<List<Tag>?> = whenSignedIn { repo.tagsFlow() }

    init {
        user.onEach { if (it != null) scope.launch { repo.ensureUserSetup() } else scheduler.cancelAll() }.launchIn(scope)
        tasks.onEach { list -> if (list != null) scheduler.sync(list) }.launchIn(scope)
    }
}
