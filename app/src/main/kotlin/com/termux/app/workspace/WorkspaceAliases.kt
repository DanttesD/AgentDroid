package com.termux.app.workspace

import android.app.Activity
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Arrays
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/** Alias definitions are written as data to one explicit installed environment.*/
object WorkspaceAliases {
    fun interface Installer {
        @Throws(Exception::class)
        fun apply(alias: WorkspaceStore.Alias?, delete: Boolean)
    }

    private fun interface Undo {
        @Throws(Exception::class)
        fun run()
    }

    @JvmField val BUSY: AtomicBoolean = AtomicBoolean()
    @JvmField val ENVIRONMENTS: MutableList<String> = Collections.unmodifiableList(Arrays.asList("termux", "ubuntu", "debian", "alpine", "archlinux", "fedora", "opensuse", "void"))

    @JvmStatic
    fun validName(name: String?): Boolean {
        return name != null && (name as java.lang.String).matches("[A-Za-z_][A-Za-z0-9_-]{0,63}") && !Arrays.asList("if", "then", "else", "elif", "fi", "do", "done", "for", "while", "until", "case", "esac", "in", "function", "select", "time", "coproc").contains(name)
    }

    @JvmStatic
    fun valid(a: WorkspaceStore.Alias?): Boolean {
        if (a == null || !validName(a.name) || !ENVIRONMENTS.contains(a.environment) || a.command == null || (a.command!! as java.lang.String).trim().isEmpty() || a.command!!.length > 16384 || a.command!!.indexOf('\u0000') >= 0 || a.command!!.indexOf('\r') >= 0) {
            return false
        }
        return a.id != null && (a.id!! as java.lang.String).matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
    }

    @JvmStatic
    fun profile(environment: String): WorkspaceStore.Session {
        if (!ENVIRONMENTS.contains(environment)) {
            throw IllegalArgumentException("ALIAS_INVALID_ENVIRONMENT")
        }
        val s = WorkspaceStore.Session()
        s.environment = "termux"
        if (!environment.equals("termux")) {
            s.prootAlias = environment
        }
        return s
    }

    @JvmStatic
    fun installed(all: MutableList<WorkspaceEnvironments.Choice>): MutableList<WorkspaceEnvironments.Choice> {
        val out: MutableList<WorkspaceEnvironments.Choice> = ArrayList<WorkspaceEnvironments.Choice>()
        for (c in all) {
            if (c.installed && ENVIRONMENTS.contains(if (c.alias.isEmpty()) "termux" else c.alias)) {
                out.add(c)
            }
        }
        return out
    }

    @JvmStatic
    fun hook(): String {
        return "if [ ! -L \"\$HOME/.termux-jobs-aliases\" ] && [ -f \"\$HOME/.termux-jobs-aliases/_loader.sh\" ] && [ ! -L \"\$HOME/.termux-jobs-aliases/_loader.sh\" ]; then\nfor _termux_jobs_alias in \"\$HOME\"/.termux-jobs-aliases/*.sh; do\nif [ -f \"\$_termux_jobs_alias\" ] && [ ! -L \"\$_termux_jobs_alias\" ]; then . \"\$_termux_jobs_alias\"; fi\ndone\n\\unset _termux_jobs_alias\nfi\n"
    }

    @JvmStatic
    fun script(helper: String, a: WorkspaceStore.Alias?, delete: Boolean, replace: String?): String {
        if (!valid(a) || replace == null || !replace.isEmpty() && !(replace as java.lang.String).matches("[a-f0-9-]{36}")) {
            throw IllegalArgumentException("ALIAS_INVALID_DEFINITION")
        }
        return "set -- " + SessionShell.quote(if (delete) "delete" else "save") + " " + SessionShell.quote(a!!.id) + " " + SessionShell.quote(a.name) + " " + SessionShell.quote(a.command) + " " + SessionShell.quote(replace) + "\n" + helper
    }

    @Throws(Exception::class)
    @JvmStatic
    fun apply(activity: Activity, a: WorkspaceStore.Alias?, delete: Boolean, replace: String) {
        apply(activity, a, delete, replace, WorkspaceEnvironments.choices())
    }

    @Throws(Exception::class)
    @JvmStatic
    fun apply(activity: Activity, a: WorkspaceStore.Alias?, delete: Boolean, replace: String, choices: MutableList<WorkspaceEnvironments.Choice>) {
        if (!valid(a)) {
            throw IOException("ALIAS_INVALID_DEFINITION")
        }
        // Recheck at execution time: a removed rootfs must not silently fall back to Termux.
        var found = false
        for (c in installed(choices)) {
            if ((if (c.alias.isEmpty()) "termux" else c.alias).equals(a!!.environment)) {
                found = true
            }
        }
        if (!found) {
            if (delete) {
                return
            }
            throw IOException("ALIAS_ENVIRONMENT_NOT_INSTALLED")
        }
        var helper: String
        val `in` = activity.getAssets().open("workspace-aliases.sh")
        var primaryException: Throwable? = null
        try {
            val out = ByteArrayOutputStream()
            var primaryException1: Throwable? = null
            try {
                val buf = ByteArray(4096)
                var n: Int = 0
                while (true) {
                    n = `in`.read(buf)
                    if (n == -1) break
                    out.write(buf, 0, n)
                }
                helper = String(out.toByteArray(), StandardCharsets.UTF_8)
            } catch (t: Throwable) {
                primaryException1 = t
                throw t
            } finally {
                if (primaryException1 != null) {
                    try {
                        out.close()
                    } catch (suppressed: Throwable) {
                        primaryException1.addSuppressed(suppressed)
                    }
                } else {
                    out.close()
                }
            }
        } catch (t: Throwable) {
            primaryException = t
            throw t
        } finally {
            if (primaryException != null) {
                try {
                    `in`.close()
                } catch (suppressed: Throwable) {
                    primaryException.addSuppressed(suppressed)
                }
            } else {
                `in`.close()
            }
        }
        (ProjectFiles(activity, profile(a!!.environment))).use { files ->
            val answer: String = files.aliasOperation(script(helper, a, delete, replace))
            if (!(answer as java.lang.String).trim().equals("ALIAS_OK")) {
                throw IOException("ALIAS_SAVE_FAILED")
            }
        }
    }

    @Throws(Exception::class)
    @JvmStatic
    fun change(before: WorkspaceStore.Alias?, after: WorkspaceStore.Alias?, collision: WorkspaceStore.Alias?, installer: Installer) {
        val undo: MutableList<Undo> = ArrayList<Undo>()
        try {
            if (after == null) {
                installer.apply(before, true)
                return
            }
            installer.apply(after, false)
            if (before != null && before.environment.equals(after.environment)) {
                undo.add(Undo { installer.apply(before, false) })
            } else {
                undo.add(Undo { installer.apply(after, true) })
            }
            if (collision != null) {
                installer.apply(collision, true)
                undo.add(Undo { installer.apply(collision, false) })
            }
            if (before != null && !before.environment.equals(after.environment)) {
                installer.apply(before, true)
                undo.add(Undo { installer.apply(before, false) })
            }
        } catch (failure: Exception) {
            var failed = false
            for (i in undo.size - 1 downTo 0) {
                try {
                    undo.get(i).run()
                } catch (rollback: Exception) {
                    failed = true
                }
            }
            if (failed) {
                throw IOException("ALIAS_ROLLBACK_FAILED: " + failure.message, failure)
            }
            throw failure
        }
    }

    @JvmStatic
    fun label(environment: String): String {
        return if (environment.equals("termux")) "Termux" else WorkspaceEnvironments.label(environment)
    }
}
