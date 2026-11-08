package com.agentdroid.agent

/**
 * Java's `String.trim()`: removes every character up to U+0020. Kotlin's `trim()` also removes U+00A0 and other
 * Unicode spaces, which would silently change the behaviour of the ported code.
 */
internal fun String.jtrim(): String = trim { it <= ' ' }

/** A boolean tool argument as models send it: `true` or the text "true". */
internal fun flag(a: Map<String, Any?>, k: String): Boolean = a[k] == true || a[k].toString() == "true"

/**
 * Java's `String.split(literal)`: trailing empty strings are dropped (Kotlin's `split` keeps them), but a string
 * that does not contain the delimiter comes back as a single element even when it is empty.
 */
internal fun String.jsplit(delimiter: Char): List<String> {
    val parts = split(delimiter)
    if (parts.size == 1) return parts
    var end = parts.size
    while (end > 0 && parts[end - 1].isEmpty()) end--
    return parts.subList(0, end)
}
