package com.termux.app.workspace

import android.app.Activity

/** What the Settings screen needs from the workspace (language and updates); the workspace internals stay package-private.*/
object WorkspaceSettings {

    /** Call before showing a workspace dialog from another screen: loads the language resources and the palette.*/
    @JvmStatic
    fun prepare(a: Activity) {
        WorkspaceLanguage.apply(a)
        Pal.init(a)
    }

    /** "Русский", "English", or an empty string when the system language is followed.*/
    @JvmStatic
    fun language(a: Activity): String {
        val c: String? = WorkspaceLanguage.choice(a)
        return if (c!!.equals("ru")) "Русский" else if (c.equals("en")) "English" else ""
    }

    @JvmStatic
    fun pickLanguage(a: Activity, changed: Runnable) {
        WorkspaceLanguage.showPicker(a, changed)
    }

    @JvmStatic
    fun checkUpdates(a: Activity) {
        WorkspaceUpdates.check(a)
    }

    @JvmStatic
    fun autoUpdates(a: Activity): Boolean {
        return WorkspaceUpdates.autoEnabled(a)
    }

    @JvmStatic
    fun autoUpdates(a: Activity, on: Boolean) {
        WorkspaceUpdates.autoEnabled(a, on)
    }

    @JvmStatic
    fun updateAvailable(a: Activity): Boolean {
        return WorkspaceUpdates.available(a)
    }

    @JvmStatic
    fun updateName(a: Activity): String? {
        return WorkspaceUpdates.availableName(a)
    }
}
