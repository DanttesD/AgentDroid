package com.termux.app.workspace

import android.view.View
import com.termux.app.TermuxActivity
import com.termux.x11.DesktopPrograms
import java.util.function.BooleanSupplier
import java.util.function.Supplier

/** One UI channel/server per environment, one visible surface in the Activity.*/
class WorkspaceDesktops {
    private val channels: MutableMap<String, WorkspaceDesktop?> = LinkedHashMap<String, WorkspaceDesktop?>()
    private val activity: TermuxActivity
    private val selected: Supplier<WorkspaceStore.Session?>
    private val visible: BooleanSupplier
    private val input: View?
    private val changed: Runnable
    private val restore: Runnable
    private var keyboardChanged: Runnable = Runnable {  }
    private var closed: Boolean = false

    constructor(a: TermuxActivity, s: Supplier<WorkspaceStore.Session?>, v: BooleanSupplier, i: View?, c: Runnable, r: Runnable) {
        activity = a
        selected = s
        visible = v
        input = i
        changed = c
        restore = r
    }

    private fun current(create: Boolean): WorkspaceDesktop? {
        val p: WorkspaceStore.Session? = selected.get()
        val target: String = WorkspaceDesktop.target(p)
        if (closed || !DesktopPrograms.supportsTarget(target)) {
            return null
        }
        var existing: WorkspaceDesktop? = channels.get(target)
        if (existing == null && create) {
            existing = WorkspaceDesktop(activity, Supplier<WorkspaceStore.Session?> {
                val now: WorkspaceStore.Session? = selected.get()
                return@Supplier if (target.equals(WorkspaceDesktop.target(now))) now else null
            }, BooleanSupplier { visible.getAsBoolean() && target.equals(WorkspaceDesktop.target(selected.get())) }, input, changed, restore)
            existing.onKeyboardChanged(Runnable {
                if (target.equals(WorkspaceDesktop.target(selected.get()))) {
                    keyboardChanged.run()
                }
            })
            channels.put(target, existing)
        }
        return existing
    }

    fun diagnostics(p: WorkspaceStore.Session?): String {
        if (!diagnosticsEligible(p)) {
            return ""
        }
        val target: String = WorkspaceDesktop.target(p)
        val ru: Boolean = WorkspaceLanguage.code(activity)!!.equals("ru")
        if (!DesktopPrograms.supportsTarget(target)) {
            return if (ru) "X11 · Рабочий стол пока не поддерживается" else "X11 · Desktop is not yet supported"
        }
        val c: WorkspaceDesktop? = channels.get(target)
        return if (c == null) if (ru) "X11 · Нет сведений о запуске в этом процессе приложения" else "X11 · No startup snapshot in this app process" else c.sessionDiagnostic(p)
    }

    fun prepareDiagnostics(p: WorkspaceStore.Session?, done: Runnable) {
        val c: WorkspaceDesktop? = if (diagnosticsEligible(p)) channels.get(WorkspaceDesktop.target(p)) else null
        if (c == null) {
            done.run()
        } else {
            c.refreshDiagnostic(done)
        }
    }

    fun onKeyboardChanged(r: Runnable) {
        keyboardChanged = r
    }

    fun hasSurface(): Boolean {
        val c: WorkspaceDesktop? = current(false)
        return c != null && c.hasSurface()
    }

    fun showing(): Boolean {
        val c: WorkspaceDesktop? = current(false)
        return c != null && c.showing()
    }

    fun keyboardEnabled(): Boolean {
        val c: WorkspaceDesktop? = current(false)
        return c != null && c.keyboardEnabled()
    }

    fun extraKeysVisible(): Boolean {
        val c: WorkspaceDesktop? = current(false)
        return c != null && c.extraKeysVisible()
    }

    fun extraKeys() {
        val c: WorkspaceDesktop? = current(false)
        if (c != null) {
            c.extraKeys()
        }
    }

    fun keyboard() {
        val c: WorkspaceDesktop? = current(false)
        if (c != null) {
            c.keyboard()
        }
    }

    fun fullscreenBack(): Boolean {
        val c: WorkspaceDesktop? = current(false)
        return c != null && c.fullscreenBack()
    }

    fun label(): String {
        val c: WorkspaceDesktop? = current(true)
        return if (c == null) if (WorkspaceLanguage.code(activity)!!.equals("ru")) "Терминал" else "Terminal" else c.label()
    }

    fun openPrograms() {
        val c: WorkspaceDesktop? = current(true)
        if (c != null) {
            c.openPrograms()
        } else {
            activity.showToast(if (WorkspaceLanguage.code(activity)!!.equals("ru")) "X11 пока не поддерживается этим окружением." else "X11 is not yet supported in this environment.", true)
        }
    }

    fun programsInstalling(): Boolean {
        val c: WorkspaceDesktop? = current(false)
        return c != null && c.programsInstalling() && !c.programsVisible()
    }

    fun menu() {
        val c: WorkspaceDesktop? = current(true)
        if (c != null) {
            c.menu()
        } else {
            activity.showToast(if (WorkspaceLanguage.code(activity)!!.equals("ru")) "X11 пока не поддерживается этим окружением." else "X11 is not yet supported in this environment.", true)
        }
    }

    fun owns(p: WorkspaceStore.Session?): Boolean {
        val c: WorkspaceDesktop? = channels.get(WorkspaceDesktop.target(p))
        return c != null && c.owns(p)
    }

    fun sessionClosed(p: WorkspaceStore.Session?) {
        val c: WorkspaceDesktop? = channels.get(WorkspaceDesktop.target(p))
        if (c != null) {
            c.sessionClosed(p)
        }
    }

    fun sync() {
        if (closed) {
            return
        }
        val active: WorkspaceDesktop? = current(false)
        for (c in ArrayList<WorkspaceDesktop?>(channels.values)) {
            if (c !== active) {
                c!!.sync()
            }
        }
        if (active != null) {
            active.sync()
        }
    }

    fun close() {
        if (closed) {
            return
        }
        closed = true
        for (c in channels.values) {
            c!!.close()
        }
        channels.clear()
    }

    companion object {
        @JvmStatic
        fun diagnosticsEligible(p: WorkspaceStore.Session?): Boolean {
            return WorkspaceDesktop.eligible(p) && !WorkspaceDesktop.target(p).equals("ubuntu-ai")
        }
    }
}
