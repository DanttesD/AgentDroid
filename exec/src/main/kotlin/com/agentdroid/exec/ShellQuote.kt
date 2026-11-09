package com.agentdroid.exec

/** POSIX shell quoting. */
object ShellQuote {
    private val ENV_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /** Wraps a value in single quotes so a POSIX shell treats it literally. */
    @JvmStatic
    fun quote(value: String?): String {
        // The Java original threw this explicitly; keep the same exception for Java callers.
        if (value == null) throw NullPointerException("value")
        return "'" + value.replace("'", "'\\''") + "'"
    }

    /** True for names that are safe to use as an environment variable name. */
    @JvmStatic
    fun isValidEnvName(name: String?): Boolean = name != null && ENV_NAME.matches(name)
}
