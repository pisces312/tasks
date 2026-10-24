package org.tasks.filters

import java.text.Collator
import java.util.Locale

actual fun localizedStringComparator(): Comparator<String> {
    val defaultCollator = Collator.getInstance(Locale.getDefault()).apply {
        strength = Collator.PRIMARY
    }
    val chineseCollator = Collator.getInstance(Locale.CHINA).apply {
        strength = Collator.PRIMARY
    }
    return Comparator { a, b ->
        // Root collation orders Han characters by radical/stroke, not pinyin, so Chinese names
        // always use the Chinese rules regardless of the system language.
        val collator = if (a.hasHan() || b.hasHan()) chineseCollator else defaultCollator
        collator.compare(a, b)
    }
}

private fun String.hasHan(): Boolean = any { char ->
    char.code in 0x3400..0x4DBF || char.code in 0x4E00..0x9FFF || char.code in 0xF900..0xFAFF
}
