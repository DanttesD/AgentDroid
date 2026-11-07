package com.termux.app.workspace;

import android.app.Activity;

/** What the Settings screen needs from the workspace (language and updates); the workspace internals stay package-private. */
public final class WorkspaceSettings {
    private WorkspaceSettings() { }

    /** Call before showing a workspace dialog from another screen: loads the language resources and the palette. */
    public static void prepare(Activity a) {
        WorkspaceLanguage.apply(a);
        Pal.init(a);
    }

    /** "Русский", "English", or an empty string when the system language is followed. */
    public static String language(Activity a) {
        String c = WorkspaceLanguage.choice(a);
        return c.equals("ru") ? "Русский" : c.equals("en") ? "English" : "";
    }

    public static void pickLanguage(Activity a, Runnable changed) { WorkspaceLanguage.showPicker(a, changed); }

    public static void checkUpdates(Activity a) { WorkspaceUpdates.check(a); }

    public static boolean autoUpdates(Activity a) { return WorkspaceUpdates.autoEnabled(a); }

    public static void autoUpdates(Activity a, boolean on) { WorkspaceUpdates.autoEnabled(a, on); }

    public static boolean updateAvailable(Activity a) { return WorkspaceUpdates.available(a); }

    public static String updateName(Activity a) { return WorkspaceUpdates.availableName(a); }
}
