package com.shishonomisaki.lifemanager.data

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.shishonomisaki.lifemanager.core.model.Project
import com.shishonomisaki.lifemanager.core.model.ProjectStatus
import com.shishonomisaki.lifemanager.core.model.SourceKind
import com.shishonomisaki.lifemanager.core.model.Tag
import com.shishonomisaki.lifemanager.core.model.Task
import com.shishonomisaki.lifemanager.core.model.TaskSource
import com.shishonomisaki.lifemanager.core.model.TaskStatus
import java.time.Instant

/** Firestore 文件 ↔ core 模型。欄位名稱見 docs/schema.md。 */

fun Instant.toTimestamp() = Timestamp(epochSecond, nano)

fun Timestamp.toInstantCompat(): Instant = Instant.ofEpochSecond(seconds, nanoseconds.toLong())

private fun DocumentSnapshot.instant(field: String): Instant? =
    getTimestamp(field, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)?.toInstantCompat()

@Suppress("UNCHECKED_CAST")
fun DocumentSnapshot.toTask(): Task {
    val src = get("source") as? Map<String, Any?>
    return Task(
        id = id,
        title = getString("title").orEmpty(),
        notes = getString("notes"),
        topic = getString("topic") ?: "個人",
        projectId = getString("projectId"),
        parentId = getString("parentId"),
        tags = (get("tags") as? List<String>).orEmpty(),
        autoTagged = (get("autoTagged") as? List<String>).orEmpty().toSet(),
        priority = getLong("priority")?.toInt() ?: 0,
        status = TaskStatus.from(getString("status")),
        waitingFor = getString("waitingFor"),
        dueAt = instant("dueAt"),
        remindAt = instant("remindAt"),
        recurrence = getString("recurrence"),
        completedAt = instant("completedAt"),
        createdAt = instant("createdAt"),
        updatedAt = instant("updatedAt"),
        source = TaskSource(
            kind = SourceKind.from(src?.get("kind") as? String),
            inboxId = src?.get("inboxId") as? String,
            confidence = (src?.get("confidence") as? Number)?.toDouble(),
        ),
        version = getLong("version") ?: 0,
        suggestedProject = getString("suggestedProject"),
    )
}

/** 待辦的內容欄位（不含 createdAt/updatedAt/version，由寫入端處理）。 */
fun Task.toFields(): Map<String, Any?> = mapOf(
    "title" to title,
    "notes" to notes,
    "topic" to topic,
    "projectId" to projectId,
    "parentId" to parentId,
    "tags" to tags,
    "autoTagged" to autoTagged.toList(),
    "priority" to priority,
    "status" to status.value,
    "waitingFor" to waitingFor,
    "dueAt" to dueAt?.toTimestamp(),
    "remindAt" to remindAt?.toTimestamp(),
    "recurrence" to recurrence,
    "completedAt" to completedAt?.toTimestamp(),
    "source" to mapOf(
        "kind" to source.kind.value,
        "inboxId" to source.inboxId,
        "confidence" to source.confidence,
    ),
    "suggestedProject" to suggestedProject,
)

fun DocumentSnapshot.toProject() = Project(
    id = id,
    name = getString("name").orEmpty(),
    topic = getString("topic") ?: "個人",
    goal = getString("goal"),
    status = ProjectStatus.from(getString("status")),
    dueAt = instant("dueAt"),
    createdAt = instant("createdAt"),
    updatedAt = instant("updatedAt"),
)

fun Project.toFields(): Map<String, Any?> = mapOf(
    "name" to name,
    "topic" to topic,
    "goal" to goal,
    "status" to status.value,
    "dueAt" to dueAt?.toTimestamp(),
)

fun DocumentSnapshot.toTag() = Tag(
    id = id,
    name = getString("name").orEmpty(),
    color = getString("color") ?: "#607D8B",
    useCount = getLong("useCount") ?: 0,
)
