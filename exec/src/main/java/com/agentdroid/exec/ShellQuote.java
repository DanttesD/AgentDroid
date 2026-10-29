package com.agentdroid.exec;

/** POSIX shell quoting. */
public final class ShellQuote {
    private ShellQuote() {}

    /** Wraps a value in single quotes so a POSIX shell treats it literally. */
    public static String quote(String value) {
        if (value == null) throw new NullPointerException("value");
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /** True for names that are safe to use as an environment variable name. */
    public static boolean isValidEnvName(String name) {
        return name != null && name.matches("[A-Za-z_][A-Za-z0-9_]*");
    }
}
