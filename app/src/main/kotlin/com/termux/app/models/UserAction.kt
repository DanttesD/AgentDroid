package com.termux.app.models

enum class UserAction {

    ABOUT("about"),
    CRASH_REPORT("crash report"),
    PLUGIN_EXECUTION_COMMAND("plugin execution command"),
    REPORT_ISSUE_FROM_TRANSCRIPT("report issue from transcript");

    private val name_: String

    constructor(name: String) {
        this.name_ = name
    }

    fun getName(): String {
        return name_
    }
}
