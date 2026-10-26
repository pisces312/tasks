package org.tasks.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.todoroo.astrid.activity.MainActivityViewModel
import com.todoroo.astrid.service.TaskCreator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.tasks.R
import org.tasks.analytics.Firebase
import org.tasks.compose.edit.SubtaskRow
import org.tasks.data.TaskSaver
import org.tasks.data.entity.Task
import org.tasks.extensions.hideKeyboard
import org.tasks.filters.SubtaskFilter
import org.tasks.preferences.Preferences
import org.tasks.service.TaskCompleter
import org.tasks.tasklist.SectionedDataSource
import org.tasks.tasklist.TasksResults
import org.tasks.themes.ColorProvider
import org.tasks.time.DateTimeUtils2.currentTimeMillis
import javax.inject.Inject

@AndroidEntryPoint
class SubtaskControlSet : TaskEditControlFragment() {
    @Inject lateinit var activity: Activity
    @Inject lateinit var taskCompleter: TaskCompleter
    @Inject lateinit var taskCreator: TaskCreator
    @Inject lateinit var taskSaver: TaskSaver
    @Inject lateinit var checkBoxProvider: CheckBoxProvider
    @Inject lateinit var chipProvider: ChipProvider
    @Inject lateinit var colorProvider: ColorProvider
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var firebase: Firebase

    private val mainViewModel: MainActivityViewModel by activityViewModels()
    private val listViewModel: TaskListViewModel by viewModels()

    @Composable
    override fun Content() {
        val viewState = viewModel.viewState.collectAsStateWithLifecycle().value
        val inlineTitleEdit = listViewModel.inlineTitleEditState.collectAsStateWithLifecycle().value
        val snackbarHostState = remember { SnackbarHostState() }
        val saveFailedMessage = stringResource(R.string.inline_title_save_failed)
        val cancelLabel = stringResource(R.string.cancel)

        LaunchedEffect(viewState.task) {
            if (viewState.task.id > 0) {
                listViewModel.setFilter(SubtaskFilter(viewState.task.id))
            }
        }
        LaunchedEffect(listViewModel) {
            listViewModel.inlineTitleCommitResults.collect { result ->
                if (result == null) {
                    return@collect
                }
                listViewModel.clearInlineTitleCommitResult(result)
                val action = viewModel.pendingSubtaskTitleAction
                viewModel.pendingSubtaskTitleAction = null
                if (
                    result.success &&
                    viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                ) {
                    perform(action, result.task)
                }
            }
        }
        LaunchedEffect(listViewModel, saveFailedMessage, cancelLabel) {
            listViewModel.inlineTitleSaveFailures.collect {
                val result = snackbarHostState.showSnackbar(
                    message = saveFailedMessage,
                    actionLabel = cancelLabel,
                    duration = SnackbarDuration.Long,
                )
                if (result == SnackbarResult.ActionPerformed) {
                    viewModel.pendingSubtaskTitleAction = null
                    listViewModel.cancelInlineTitleEdit()
                    activity.hideKeyboard()
                }
            }
        }
        BackHandler(enabled = inlineTitleEdit != null) {
            commitInlineTitleEdit()
        }

        val originalState = viewModel.originalState.collectAsStateWithLifecycle().value
        Box(modifier = Modifier.fillMaxWidth()) {
            SubtaskRow(
                originalFilter = originalState.list,
                filter = viewState.list,
                hasParent = viewState.hasParent,
                existingSubtasks = if (viewState.isNew) {
                    TasksResults.Results(SectionedDataSource())
                } else {
                    listViewModel.state.collectAsStateWithLifecycle().value.tasks
                },
                newSubtasks = viewState.newSubtasks,
                inlineTitleEdit = inlineTitleEdit,
                editSubtask = { task, cursorOffset ->
                    beginInlineTitleEdit(task, cursorOffset)
                },
                changeSubtaskTitle = { taskId, title ->
                    listViewModel.updateInlineTitleDraft(taskId, title)
                },
                commitSubtaskTitle = {
                    commitInlineTitleEdit()
                },
                subtaskTitleFocusLost = { taskId ->
                    val state = listViewModel.inlineTitleEditState.value
                    if (state?.taskId == taskId && !state.saving) {
                        commitInlineTitleEdit()
                    }
                },
                onCursorRequestConsumed = { taskId, cursorRequestId ->
                    listViewModel.clearInlineTitleCursorRequest(taskId, cursorRequestId)
                },
                openSubtask = { task ->
                    commitInlineTitleEdit(action = SubtaskTitleAction.OpenDetails(task))
                },
                completeExistingSubtask = { task, completed ->
                    commitInlineTitleEdit(
                        action = SubtaskTitleAction.Complete(task, completed)
                    )
                },
                toggleSubtask = { taskId, collapsed ->
                    commitInlineTitleEdit(
                        action = SubtaskTitleAction.ToggleSubtasks(taskId, collapsed)
                    )
                },
                addSubtask = {
                    commitInlineTitleEdit(action = SubtaskTitleAction.AddSubtask)
                },
                completeNewSubtask = { subtask ->
                    commitInlineTitleEdit(
                        action = SubtaskTitleAction.CompleteNewSubtask(subtask)
                    )
                },
                deleteSubtask = { subtask ->
                    commitInlineTitleEdit(
                        action = SubtaskTitleAction.DeleteNewSubtask(subtask)
                    )
                },
            )
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    override fun onPause() {
        val completedSynchronously = listViewModel.inlineTitleEditState.value != null &&
                listViewModel.commitInlineTitleEdit()
        if (completedSynchronously) {
            viewModel.pendingSubtaskTitleAction = null
        }
        super.onPause()
    }

    private fun beginInlineTitleEdit(task: Task, cursorOffset: Int? = null) {
        if (task.readOnly || listViewModel.inlineTitleEditState.value?.taskId == task.id) {
            return
        }
        commitInlineTitleEdit(
            preserveKeyboard = true,
            action = SubtaskTitleAction.BeginEdit(task, cursorOffset),
        )
    }

    private fun commitInlineTitleEdit(
        preserveKeyboard: Boolean = false,
        action: SubtaskTitleAction? = null,
    ) {
        if (action != null) {
            viewModel.pendingSubtaskTitleAction = action
        } else if (!preserveKeyboard) {
            viewModel.pendingSubtaskTitleAction = null
        }
        val state = listViewModel.inlineTitleEditState.value
        if (state == null) {
            val pendingAction = action ?: viewModel.pendingSubtaskTitleAction
            viewModel.pendingSubtaskTitleAction = null
            perform(pendingAction, null)
            return
        }
        val completedSynchronously = listViewModel.commitInlineTitleEdit(preserveKeyboard)
        if (!preserveKeyboard) {
            activity.hideKeyboard()
        }
        if (completedSynchronously) {
            val pendingAction = viewModel.pendingSubtaskTitleAction
            viewModel.pendingSubtaskTitleAction = null
            perform(pendingAction, null)
        }
    }

    private fun perform(action: SubtaskTitleAction?, savedTask: Task?) {
        when (action) {
            null -> Unit
            is SubtaskTitleAction.OpenDetails -> openSubtask(
                savedTask?.takeIf { it.id == action.task.id } ?: action.task
            )
            is SubtaskTitleAction.Complete -> complete(
                savedTask?.takeIf { it.id == action.task.id } ?: action.task,
                action.completed,
            )
            is SubtaskTitleAction.ToggleSubtasks -> toggleSubtask(action.taskId, action.collapsed)
            is SubtaskTitleAction.AddSubtask -> addSubtask()
            is SubtaskTitleAction.CompleteNewSubtask -> completeNewSubtask(action.task)
            is SubtaskTitleAction.DeleteNewSubtask -> deleteNewSubtask(action.task)
            is SubtaskTitleAction.BeginEdit -> listViewModel.beginInlineTitleEdit(
                action.task.id,
                savedTask?.takeIf { it.id == action.task.id }?.title ?: action.task.title,
                cursorOffset = action.cursorOffset,
            )
        }
    }

    private fun openSubtask(task: Task) {
        mainViewModel.setTask(task)
    }

    private fun toggleSubtask(taskId: Long, collapsed: Boolean) = lifecycleScope.launch {
        taskSaver.setCollapsed(taskId, collapsed)
    }

    private fun complete(task: Task, completed: Boolean) = lifecycleScope.launch {
        taskCompleter.setComplete(task, completed)
        firebase.completeTask("edit_screen_subtask")
    }

    private fun addSubtask() = lifecycleScope.launch {
        viewModel.setSubtasks(
            viewModel.viewState.value.newSubtasks.plus(taskCreator.createWithValues(""))
        )
    }

    private fun completeNewSubtask(subtask: Task) = lifecycleScope.launch {
        val current = viewModel.viewState.value.newSubtasks
        val index = current.indexOfFirst { it.remoteId == subtask.remoteId }
        if (index >= 0) {
            val modified = current[index].copy(
                completionDate = if (current[index].isCompleted) 0 else currentTimeMillis()
            )
            viewModel.setSubtasks(current.toMutableList().apply { set(index, modified) })
        }
    }

    private fun deleteNewSubtask(subtask: Task) = lifecycleScope.launch {
        viewModel.setSubtasks(
            viewModel.viewState.value.newSubtasks.filterNot { it.remoteId == subtask.remoteId }
        )
    }

    companion object {
        val TAG = R.string.TEA_ctrl_subtask_pref
    }
}
