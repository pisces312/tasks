package org.tasks.filters

import org.tasks.data.NO_ORDER

internal fun List<Filter>.sortForDrawer(mode: DrawerSortMode): List<Filter> = when (mode) {
    DrawerSortMode.MANUAL ->
        if (all { it.order == NO_ORDER }) {
            sortedWith(AlphanumComparator.FILTER)
        } else {
            sortedWith(MANUAL_ORDER)
        }

    DrawerSortMode.NAME -> sortedWith(localizedAlphanumComparator())
}

private val MANUAL_ORDER = Comparator<Filter> { f1, f2 ->
    when {
        f1.order == NO_ORDER && f2.order == NO_ORDER ->
            AlphanumComparator.FILTER.compare(f1, f2)

        f1.order == NO_ORDER -> 1
        f2.order == NO_ORDER -> -1
        f1.order < f2.order -> -1
        f1.order > f2.order -> 1
        else -> AlphanumComparator.FILTER.compare(f1, f2)
    }
}

private fun localizedAlphanumComparator(): Comparator<Filter> =
    AlphanumComparator(Filter::title, localizedStringComparator())
