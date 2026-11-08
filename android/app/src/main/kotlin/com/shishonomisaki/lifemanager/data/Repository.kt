package com.shishonomisaki.lifemanager.data

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import com.shishonomisaki.lifemanager.core.model.Defaults
import com.shishonomisaki.lifemanager.core.model.Project
import com.shishonomisaki.lifemanager.core.model.Tag
import com.shishonomisaki.lifemanager.core.model.Task
import com.shishonomisaki.lifemanager.core.model.TaskStatus
import com.shishonomisaki.lifemanager.core.parse.Recurrence
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.time.Instant

/**
 * Firestore 存取，全部放在 `users/{uid}/` 底下。
 *
 * 寫入不等伺服器回應（離線時 Firestore 會先存在手機、連線後同步），所以這裡的寫入函式都不是 suspend。
 */
class Repository(private val db: FirebaseFirestore, private val auth: FirebaseAuth) {

    private val uid: String get() = auth.currentUser?.uid ?: error("尚未登入")
    private fun user() = db.collection("users").document(uid)
    private fun tasks(): CollectionReference = user().collection("tasks")
    private fun projects(): CollectionReference = user().collection("projects")
    private fun tags(): CollectionReference = user().collection("tags")

    val isSignedIn: Boolean get() = auth.currentUser != null

    fun tasksFlow(): Flow<List<Task>> = callbackFlow {
        val reg = tasks().addSnapshotListener { snap, e ->
            if (e != null) { Log.w(TAG, "tasks listener", e); return@addSnapshotListener }
            if (snap != null) trySend(snap.documents.map { it.toTask() })
        }
        awaitClose { reg.remove() }
    }

    fun projectsFlow(): Flow<List<Project>> = callbackFlow {
        val reg = projects().addSnapshotListener { snap, e ->
            if (e != null) { Log.w(TAG, "projects listener", e); return@addSnapshotListener }
            if (snap != null) trySend(snap.documents.map { it.toProject() })
        }
        awaitClose { reg.remove() }
    }

    fun tagsFlow(): Flow<List<Tag>> = callbackFlow {
        val reg = tags().addSnapshotListener { snap, e ->
            if (e != null) { Log.w(TAG, "tags listener", e); return@addSnapshotListener }
            if (snap != null) trySend(snap.documents.map { it.toTag() }.sortedByDescending { it.useCount })
        }
        awaitClose { reg.remove() }
    }

    /** 第一次登入時建立使用者設定和預設標籤。 */
    suspend fun ensureUserSetup() {
        val u = auth.currentUser ?: return
        val doc = runCatching { user().get().await() }.getOrNull()
        if (doc == null || !doc.exists()) {
            user().set(
                mapOf(
                    "displayName" to u.displayName,
                    "email" to u.email,
                    "timezone" to Defaults.ZONE.id,
                    "weekStartsOn" to Defaults.WEEK_STARTS_ON,
                    "topics" to Defaults.TOPICS,
                ),
                SetOptions.merge(),
            )
        }
        val existing = runCatching { tags().get().await().documents.map { it.id }.toSet() }.getOrDefault(emptySet())
        if (existing.isEmpty()) {
            Defaults.TAGS.forEach { t ->
                tags().document(t.id).set(mapOf("name" to t.name, "color" to t.color, "useCount" to 0))
            }
        }
    }

    // ---------- 待辦 ----------

    fun newTaskId(): String = tasks().document().id

    fun createTask(task: Task): String {
        val ref = if (task.id.isNotEmpty()) tasks().document(task.id) else tasks().document()
        val data = task.toFields() + mapOf(
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
            "version" to 1L,
        )
        ref.set(data).addOnFailureListener { Log.w(TAG, "createTask", it) }
        task.tags.forEach { bumpTag(it) }
        return ref.id
    }

    /** 覆寫待辦內容並把 version +1。 */
    fun saveTask(task: Task) {
        val data = task.toFields() + mapOf(
            "updatedAt" to FieldValue.serverTimestamp(),
            "version" to FieldValue.increment(1),
        )
        tasks().document(task.id).update(data).addOnFailureListener { Log.w(TAG, "saveTask", it) }
    }

    fun updateTaskFields(id: String, fields: Map<String, Any?>) {
        val data = fields + mapOf(
            "updatedAt" to FieldValue.serverTimestamp(),
            "version" to FieldValue.increment(1),
        )
        tasks().document(id).update(data).addOnFailureListener { Log.w(TAG, "updateTaskFields", it) }
    }

    fun setStatus(task: Task, status: TaskStatus, now: Instant = Instant.now()) {
        if (status == TaskStatus.DONE) return complete(task, now)
        updateTaskFields(task.id, mapOf("status" to status.value, "completedAt" to null))
    }

    /** 完成；重複的待辦會建立下一次。 */
    fun complete(task: Task, now: Instant = Instant.now()) {
        updateTaskFields(task.id, mapOf("status" to TaskStatus.DONE.value, "completedAt" to now.toTimestamp()))
        val rule = task.recurrence ?: return
        val base = (task.dueAt ?: task.remindAt ?: now).atZone(Defaults.ZONE)
        var next = Recurrence.next(rule, base) ?: return
        // 拖了很久才完成的話，跳到現在之後的下一次
        while (next.toInstant().isBefore(now)) next = Recurrence.next(rule, next) ?: return
        val shift = java.time.Duration.between(base, next)
        createTask(
            task.copy(
                id = "",
                status = TaskStatus.TODO,
                completedAt = null,
                dueAt = task.dueAt?.plus(shift),
                remindAt = task.remindAt?.plus(shift),
                version = 0,
            ),
        )
    }

    fun deleteTask(task: Task, allTasks: List<Task>) {
        allTasks.filter { it.parentId == task.id }.forEach { tasks().document(it.id).delete() }
        tasks().document(task.id).delete().addOnFailureListener { Log.w(TAG, "deleteTask", it) }
    }

    suspend fun getTask(id: String): Task? =
        runCatching { tasks().document(id).get().await() }.getOrNull()?.takeIf { it.exists() }?.toTask()

    suspend fun openTasks(source: Source = Source.DEFAULT): List<Task> =
        tasks().whereIn("status", listOf("todo", "doing", "waiting")).get(source).await().documents.map { it.toTask() }

    suspend fun allProjects(): List<Project> =
        runCatching { projects().get().await().documents.map { it.toProject() } }.getOrDefault(emptyList())

    suspend fun allTags(): List<Tag> =
        runCatching { tags().get().await().documents.map { it.toTag() } }.getOrDefault(Defaults.TAGS)

    // ---------- 專案 ----------

    fun createProject(project: Project): String {
        val ref = projects().document()
        ref.set(project.toFields() + mapOf("createdAt" to FieldValue.serverTimestamp(), "updatedAt" to FieldValue.serverTimestamp()))
        return ref.id
    }

    fun saveProject(project: Project) {
        projects().document(project.id).update(project.toFields() + ("updatedAt" to FieldValue.serverTimestamp()))
    }

    fun deleteProject(project: Project, allTasks: List<Task>) {
        allTasks.filter { it.projectId == project.id }.forEach { updateTaskFields(it.id, mapOf("projectId" to null)) }
        projects().document(project.id).delete()
    }

    // ---------- 標籤 ----------

    fun createTag(name: String, color: String): String {
        val ref = tags().document()
        ref.set(mapOf("name" to name, "color" to color, "useCount" to 0))
        return ref.id
    }

    fun deleteTag(id: String) { tags().document(id).delete() }

    fun bumpTag(id: String) {
        tags().document(id).update("useCount", FieldValue.increment(1))
    }

    fun signOut() = auth.signOut()

    companion object { private const val TAG = "Repository" }
}
