package org.tasks.filters

import platform.Foundation.NSComparisonResult
import platform.Foundation.NSString

actual fun localizedStringComparator(): Comparator<String> = Comparator { a, b ->
    when ((a as NSString).localizedStandardCompare(b as NSString)) {
        NSComparisonResult.NSOrderedAscending -> -1
        NSComparisonResult.NSOrderedDescending -> 1
        else -> 0
    }
}
