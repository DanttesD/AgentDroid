package com.termux.terminal

/** Java's String.trim(): removes characters up to U+0020 only (Kotlin's trim() removes all Unicode whitespace). */
internal fun String.jtrim(): String = trim { it <= ' ' }

/** Java's String.split(char): trailing empty strings are removed (Kotlin's split keeps them). */
internal fun String.jsplit(delimiter: Char): List<String> {
    val parts = split(delimiter)
    if (parts.size == 1) return parts
    var end = parts.size
    while (end > 0 && parts[end - 1].isEmpty()) end--
    return parts.subList(0, end)
}
