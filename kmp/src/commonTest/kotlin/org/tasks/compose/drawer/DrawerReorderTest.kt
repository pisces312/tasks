package org.tasks.compose.drawer

import org.tasks.data.entity.CaldavAccount
import org.tasks.data.entity.CaldavCalendar
import org.tasks.data.entity.TagData
import org.tasks.filters.CaldavFilter
import org.tasks.filters.EmptyFilter
import org.tasks.filters.Filter
import org.tasks.filters.NavigationDrawerSubheader
import org.tasks.filters.TagFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DrawerReorderTest {

    @Test
    fun listsShareAGroupWithinAnAccountAndNotAcrossAccounts() {
        val first = listItem("account-a", "One")
        val second = listItem("account-a", "Two")
        val other = listItem("account-b", "Three")

        assertNotNull(first.reorderGroup())
        assertEquals(first.reorderGroup(), second.reorderGroup())
        assertNotEquals(first.reorderGroup(), other.reorderGroup())
    }

    @Test
    fun headersAndBuiltInFiltersHaveNoGroup() {
        assertNull(header("a").reorderGroup())
        assertNull(DrawerItem.SignIn.reorderGroup())
        assertNull(filterItem(EmptyFilter()).reorderGroup())
    }

    @Test
    fun dragSwapsNeighboursAndReturnsTheNewGroupOrder() {
        val header = header("tags")
        val one = tagItem("one")
        val two = tagItem("two")
        val three = tagItem("three")
        val state = DrawerReorderState()

        assertTrue(state.start(listOf(header, one, two, three), one.key(), STEP))
        state.drag(STEP * 0.6f)

        val ordered = assertNotNull(state.finish())
        assertEquals(listOf("two", "one", "three"), ordered.map(Filter::title))
    }

    @Test
    fun dragUpSwapsWithTheRowAbove() {
        val one = tagItem("one")
        val two = tagItem("two")
        val state = DrawerReorderState()

        assertTrue(state.start(listOf(header("tags"), one, two), two.key(), STEP))
        state.drag(-STEP * 0.6f)

        assertEquals(listOf("two", "one"), assertNotNull(state.finish()).map(Filter::title))
    }

    @Test
    fun dragStopsWhereTheGroupEnds() {
        val one = tagItem("one")
        val two = tagItem("two")
        val accountHeader = header("account")
        val list = listItem("account-a", "List")
        val state = DrawerReorderState()

        assertTrue(state.start(listOf(header("tags"), one, two, accountHeader, list), one.key(), STEP))
        state.drag(STEP * 2f)
        val ordered = assertNotNull(state.finish())

        assertEquals(listOf("two", "one"), ordered.map(Filter::title))
    }

    @Test
    fun draggingAgainstTheBoundaryDoesNotMoveAnything() {
        val one = tagItem("one")
        val accountHeader = header("account")
        val list = listItem("account-a", "List")
        val state = DrawerReorderState()

        assertTrue(state.start(listOf(header("tags"), one, accountHeader, list), one.key(), STEP))
        state.drag(STEP * 2f)

        assertNull(state.finish())
    }

    @Test
    fun droppingWithoutCrossingHalfARowIsNotAReorder() {
        val one = tagItem("one")
        val two = tagItem("two")
        val state = DrawerReorderState()

        assertTrue(state.start(listOf(header("tags"), one, two), one.key(), STEP))
        state.drag(STEP * 0.4f)

        assertNull(state.finish())
    }

    @Test
    fun onlyGroupChildrenCanStartADrag() {
        val state = DrawerReorderState()
        val sectionHeader = header("tags")

        assertFalse(state.start(listOf(sectionHeader, tagItem("one")), sectionHeader.key(), STEP))
    }

    private fun header(id: String) = DrawerItem.Header(
        title = id,
        collapsed = false,
        header = NavigationDrawerSubheader(
            title = id,
            error = false,
            isCollapsed = false,
            subheaderType = NavigationDrawerSubheader.SubheaderType.CALDAV,
            id = id,
        ),
    )

    private fun tagItem(name: String) = filterItem(TagFilter(TagData(name = name)))

    private fun listItem(account: String, name: String) = filterItem(
        CaldavFilter(
            calendar = CaldavCalendar(uuid = "$account-$name", name = name),
            account = CaldavAccount(uuid = account),
        ),
    )

    private fun filterItem(filter: Filter) = DrawerItem.Filter(
        title = filter.title,
        icon = null,
        filter = filter,
    )

    private companion object {
        const val STEP = 100f
    }
}
