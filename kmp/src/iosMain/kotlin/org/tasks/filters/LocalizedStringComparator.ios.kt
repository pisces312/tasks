package org.tasks.filters

import platform.Foundation.NSString
import platform.Foundation.localizedStandardCompare

@Suppress("CAST_NEVER_SUCCEEDS")
actual fun localizedStringComparator(): Comparator<String> = Comparator { a, b ->
    (a as NSString).localizedStandardCompare(b).toInt()
}
