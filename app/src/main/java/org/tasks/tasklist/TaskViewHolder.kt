package org.tasks.tasklist

import android.app.Activity
import android.content.Context
import android.graphics.Paint
import android.text.InputFilter
import android.text.TextWatcher
import android.util.DisplayMetrics
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.recyclerview.widget.RecyclerView
import com.todoroo.astrid.core.SortHelper.SORT_DUE
import com.todoroo.astrid.core.SortHelper.SORT_LIST
import com.todoroo.astrid.core.SortHelper.SORT_START
import com.todoroo.astrid.ui.CheckableImageView
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.color.MaterialColors
import org.tasks.R
import org.tasks.compose.chips.ChipGroup
import org.tasks.compose.chips.FilterChip
import org.tasks.compose.StartDateChip
import org.tasks.compose.chips.SubtaskChip
import org.tasks.data.INDENT_STEP_DP
import org.tasks.data.TaskContainer
import org.tasks.data.entity.Task
import org.tasks.data.hasNotes
import org.tasks.data.isHidden
import org.tasks.data.isOverdue
import org.tasks.databinding.TaskAdapterRowBinding
import org.tasks.dialogs.Linkify
import org.tasks.extensions.Context.is24HourFormat
import org.tasks.filters.CaldavFilter
import org.tasks.filters.Filter
import org.tasks.filters.PlaceFilter
import org.tasks.filters.TagFilter
import org.tasks.kmp.org.tasks.time.DateFormatter
import org.tasks.kmp.formatTime
import org.tasks.markdown.Markdown
import org.tasks.preferences.Preferences
import org.tasks.themes.TasksIcons
import org.tasks.themes.TasksTheme
import org.tasks.themes.Theme
import org.tasks.time.DateTimeUtils2.currentTimeMillis
import org.tasks.time.startOfDay
import org.tasks.ui.CheckBoxProvider
import org.tasks.ui.ChipProvider
import org.tasks.ui.InlineTitleEditState
import kotlin.math.max
import kotlin.math.roundToInt

class TaskViewHolder internal constructor(
    private val context: Activity,
    binding: TaskAdapterRowBinding,
    private val preferences: Preferences,
    fontSize: Int,
    private val chipProvider: ChipProvider,
    private val checkBoxProvider: CheckBoxProvider,
    private val textColorOverdue: Int,
    private val callback: ViewHolderCallbacks,
    private val metrics: DisplayMetrics,
    private val background: Int,
    private val selectedColor: Int,
    private val rowPaddingDp: Int,
    private val rowPaddingPx: Int,
    private val linkify: Linkify,
    private val markdown: Markdown,
    private val theme: Theme,
    private val dateFormatter: DateFormatter,
) : RecyclerView.ViewHolder(binding.root) {
    private val row: ViewGroup = binding.row
    private val dueDate: TextView = binding.dueDate.apply {
        setOnClickListener { changeDueDate() }
    }
    private val rowBody: ViewGroup = binding.rowBody.apply {
        setOnClickListener { callback.onOutsideClick(this@TaskViewHolder) }
        setOnLongClickListener { callback.onLongPress(this@TaskViewHolder) }
    }
    private var titleTapX: Float? = null
    private var titleTapY: Float? = null
    private val titleContainer = binding.titleContainer
    private val nameView: TextView = binding.title
    private val titleEditor: EditText = binding.titleEditor
    private val titleEditorKeyListener = titleEditor.keyListener
    private val titleEditorFilters = titleEditor.filters
    private val preserveKeyboardInputFilter = InputFilter { _, _, _, dest, dstart, dend ->
        dest.subSequence(dstart, dend)
    }
    private var titleEditorInputType = 0
    private var displayedTitleSource: String? = null
    private var inlineEditing = false
    private val openDetails = binding.openDetails.apply {
        setOnClickListener { onTrailingActionClick() }
    }
    private var titleTextWatcher: TextWatcher? = null
    private var cursorPositionRunnable: Runnable? = null
    private var boundEditorTaskId: Long? = null
    private val description: TextView = binding.description
    private val completeBox: CheckableImageView = binding.completeBox.apply {
        setOnClickListener { onCompleteBoxClick() }
    }
    private val chipGroup: ComposeView = binding.chipGroup
    private val debugDirty: View = binding.debugDirty
    private val alwaysDisplayFullDate: Boolean = preferences.alwaysDisplayFullDate

    lateinit var task: TaskContainer

    var indent = 0
        set(value) {
            field = value
            val indentSize = getIndentSize(value)
            val layoutParams = row.layoutParams as MarginLayoutParams
            layoutParams.marginStart = indentSize
            row.layoutParams = layoutParams
        }

    var selected = false
        set(value) {
            field = value
            updateBackground()
        }

    var moving = false
        set(value) {
            field = value
            updateBackground()
        }

    var minIndent = 0
        set(value) {
            field = value
            if (task.targetIndent < value) {
                task.targetIndent = value
            }
        }

    var maxIndent = 0
        set(value) {
            field = value
            if (task.targetIndent > value) {
                task.targetIndent = value
            }
        }

    private fun setTopPadding(padding: Int, vararg views: View) {
        for (v in views) {
            v.setPaddingRelative(v.paddingStart, padding, v.paddingEnd, v.paddingBottom)
        }
    }

    private fun setBottomPadding(padding: Int, vararg views: View) {
        for (v in views) {
            v.setPaddingRelative(v.paddingStart, v.paddingTop, v.paddingEnd, padding)
        }
    }
    
    private fun setupTitleClickListeners(editable: Boolean) {
        nameView.movementMethod = null
        nameView.setOnLongClickListener(null)
        if (editable) {
            titleContainer.setOnTouchListener { _, event -> recordTitleTouch(event) }
            titleContainer.setOnClickListener { editTitleAtLastTap() }
            nameView.setOnTouchListener { _, event -> recordTitleTouch(event) }
            nameView.setOnClickListener { editTitleAtLastTap() }
        } else {
            titleContainer.setOnTouchListener(null)
            titleContainer.setOnClickListener(null)
            nameView.setOnTouchListener(null)
            nameView.setOnClickListener(null)
        }
    }

    private fun recordTitleTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_UP -> {
                titleTapX = event.x
                titleTapY = event.y
            }
            MotionEvent.ACTION_CANCEL -> {
                titleTapX = null
                titleTapY = null
            }
        }
        return false
    }

    private fun onTrailingActionClick() {
        if (inlineEditing) {
            openDetails.isEnabled = false
            callback.onTitleCommit(this)
        } else {
            callback.onOpenDetails(this)
        }
    }

    private fun editTitleAtLastTap() {
        val cursorX = titleTapX
        val cursorY = titleTapY
        val cursorOffset = if (cursorX != null && cursorY != null) {
            sourceOffsetAtTap(cursorX, cursorY)
        } else {
            null
        }
        titleTapX = null
        titleTapY = null
        callback.onTitleClick(
            this,
            displayedTitleSource ?: task.title,
            cursorOffset,
            cursorX,
            cursorY,
        )
    }

    private fun sourceOffsetAtTap(x: Float, y: Float): Int? {
        val source = displayedTitleSource ?: task.title.orEmpty()
        val rendered = nameView.text.toString()
        if (source.isEmpty() || rendered.isEmpty()) {
            return 0
        }
        val renderedOffset = nameView.getOffsetForPosition(x, y)
            .coerceIn(0, rendered.length)
        if (source == rendered) {
            return renderedOffset.coerceIn(0, source.length)
        }

        var sourceIndex = 0
        val sourcePositions = IntArray(rendered.length)
        rendered.forEachIndexed { index, character ->
            val match = source.indexOf(character, sourceIndex)
            if (match < 0) {
                return null
            }
            sourcePositions[index] = match
            sourceIndex = match + 1
        }
        return if (renderedOffset == rendered.length) {
            (sourcePositions.last() + 1).coerceAtMost(source.length)
        } else {
            sourcePositions[renderedOffset]
        }
    }

    private fun updateBackground() {
        if (selected || moving) {
            rowBody.setBackgroundColor(selectedColor)
        } else {
            rowBody.setBackgroundResource(background)
            rowBody.background?.jumpToCurrentState()
        }
    }

    val shiftSize: Float
        get() = INDENT_STEP_DP * metrics.density

    private fun getIndentSize(indent: Int) = (indent * shiftSize).roundToInt()

    fun bindView(
        task: TaskContainer,
        filter: Filter,
        sortMode: Int,
        isDirty: Boolean = false,
        dirtyColor: Int = 0,
        inlineTitleEditState: InlineTitleEditState? = null,
        displayedTitle: String? = task.title,
    ) {
        titleTapX = null
        titleTapY = null
        this.task = task
        setupTitleClickListeners(!task.isReadOnly)
        if (isDirty) {
            debugDirty.setBackgroundColor(dirtyColor)
            debugDirty.visibility = View.VISIBLE
        } else {
            debugDirty.visibility = View.GONE
        }
        indent = task.indent
        displayedTitleSource = displayedTitle
        markdown.setMarkdown(nameView, displayedTitle)
        setupTitleAndCheckbox()
        bindInlineTitleEdit(
            inlineTitleEditState?.takeIf { it.taskId == task.id },
        )
        setupDueDate(sortMode == SORT_DUE)
        setupChips(
            filter = filter,
            sortByStartDate = sortMode == SORT_START,
            sortByList = sortMode == SORT_LIST
        )
        if (preferences.getBoolean(R.string.p_show_description, true)) {
            markdown.setMarkdown(description, task.notes)
            description.visibility = if (task.task.hasNotes()) View.VISIBLE else View.GONE
        }
        if (markdown.enabled || preferences.getBoolean(R.string.p_linkify_task_list, false)) {
            if (!markdown.enabled) {
                Linkify.safeLinkify(nameView)
                Linkify.safeLinkify(description)
            }
            if (!task.isReadOnly) {
                linkify.setMovementMethod(
                    nameView,
                    linkClickHandler = { url -> callback.onLinkClicked(this, url) },
                    rowClickHandler = { editTitleAtLastTap() }
                )
                nameView.setOnLongClickListener { callback.onLongPress(this) }
            }
            linkify.setMovementMethod(
                description,
                linkClickHandler = { url -> callback.onLinkClicked(this, url) },
                rowClickHandler = { callback.onOutsideClick(this) }
            )
            description.setOnLongClickListener { callback.onLongPress(this) }
        }
        if (task.isReadOnly) {
            nameView.movementMethod = null
            nameView.setOnTouchListener(null)
            nameView.setOnClickListener(null)
            nameView.setOnLongClickListener(null)
        }
        when {
            chipGroup.visibility == View.VISIBLE -> {
                setBottomPadding(0, description, nameView, titleEditor)
            }
            description.visibility == View.VISIBLE -> {
                setBottomPadding(rowPaddingPx, description)
                setBottomPadding(0, nameView, titleEditor)
            }
            else -> {
                setBottomPadding(rowPaddingPx, nameView, titleEditor)
            }
        }
    }

    fun bindInlineTitleEdit(
        editState: InlineTitleEditState?,
        committedTask: Task? = null,
    ) {
        committedTask?.let { saved ->
            displayedTitleSource = saved.title
            markdown.setMarkdown(nameView, saved.title)
        }
        setupInlineTitleEdit(editState)
    }

    private fun setupInlineTitleEdit(editState: InlineTitleEditState?) {
        cursorPositionRunnable?.let(titleEditor::removeCallbacks)
        cursorPositionRunnable = null
        titleTextWatcher?.let(titleEditor::removeTextChangedListener)
        titleTextWatcher = null
        titleEditor.setOnEditorActionListener(null)
        titleEditor.onFocusChangeListener = null

        if (editState == null) {
            inlineEditing = false
            openDetails.setImageResource(R.drawable.ic_chevron_right_24dp)
            openDetails.contentDescription = context.getString(R.string.open_task_details)
            openDetails.isEnabled = true
            openDetails.alpha = 1f
            boundEditorTaskId = null
            titleEditor.clearFocus()
            titleEditor.isEnabled = true
            titleEditor.keyListener = titleEditorKeyListener
            titleEditor.setRawInputType(titleEditorInputType)
            titleEditor.filters = titleEditorFilters
            titleEditor.isFocusableInTouchMode = true
            titleEditor.isCursorVisible = true
            titleEditor.isLongClickable = true
            titleEditor.visibility = View.GONE
            nameView.visibility = View.VISIBLE
            return
        }

        inlineEditing = true
        openDetails.setImageResource(R.drawable.ic_check_24dp)
        openDetails.contentDescription = context.getString(R.string.save_task_title)
        openDetails.isEnabled = !editState.saving
        openDetails.alpha = if (editState.saving) 0.38f else 1f
        val editorTaskChanged = boundEditorTaskId != editState.taskId
        boundEditorTaskId = editState.taskId
        nameView.visibility = View.INVISIBLE
        titleEditor.visibility = View.VISIBLE
        titleEditor.isEnabled = true
        titleEditor.setTextColor(nameView.currentTextColor)
        if (titleEditor.keyListener == null) {
            titleEditor.keyListener = titleEditorKeyListener
        }
        if (titleEditor.inputType != titleEditorInputType) {
            titleEditor.setRawInputType(titleEditorInputType)
        }
        titleEditor.filters = titleEditorFilters
        val replacedText = titleEditor.text.toString() != editState.draftTitle
        if (replacedText) {
            titleEditor.setText(editState.draftTitle)
        }
        when {
            !editState.saving -> {
                titleEditor.isFocusableInTouchMode = true
                titleEditor.isCursorVisible = true
                titleEditor.isLongClickable = true
            }
            editState.preserveKeyboard -> {
                titleEditor.filters = titleEditorFilters + preserveKeyboardInputFilter
                titleEditor.isFocusableInTouchMode = true
                titleEditor.isCursorVisible = false
                titleEditor.isLongClickable = false
            }
            else -> {
                titleEditor.clearFocus()
                titleEditor.keyListener = null
                titleEditor.isFocusable = false
                titleEditor.isCursorVisible = false
                titleEditor.isLongClickable = false
            }
        }
        if (!editState.saving) {
            titleTextWatcher = titleEditor.doAfterTextChanged {
                callback.onTitleChanged(task.id, it?.toString().orEmpty())
            }
            titleEditor.setOnEditorActionListener { view, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    view.onFocusChangeListener = null
                    view.isCursorVisible = false
                    view.isFocusable = false
                    callback.onTitleCommit(this)
                    (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .hideSoftInputFromWindow(view.windowToken, 0)
                    true
                } else {
                    false
                }
            }
            titleEditor.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                if (!hasFocus && bindingAdapterPosition != RecyclerView.NO_POSITION) {
                    titleEditor.onFocusChangeListener = null
                    titleEditor.isCursorVisible = false
                    titleEditor.isFocusable = false
                    callback.onTitleCommit(this)
                }
            }
        }
        val cursorRequestId = editState.cursorRequestId
        val needsInitialFocus = editorTaskChanged || cursorRequestId != null || !titleEditor.hasFocus()
        if (!editState.saving && needsInitialFocus) {
            val taskId = task.id
            val positionCursor = Runnable {
                cursorPositionRunnable = null
                if (
                    bindingAdapterPosition == RecyclerView.NO_POSITION ||
                    task.id != taskId ||
                    titleEditor.visibility != View.VISIBLE ||
                    !titleEditor.requestFocus()
                ) {
                    return@Runnable
                }
                val imeVisible = ViewCompat.getRootWindowInsets(titleEditor)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) == true
                val requestedOffset = editState.cursorOffset
                val cursorX = editState.cursorX
                val cursorY = editState.cursorY
                when {
                    requestedOffset != null -> {
                        titleEditor.setSelection(
                            requestedOffset.coerceIn(0, titleEditor.text.length)
                        )
                    }
                    cursorX != null && cursorY != null &&
                            titleEditor.width > 0 && titleEditor.height > 0 -> {
                        val x = cursorX.coerceIn(
                            0f,
                            (titleEditor.width - 1).coerceAtLeast(0).toFloat(),
                        )
                        val y = cursorY.coerceIn(
                            0f,
                            (titleEditor.height - 1).coerceAtLeast(0).toFloat(),
                        )
                        val offset = titleEditor.getOffsetForPosition(x, y)
                            .coerceIn(0, titleEditor.text.length)
                        titleEditor.setSelection(offset)
                    }
                    editorTaskChanged || replacedText || cursorRequestId != null -> {
                        titleEditor.setSelection(titleEditor.text.length)
                    }
                }
                if (cursorRequestId != null) {
                    callback.onTitleCursorPositioned(taskId, cursorRequestId)
                }
                if (!imeVisible) {
                    (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .showSoftInput(titleEditor, InputMethodManager.SHOW_IMPLICIT)
                }
            }
            cursorPositionRunnable = positionCursor
            titleEditor.post(positionCursor)
        }
    }

    private fun setupTitleAndCheckbox() {
        val onSurface = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, 0)
        val onSurfaceDim = ColorUtils.setAlphaComponent(onSurface, 0x61)
        if (task.isCompleted) {
            nameView.setTextColor(onSurfaceDim)
            nameView.paintFlags = nameView.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        } else {
            nameView.setTextColor(if (task.task.isHidden) onSurfaceDim else onSurface)
            nameView.paintFlags = nameView.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        }
        completeBox.isChecked = task.isCompleted
        completeBox.setImageDrawable(checkBoxProvider.getCheckBox(task.task))
        completeBox.invalidate()
    }

    private fun setupDueDate(sortByDueDate: Boolean) {
        if (task.hasDueDate()) {
            if (task.task.isOverdue) {
                dueDate.setTextColor(textColorOverdue)
            } else {
                dueDate.setTextColor(MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, 0))
            }
            val dateValue: String? = if (sortByDueDate
                    && (task.sortGroup ?: 0) >= currentTimeMillis().startOfDay()
            ) {
                task.takeIf { it.hasDueTime() }?.let {
                    dateFormatter.time(task.dueDate)
                }
            } else {
                dateFormatter.relativeDateTime(
                    task.dueDate,
                    alwaysDisplayFullDate = alwaysDisplayFullDate
                )
            }
            dueDate.text = dateValue
            dueDate.visibility = View.VISIBLE
        } else {
            dueDate.visibility = View.GONE
        }
    }

    private fun setupChips(filter: Filter, sortByStartDate: Boolean, sortByList: Boolean) {
        val id = task.id
        val children = task.children
        val chipCount = task.chipCount
        val collapsed = task.isCollapsed
        val isHidden = task.task.isHidden
        val sortGroup = task.sortGroup
        val startDate = task.task.hideUntil
        val place = task.location?.place
        val list = task.caldav
        val tagsString = task.tagsString
        val appearance = preferences.getIntegerFromString(R.string.p_chip_appearance, 0)
        val showText = appearance != 2
        val showIcon = appearance != 1
        val toggleSubtasks = { task: Long, collapsed: Boolean -> callback.toggleSubtasks(task, collapsed) }
        val onClick = { it: Filter -> callback.onClick(it) }
        chipGroup.setContent {
            TasksTheme(
                theme = theme.themeBase.index,
                primary = theme.themeColor.primaryColor,
            ) {
                ChipGroup(
                    modifier = Modifier.padding(
                        end = 16.dp,
                        bottom = rowPaddingDp.dp
                    )
                ) {
                    if (children > 0 && remember { preferences.showSubtaskChip }) {
                        SubtaskChip(
                            collapsed = collapsed,
                            children = chipCount,
                            onClick = { toggleSubtasks(id, !collapsed) }
                        )
                    }
                    if (
                        isHidden &&
                        remember { preferences.showStartDateChip } &&
                        startDate != task.dueDate &&
                        startDate != task.dueDate.startOfDay()
                    ) {
                        StartDateChip(
                            sortGroup = sortGroup,
                            startDate = startDate,
                            compact = !showText,
                            timeOnly = sortByStartDate,
                            colorProvider = { chipProvider.getColor(it) },
                            dateFormatter = dateFormatter,
                        )
                    }
                    if (place != null && filter !is PlaceFilter && remember { preferences.showPlaceChip }) {
                        FilterChip(
                            filter = PlaceFilter(place),
                            defaultIcon = TasksIcons.PLACE,
                            onClick = onClick,
                            showText = showText,
                            showIcon = showIcon,
                            colorProvider = { chipProvider.getColor(it) },
                        )
                    }

                    if (
                        indent == 0 &&
                        !sortByList &&
                        preferences.showListChip &&
                        filter !is CaldavFilter
                    ) {
                        remember(list, chipProvider.lists.listsCount) {
                            chipProvider.lists.getCaldavList(list)
                        }?.let {
                            FilterChip(
                                filter = it,
                                defaultIcon = TasksIcons.LIST,
                                onClick = onClick,
                                showText = showText,
                                showIcon = showIcon,
                                colorProvider = { chipProvider.getColor(it) },
                            )
                        }
                    }
                    if (!tagsString.isNullOrBlank() && remember { preferences.showTagChip }) {
                        remember(tagsString, filter, chipProvider.lists.tagsVersion) {
                            val tags = tagsString.split(",").toHashSet()
                            if (filter is TagFilter) {
                                tags.remove(filter.uuid)
                            }
                            tags.mapNotNull { chipProvider.lists.getTag(it) }
                                .sortedBy(TagFilter::title)
                        }
                            .forEach {
                                FilterChip(
                                    filter = it,
                                    defaultIcon = TasksIcons.LABEL,
                                    onClick = onClick,
                                    showText = showText,
                                    showIcon = showIcon,
                                    colorProvider = { chipProvider.getColor(it) },
                                )
                            }
                    }
                }
            }
        }
    }

    private fun onCompleteBoxClick() {
        val newState = completeBox.isChecked
        if (newState != task.isCompleted) {
            callback.onCompletedTask(task, newState)
        }

        // set check box to actual action item state
        setupTitleAndCheckbox()
    }

    private fun changeDueDate() {
        callback.onChangeDueDate(task)
    }

    interface ViewHolderCallbacks {
        fun onCompletedTask(task: TaskContainer, newState: Boolean)
        fun onLinkClicked(vh: TaskViewHolder, url: String): Boolean
        fun onTitleClick(
            taskViewHolder: TaskViewHolder,
            title: String?,
            cursorOffset: Int?,
            cursorX: Float?,
            cursorY: Float?,
        )
        fun onTitleCursorPositioned(taskId: Long, cursorRequestId: Long)
        fun onTitleChanged(taskId: Long, title: String)
        fun onTitleCommit(taskViewHolder: TaskViewHolder)
        fun onOpenDetails(taskViewHolder: TaskViewHolder)
        fun onOutsideClick(taskViewHolder: TaskViewHolder)
        fun onClick(filter: Filter)
        fun toggleSubtasks(task: Long, collapsed: Boolean)
        fun onLongPress(taskViewHolder: TaskViewHolder): Boolean
        fun onChangeDueDate(task: TaskContainer)
    }

    init {
        val fullTaskTitle = preferences.getBoolean(R.string.p_fullTaskTitle, false)
        if (fullTaskTitle) {
            nameView.maxLines = Int.MAX_VALUE
            nameView.isSingleLine = false
            nameView.ellipsize = null
        }
        titleEditor.isSingleLine = !fullTaskTitle
        titleEditor.maxLines = nameView.maxLines
        titleEditor.setHorizontallyScrolling(!fullTaskTitle)
        titleEditor.imeOptions = EditorInfo.IME_ACTION_DONE
        titleEditorInputType = titleEditor.inputType
        if (preferences.getBoolean(R.string.p_show_full_description, false)) {
            description.maxLines = Int.MAX_VALUE
            description.isSingleLine = false
            description.ellipsize = null
        }
        titleEditor.maxLines = nameView.maxLines
        titleEditor.typeface = nameView.typeface
        titleEditor.includeFontPadding = nameView.includeFontPadding
        titleEditor.letterSpacing = nameView.letterSpacing
        titleEditor.setLineSpacing(nameView.lineSpacingExtra, nameView.lineSpacingMultiplier)
        setTopPadding(rowPaddingPx, nameView, titleEditor, completeBox, dueDate)
        setBottomPadding(rowPaddingPx, completeBox, dueDate)
        nameView.textSize = fontSize.toFloat()
        titleEditor.textSize = fontSize.toFloat()
        description.textSize = fontSize.toFloat()
        val fontSizeDetails = max(10, fontSize - 2)
        dueDate.textSize = fontSizeDetails.toFloat()
        with(binding.root) {
            tag = this@TaskViewHolder
            for (i in 0 until childCount) {
                getChildAt(i).tag = this@TaskViewHolder
            }
        }
    }
}