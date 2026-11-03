package com.todoroo.astrid.service

import org.tasks.data.TaskSaver
import org.tasks.data.dao.AlarmDao
import org.tasks.data.dao.CaldavDao
import org.tasks.data.dao.GoogleTaskDao
import org.tasks.data.dao.TaskDao
import org.tasks.data.entity.CaldavTask
import org.tasks.data.entity.Task
import org.tasks.data.getDefaultAlarms
import org.tasks.filters.CaldavFilter
import org.tasks.preferences.DefaultFilterProvider
import org.tasks.preferences.Preferences
import javax.inject.Inject

/**
 * Creates and persists subtasks under an existing parent, outside the task edit screen.
 *
 * [saveSubtask] is the single implementation of "attach a task to a parent in a list"; the task
 * edit screen calls it too, so the list screen and the detail screen cannot drift apart (different
 * list membership, missing remote parent, or a subtask that ignores the parent's completion).
 */
class SubtaskCreator @Inject constructor(
    private val taskCreator: TaskCreator,
    private val taskDao: TaskDao,
    private val taskSaver: TaskSaver,
    private val caldavDao: CaldavDao,
    private val googleTaskDao: GoogleTaskDao,
    private val alarmDao: AlarmDao,
    private val preferences: Preferences,
    private val defaultFilterProvider: DefaultFilterProvider,
) {

    /**
     * @return the created subtask, or null when the parent's list is read only
     */
    suspend fun createSubtask(parent: Task, title: String = ""): Task? {
        val list = defaultFilterProvider.getList(parent)
        if (list.isReadOnly) {
            return null
        }
        val subtask = taskCreator.createWithValues(title)
        saveSubtask(parent, subtask, list, preferences.addTasksToTop())
        return subtask
    }

    /**
     * Persists [subtask] under [parent] in [list], matching what the task edit screen has always
     * done for the subtasks added there.
     *
     * @param addToTop whether the list order puts the new row on top
     *   (`preferences.addTasksToTop()`)
     */
    suspend fun saveSubtask(
        parent: Task,
        subtask: Task,
        list: CaldavFilter,
        addToTop: Boolean,
    ) {
        if (!subtask.isCompleted) {
            // subtasks follow their completed parent, as on the detail screen
            subtask.completionDate = parent.completionDate
        }
        taskDao.createNew(subtask)
        alarmDao.insert(subtask.getDefaultAlarms(preferences.isDefaultDueTimeEnabled()))
        if (list.isGoogleTasks) {
            val googleTask = CaldavTask(
                task = subtask.id,
                calendar = list.uuid,
                remoteId = null,
            )
            subtask.parent = parent.id
            googleTask.isMoved = true
            googleTaskDao.insertAndShift(
                task = subtask,
                caldavTask = googleTask,
                top = addToTop,
            )
        } else {
            val caldavTask = CaldavTask(
                task = subtask.id,
                calendar = list.uuid,
            )
            subtask.parent = parent.id
            if (list.account.pushesRemoteParent) {
                caldavTask.remoteParent = caldavDao.getRemoteIdForTask(parent.id)
            }
            taskSaver.save(subtask, null)
            caldavDao.insert(
                task = subtask,
                caldavTask = caldavTask,
                addToTop = addToTop,
            )
        }
    }
}
