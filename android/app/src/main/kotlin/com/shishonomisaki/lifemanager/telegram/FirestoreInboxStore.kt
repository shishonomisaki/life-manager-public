package com.shishonomisaki.lifemanager.telegram

import android.util.Log
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.shishonomisaki.lifemanager.LifeApp
import com.shishonomisaki.lifemanager.core.model.Project
import com.shishonomisaki.lifemanager.core.model.ProjectStatus
import com.shishonomisaki.lifemanager.core.model.Task
import com.shishonomisaki.lifemanager.core.parse.ParseContext
import com.shishonomisaki.lifemanager.data.toTask
import kotlinx.coroutines.tasks.await
import java.time.ZonedDateTime

/** 收件匣的資料存取：待辦走共用的 Repository，inbox 和綁定資訊直接寫 Firestore（欄位見 docs/schema.md）。 */
class FirestoreInboxStore(private val app: LifeApp) : InboxStore {

    private val repo get() = app.repository
    private fun user() = app.firestore.collection("users").document(app.auth.currentUser?.uid ?: error("尚未登入"))
    private fun tasks() = user().collection("tasks")
    private fun inbox() = user().collection("inbox")

    override suspend fun parseContext(now: ZonedDateTime): ParseContext {
        val open = runCatching { repo.openTasks() }.getOrDefault(emptyList())
        return ParseContext(
            now = now,
            projects = repo.allProjects().filter { it.status == ProjectStatus.ACTIVE },
            tags = repo.allTags(),
            openTasks = open.sortedByDescending { it.createdAt }.take(20),
        )
    }

    override fun newTaskId(): String = repo.newTaskId()
    override fun createTask(task: Task) { repo.createTask(task) }
    override fun saveTask(task: Task) = repo.saveTask(task)
    override suspend fun getTask(id: String): Task? = repo.getTask(id)

    override suspend fun children(parentId: String): List<Task> =
        runCatching { tasks().whereEqualTo("parentId", parentId).get().await().documents.map { it.toTask() } }.getOrDefault(emptyList())

    override suspend fun deleteTask(id: String) {
        val t = getTask(id) ?: return
        repo.deleteTask(t, children(id))
    }

    override suspend fun completeTask(id: String) {
        getTask(id)?.let { repo.complete(it) }
    }

    override suspend fun allTasks(): List<Task> = tasks().get().await().documents.map { it.toTask() }

    override fun newInboxId(): String = inbox().document().id

    override fun saveInbox(entry: InboxEntry) {
        inbox().document(entry.id).set(
            mapOf(
                "kind" to entry.kind,
                "text" to entry.text,
                "forwardFrom" to entry.forwardFrom,
                "tgMessageId" to entry.tgMessageId,
                "tgChatId" to entry.tgChatId,
                "status" to entry.status,
                "taskIds" to entry.taskIds,
                "replyMessageId" to entry.replyMessageId,
                "fileRefs" to entry.fileRefs,
                "retryAi" to entry.retryAi,
                "retries" to entry.retries,
                "model" to entry.model,
                "createdAt" to FieldValue.serverTimestamp(),
            ),
            SetOptions.merge(),
        ).addOnFailureListener { Log.w(TAG, "saveInbox", it) }
    }

    override suspend fun getInbox(id: String): InboxEntry? {
        val d = runCatching { inbox().document(id).get().await() }.getOrNull()?.takeIf { it.exists() } ?: return null
        return d.toInbox()
    }

    override suspend fun pendingInbox(): List<InboxEntry> =
        runCatching { inbox().whereEqualTo("retryAi", true).limit(10).get().await().documents.map { it.toInbox() } }
            .getOrDefault(emptyList())

    @Suppress("UNCHECKED_CAST")
    private fun DocumentSnapshot.toInbox() = InboxEntry(
        id = id,
        kind = getString("kind").orEmpty(),
        text = getString("text"),
        forwardFrom = getString("forwardFrom"),
        tgMessageId = getLong("tgMessageId") ?: 0,
        tgChatId = getLong("tgChatId") ?: 0,
        status = getString("status").orEmpty(),
        taskIds = (get("taskIds") as? List<String>).orEmpty(),
        replyMessageId = getLong("replyMessageId") ?: 0,
        fileRefs = (get("fileRefs") as? List<String>).orEmpty(),
        retryAi = getBoolean("retryAi") ?: false,
        retries = getLong("retries")?.toInt() ?: 0,
        model = getString("model"),
    )

    override fun createProject(name: String, topic: String): String = repo.createProject(Project(name = name, topic = topic))

    override fun linkChat(chatId: Long) {
        user().set(
            mapOf("telegram" to mapOf("chatId" to chatId, "linkedAt" to FieldValue.serverTimestamp())),
            SetOptions.merge(),
        )
    }

    companion object { private const val TAG = "InboxStore" }
}
