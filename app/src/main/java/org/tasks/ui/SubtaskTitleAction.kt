package org.tasks.ui

import org.tasks.data.entity.Task

/** Action requested after an existing subtask title commit finishes. */
sealed interface SubtaskTitleAction {
    data class OpenDetails(val task: Task) : SubtaskTitleAction
    data class Complete(val task: Task, val completed: Boolean) : SubtaskTitleAction
    data class ToggleSubtasks(val taskId: Long, val collapsed: Boolean) : SubtaskTitleAction
    data object AddSubtask : SubtaskTitleAction
    data class CompleteNewSubtask(val task: Task) : SubtaskTitleAction
    data class DeleteNewSubtask(val task: Task) : SubtaskTitleAction
    data class BeginEdit(val task: Task) : SubtaskTitleAction
}
