package org.tasks.tasklist

import android.view.ViewGroup
import androidx.recyclerview.widget.ListUpdateCallback
import androidx.recyclerview.widget.RecyclerView
import com.todoroo.astrid.activity.TaskListFragment
import com.todoroo.astrid.adapter.TaskAdapter
import com.todoroo.astrid.adapter.TaskAdapterDataSource
import org.tasks.data.entity.Task
import org.tasks.preferences.Preferences

abstract class TaskListRecyclerAdapter internal constructor(
        private val adapter: TaskAdapter,
        internal val viewHolderFactory: ViewHolderFactory,
        private val taskList: TaskListFragment,
        internal val preferences: Preferences
): RecyclerView.Adapter<RecyclerView.ViewHolder>(), ListUpdateCallback, TaskAdapterDataSource {

    var dirtyTaskIds: Set<Long> = emptySet()
        set(value) {
            if (field != value) {
                field = value
                notifyItemRangeChanged(0, itemCount)
            }
        }

    var dirtyColor: Int = 0

    private val committedTitleOverrides = mutableMapOf<Long, CommittedTitleOverride>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder
            = viewHolderFactory.newViewHolder(parent, taskList)

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val filter = taskList.getFilter()
        val task = getItem(position)
        if (task != null) {
            (holder as TaskViewHolder)
                    .bindView(
                        task,
                        filter,
                        groupMode,
                        dirtyTaskIds.contains(task.id),
                        dirtyColor,
                        taskList.inlineTitleEditState,
                        committedTitleOverrides[task.id]?.committedTitle ?: task.title,
                    )
            holder.moving = false
            val indent = adapter.getIndent(task)
            task.indent = indent
            task.targetIndent = indent
            holder.indent = indent
            holder.selected = adapter.isSelected(task)
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>,
    ) {
        val inlinePayload = payloads.lastOrNull { it is InlineTitlePayload } as? InlineTitlePayload
        if (holder is TaskViewHolder && inlinePayload != null) {
            val task = getItem(position) ?: return
            holder.bindInlineTitleEdit(
                taskList.inlineTitleEditState?.takeIf { it.taskId == task.id },
                inlinePayload.committedTask?.takeIf { it.id == task.id },
            )
        } else {
            onBindViewHolder(holder, position)
        }
    }

    fun notifyInlineTitleStateChanged(
        taskIds: Collection<Long>,
        committedTask: Task? = null,
    ) {
        if (taskIds.isEmpty()) {
            return
        }
        val ids = taskIds.toHashSet()
        val matches = buildList {
            for (position in 0 until itemCount) {
                if (!isHeader(position)) {
                    getItem(position)?.takeIf { it.id in ids }?.let { add(position to it) }
                }
            }
        }
        matches.forEach { (position, _) ->
            notifyItemChanged(position, InlineTitlePayload(committedTask))
        }
    }

    fun recordCommittedTitle(saved: Task) {
        for (position in 0 until itemCount) {
            if (isHeader(position)) {
                continue
            }
            val task = getItem(position) ?: continue
            if (task.id == saved.id) {
                committedTitleOverrides[saved.id] = CommittedTitleOverride(
                    originalTitle = task.title,
                    committedTitle = saved.title,
                )
                return
            }
        }
    }

    fun reconcileInlineTitleOverrides(list: SectionedDataSource) {
        if (committedTitleOverrides.isEmpty()) {
            return
        }
        val seen = hashSetOf<Long>()
        for (position in 0 until list.size) {
            if (list.isHeader(position)) {
                continue
            }
            val task = list.getItem(position)
            val override = committedTitleOverrides[task.id] ?: continue
            seen.add(task.id)
            if (
                task.title == override.committedTitle ||
                task.title != override.originalTitle
            ) {
                committedTitleOverrides.remove(task.id)
            }
        }
        committedTitleOverrides.keys.retainAll(seen)
    }

    fun toggle(taskViewHolder: TaskViewHolder) {
        adapter.toggleSelection(taskViewHolder.task)
        notifyItemChanged(taskViewHolder.bindingAdapterPosition)
        if (adapter.getSelected().isEmpty()) {
            taskList.finishActionMode()
        } else {
            taskList.updateModeTitle()
        }
    }

    abstract fun dragAndDropEnabled(): Boolean

    abstract fun submitList(list: SectionedDataSource)

    abstract val groupMode: Int

    override fun onInserted(position: Int, count: Int) {
        notifyItemRangeInserted(position, count)
    }

    override fun onRemoved(position: Int, count: Int) {
        notifyItemRangeRemoved(position, count)
    }

    override fun onMoved(fromPosition: Int, toPosition: Int) {
        notifyItemMoved(fromPosition, toPosition)
    }

    override fun onChanged(position: Int, count: Int, payload: Any?) {
        notifyItemRangeChanged(position, count, payload)
    }
}

private data class InlineTitlePayload(
    val committedTask: Task?,
)

private data class CommittedTitleOverride(
    val originalTitle: String?,
    val committedTitle: String?,
)
