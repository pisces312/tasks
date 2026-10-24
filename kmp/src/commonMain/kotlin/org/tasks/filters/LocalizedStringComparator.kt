package org.tasks.filters

/**
 * Locale-aware string comparator for list names. Unlike [String.compareTo], which orders Chinese
 * names by code point, this follows the platform collation rules so that names sort by pinyin.
 *
 * A new comparator is returned on every call because the underlying collators are not thread safe.
 */
expect fun localizedStringComparator(): Comparator<String>
