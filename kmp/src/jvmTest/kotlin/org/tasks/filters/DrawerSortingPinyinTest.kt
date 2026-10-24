package org.tasks.filters

import org.tasks.data.NO_COUNT
import org.tasks.data.NO_ORDER
import org.tasks.data.entity.TagData
import kotlin.test.Test
import kotlin.test.assertEquals

class DrawerSortingPinyinTest {

    @Test
    fun chineseNamesSortByPinyin() {
        val sorted = listOf(tag("香蕉"), tag("苹果"), tag("梨"), tag("北京"))
            .sortForDrawer(DrawerSortMode.NAME)
            .map(Filter::title)

        assertEquals(listOf("北京", "梨", "苹果", "香蕉"), sorted)
    }

    @Test
    fun numericSuffixesStillSortNumerically() {
        val sorted = listOf(tag("清单10"), tag("清单2"), tag("清单1"))
            .sortForDrawer(DrawerSortMode.NAME)
            .map(Filter::title)

        assertEquals(listOf("清单1", "清单2", "清单10"), sorted)
    }

    private fun tag(name: String) =
        TagFilter(TagData(name = name, order = NO_ORDER), count = NO_COUNT)
}
