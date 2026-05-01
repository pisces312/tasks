package org.tasks.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import org.tasks.BuildConfig
import org.tasks.caldav.VtodoCache
import org.tasks.data.dao.AlarmDao
import org.tasks.data.dao.CaldavDao
import org.tasks.data.dao.FilterDao
import org.tasks.data.dao.LocationDao
import org.tasks.data.dao.TagDao
import org.tasks.data.dao.TagDataDao
import org.tasks.data.dao.TaskAttachmentDao
import org.tasks.data.dao.TaskDao
import org.tasks.data.dao.TaskListMetadataDao
import org.tasks.data.dao.UserActivityDao
import org.tasks.data.entity.Alarm
import org.tasks.data.entity.CaldavAccount
import org.tasks.data.entity.CaldavCalendar
import org.tasks.data.entity.CaldavTask
import org.tasks.data.entity.Filter
import org.tasks.data.entity.Geofence
import org.tasks.data.entity.Place
import org.tasks.data.entity.Tag
import org.tasks.data.entity.TagData
import org.tasks.data.entity.Task
import org.tasks.data.entity.Attachment
import org.tasks.data.entity.TaskAttachment
import org.tasks.data.entity.TaskListMetadata
import org.tasks.data.entity.UserActivity
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Git-diff friendly JSON exporter for tasks data.
 *
 * Key differences from TasksJsonExporter:
 * - Pretty-print output (2-space indent) for readable git diffs
 * - Deterministic ordering: tasks sorted by UUID, keys sorted alphabetically
 * - No timestamp field (avoids noise in diffs)
 * - Fixed filename (tasks.json) so git tracks modifications, not new files
 * - Outputs directly to the git repo working directory
 *
 * Uses JsonElement builders instead of Map<String, Any?> to avoid
 * kotlinx.serialization runtime errors (Any? is not serializable).
 */
@Singleton
class GitJsonExporter @Inject constructor(
    private val tagDataDao: TagDataDao,
    private val taskDao: TaskDao,
    private val userActivityDao: UserActivityDao,
    private val alarmDao: AlarmDao,
    private val locationDao: LocationDao,
    private val tagDao: TagDao,
    private val filterDao: FilterDao,
    private val taskAttachmentDao: TaskAttachmentDao,
    private val caldavDao: CaldavDao,
    private val taskListMetadataDao: TaskListMetadataDao,
    private val vtodoCache: VtodoCache,
) {
    private val gitJson = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        ignoreUnknownKeys = true
    }

    /**
     * Export all tasks data to the git repo working directory.
     * Output file: repoDir/tasks.json
     *
     * @return the number of tasks exported
     */
    suspend fun exportTo(repoDir: File): Int = withContext(Dispatchers.IO) {
        val taskIds = taskDao.getAllTaskIds()
        if (taskIds.isEmpty()) {
            Timber.w("No tasks to export")
            return@withContext 0
        }

        // Fetch all tasks and sort by UUID for deterministic output
        val allTasks = taskIds.mapNotNull { id ->
            try {
                taskDao.fetch(id)
            } catch (e: Exception) {
                Timber.e(e, "Failed to fetch task $id")
                null
            }
        }.sortedBy { it.uuid }

        // Build task entries
        val tasksArray = JsonArray(
            allTasks.mapNotNull { task ->
                try {
                    buildTaskBackup(task)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to export task ${task.id}")
                    null
                }
            }
        )

        // Pre-fetch all data (suspend calls must happen outside buildJsonObject)
        val places = encodeList(locationDao.getPlaces(), Place.serializer())
        val tags = encodeList(tagDataDao.getAll().sortedBy { it.remoteId }, TagData.serializer())
        val filters = encodeList(filterDao.getFilters().sortedBy { it.title }, Filter.serializer())
        val caldavAccounts = encodeList(caldavDao.getAccounts().sortedBy { it.uuid }, CaldavAccount.serializer())
        val caldavCalendars = encodeList(caldavDao.getCalendars().sortedBy { it.uuid }, CaldavCalendar.serializer())
        val taskListMetadata = encodeList(taskListMetadataDao.getAll(), TaskListMetadata.serializer())
        val taskAttachments = encodeList(taskAttachmentDao.getAttachments(), TaskAttachment.serializer())

        // Assemble root JSON object (no suspend calls here)
        val root = JsonObject(mapOf(
            "version" to JsonPrimitive(BuildConfig.VERSION_CODE),
            "tasks" to tasksArray,
            "places" to places,
            "tags" to tags,
            "filters" to filters,
            "caldavAccounts" to caldavAccounts,
            "caldavCalendars" to caldavCalendars,
            "taskListMetadata" to taskListMetadata,
            "taskAttachments" to taskAttachments,
        ))

        val jsonContent = gitJson.encodeToString(JsonObject.serializer(), root)

        val outputFile = File(repoDir, TASKS_JSON_FILENAME)
        outputFile.writeText(jsonContent)

        Timber.d("Exported ${allTasks.size} tasks to ${outputFile.absolutePath}")
        allTasks.size
    }

    private suspend fun buildTaskBackup(task: Task): JsonObject {
        val taskId = task.id
        val caldavTasks = caldavDao.getTasks(taskId)
        val vtodo = vtodoCache.getVtodo(caldavTasks.firstOrNull { !it.isDeleted() })

        // Pre-fetch all data (suspend calls outside of JsonObject builder)
        val alarms = encodeList(alarmDao.getAlarms(taskId), Alarm.serializer())
        val geofences = encodeList(locationDao.getGeofencesForTask(taskId), Geofence.serializer())
        val tags = encodeList(tagDao.getTagsForTask(taskId), Tag.serializer())
        val comments = encodeList(userActivityDao.getComments(taskId), UserActivity.serializer())
        val attachments = encodeList(taskAttachmentDao.getAttachmentsForTask(taskId), Attachment.serializer())
        val caldavTasksJson = encodeList(caldavTasks, CaldavTask.serializer())

        val entries = mutableMapOf<String, JsonElement>()
        entries["task"] = gitJson.encodeToJsonElement(Task.serializer(), task)
        entries["alarms"] = alarms
        entries["geofences"] = geofences
        entries["tags"] = tags
        entries["comments"] = comments
        entries["attachments"] = attachments
        vtodo?.let { entries["vtodo"] = JsonPrimitive(it) }
        entries["caldavTasks"] = caldavTasksJson

        return JsonObject(entries)
    }

    /**
     * Encode a list of @Serializable items into a JsonArray.
     */
    private fun <T> encodeList(
        items: List<T>,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): JsonArray = JsonArray(items.map { gitJson.encodeToJsonElement(serializer, it) })

    companion object {
        const val TASKS_JSON_FILENAME = "tasks.json"
    }
}
