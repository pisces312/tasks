package org.tasks.filters

/**
 * Ordering applied to the children of each navigation drawer section. Section headers themselves
 * keep their existing order (filters, tags, places, then one group per account).
 */
enum class DrawerSortMode {
    /** Stored drag-and-drop order, falling back to name when no item has been reordered. */
    MANUAL,

    /** Locale-aware name order: Chinese names sort by pinyin rather than by code point. */
    NAME,
    ;

    companion object {
        fun fromValue(value: String?): DrawerSortMode =
            entries.firstOrNull { it.name == value } ?: MANUAL
    }
}
