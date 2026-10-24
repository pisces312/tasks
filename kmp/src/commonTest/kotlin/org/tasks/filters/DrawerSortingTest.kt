package org.tasks.filters

import org.tasks.data.NO_ORDER
import org.tasks.data.entity.TagData
import kotlin.test.Test
import kotlin.test.assertEquals

class DrawerSortingTest {

    @Test
    fun manualSortWithoutStoredOrderFallsBackToNaturalNameOrder() {
        val sorted = listOf(tag("List 10"), tag("List 2"), tag("List 1"))
            .sortForDrawer(DrawerSortMode.MANUAL)
            .map(Filter::title)

        assertEquals(listOf("List 1", "List 2", "List 10"), sorted)
    }

    @Test
    fun manualSortHonorsStoredOrder() {
        val sorted = listOf(
            tag("Charlie"),
            tag("Bravo", order = 1),
            tag("Alpha", order = 0),
        )
            .sortForDrawer(DrawerSortMode.MANUAL)
            .map(Filter::title)

        assertEquals(listOf("Alpha", "Bravo", "Charlie"), sorted)
    }

    @Test
    fun nameSortIgnoresStoredOrder() {
        val sorted = listOf(
            tag("Bravo", order = 0),
            tag("Alpha", order = 1),
        )
            .sortForDrawer(DrawerSortMode.NAME)
            .map(Filter::title)

        assertEquals(listOf("Alpha", "Bravo"), sorted)
    }

    private fun tag(name: String, order: Int = NO_ORDER) =
        TagFilter(TagData(name = name, order = order))
}
