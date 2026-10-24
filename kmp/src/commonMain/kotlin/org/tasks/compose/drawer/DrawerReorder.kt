package org.tasks.compose.drawer

import org.tasks.filters.CaldavFilter
import org.tasks.filters.CustomFilter
import org.tasks.filters.Filter
import org.tasks.filters.PlaceFilter
import org.tasks.filters.TagFilter

/**
 * Identifies the run of drawer items that share one manual order. Lists belong to their account,
 * while tags, places and custom filters each form a single group. Built-in filters and section
 * headers have no group and cannot be dragged.
 */
internal fun DrawerItem.reorderGroup(): String? = when (val filter = (this as? DrawerItem.Filter)?.filter) {
    is CaldavFilter -> "caldav:${filter.account.uuid}"
    is TagFilter -> "tag"
    is PlaceFilter -> "place"
    is CustomFilter -> "filter"
    else -> null
}

/**
 * Drag bookkeeping for reordering drawer children in place. The list is mutated as the finger
 * crosses each row so the rows shift under the pointer; [finish] returns the new order of the
 * dragged group, which the caller persists.
 */
internal class DrawerReorderState {

    var items: List<DrawerItem>? = null
        private set

    private var key: String? = null
    private var group: String? = null
    private var stepPx = 0f
    private var offsetY = 0f
    private var moved = false

    fun start(items: List<DrawerItem>, key: String, stepPx: Float): Boolean {
        val group = items.firstOrNull { it.key() == key }?.reorderGroup() ?: return false
        this.items = items
        this.key = key
        this.group = group
        this.stepPx = stepPx.coerceAtLeast(1f)
        offsetY = 0f
        moved = false
        return true
    }

    /** Adds [deltaY] to the drag and swaps with neighbours each time half a row is crossed. */
    fun drag(deltaY: Float): Float {
        offsetY += deltaY
        while (offsetY > stepPx / 2f) {
            if (!swap(1)) break
            offsetY -= stepPx
            moved = true
        }
        while (offsetY < -stepPx / 2f) {
            if (!swap(-1)) break
            offsetY += stepPx
            moved = true
        }
        return offsetY
    }

    /** New order of the dragged group, or null when the item did not move. */
    fun finish(): List<Filter>? {
        val items = items
        val group = group
        return if (moved && items != null && group != null) {
            items
                .filter { it.reorderGroup() == group }
                .mapNotNull { (it as? DrawerItem.Filter)?.filter }
        } else {
            null
        }
    }

    fun reset() {
        items = null
        key = null
        group = null
        offsetY = 0f
        moved = false
    }

    private fun swap(direction: Int): Boolean {
        val items = items ?: return false
        val index = items.indexOfFirst { it.key() == key }
        if (index == -1) return false
        if (items.getOrNull(index + direction)?.reorderGroup() != group) return false
        val mutable = items.toMutableList()
        mutable.add(index + direction, mutable.removeAt(index))
        this.items = mutable
        return true
    }
}
