package com.termux.app.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.agentdroid.agent.ScreenBridge
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Lets the agent read the screen as a tree of elements and act on them by number, without root.*/
open class AgentAccessibilityService : AccessibilityService(), ScreenBridge {
    private val nodes: MutableList<AccessibilityNodeInfo> = ArrayList<AccessibilityNodeInfo>()

    protected override fun onServiceConnected() {
        super.onServiceConnected()
        ScreenBridge.Holder.current = this
    }

    override fun onUnbind(intent: Intent): Boolean {
        if (ScreenBridge.Holder.current === this) {
            ScreenBridge.Holder.current = null
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (ScreenBridge.Holder.current === this) {
            ScreenBridge.Holder.current = null
        }
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
    }

    override fun onInterrupt() {
    }

    // ---------- ScreenBridge ----------
    @Synchronized
    override fun read(all: Boolean): String {
        nodes.clear()
        val root: AccessibilityNodeInfo? = getRootInActiveWindow()
        if (root == null) {
            return "(no active window; the app may block reading — use screenshot)"
        }
        val sb = StringBuilder()
        sb.append("package: ").append(root.getPackageName()).append('\n')
        walk(root, sb, all, 0)
        if (nodes.isEmpty()) {
            sb.append("(no elements; use screenshot)\n")
        }
        return sb.toString()
    }

    private fun walk(n: AccessibilityNodeInfo?, sb: StringBuilder, all: Boolean, depth: Int) {
        if (n == null || depth > 60) {
            return
        }
        if (n.isVisibleToUser()) {
            val text: CharSequence? = if (n.isPassword()) if (n.getText() == null) "" else "••••" else n.getText()
            val desc: CharSequence? = n.getContentDescription()
            val has: Boolean = text != null && text.length > 0 || desc != null && desc.length > 0
            val act: Boolean = n.isClickable() || n.isLongClickable() || n.isScrollable() || n.isEditable() || n.isCheckable()
            if (all || has || act) {
                val r = Rect()
                n.getBoundsInScreen(r)
                if (r.width() > 0 && r.height() > 0) {
                    nodes.add(n)
                    sb.append('[').append(nodes.size - 1).append("] ")
                    val cls: CharSequence? = n.getClassName()
                    if (cls != null) {
                        val c: String = cls.toString()
                        sb.append(c.substring(c.lastIndexOf('.') + 1)).append(' ')
                    }
                    if (text != null && text.length > 0) {
                        sb.append('"').append((text.toString() as java.lang.String).replace("\n", " ")).append("\" ")
                    }
                    if (desc != null && desc.length > 0) {
                        sb.append("desc=\"").append((desc.toString() as java.lang.String).replace("\n", " ")).append("\" ")
                    }
                    val id: String? = n.getViewIdResourceName()
                    if (id != null) {
                        sb.append("id=").append(id.substring(id.indexOf('/') + 1)).append(' ')
                    }
                    if (n.isClickable()) {
                        sb.append("clickable ")
                    }
                    if (n.isLongClickable()) {
                        sb.append("long-clickable ")
                    }
                    if (n.isScrollable()) {
                        sb.append("scrollable ")
                    }
                    if (n.isEditable()) {
                        sb.append("editable ")
                    }
                    if (n.isCheckable()) {
                        sb.append(if (n.isChecked()) "checked " else "unchecked ")
                    }
                    if (n.isSelected()) {
                        sb.append("selected ")
                    }
                    if (n.isFocused()) {
                        sb.append("focused ")
                    }
                    if (!n.isEnabled()) {
                        sb.append("disabled ")
                    }
                    sb.append("@(").append(r.centerX()).append(',').append(r.centerY()).append(")\n")
                }
            }
        }
        var i = 0
        while (i < n.getChildCount()) {
            walk(n.getChild(i), sb, all, depth + 1)
            i++
        }
    }

    @Synchronized
    private fun node(id: Int): AccessibilityNodeInfo {
        if (id < 0 || id >= nodes.size) {
            throw IllegalArgumentException("no element " + id + "; call ui_read again")
        }
        val n: AccessibilityNodeInfo = nodes.get(id)
        n.refresh()
        return n
    }

    override fun click(id: Int): String {
        return act(id, false)
    }

    override fun longClick(id: Int): String {
        return act(id, true)
    }

    private fun act(id: Int, longPress: Boolean): String {
        val n: AccessibilityNodeInfo = node(id)
        var t: AccessibilityNodeInfo? = n
        var guard = 0
        while (t != null && guard++ < 8) {
            if (if (longPress) t.isLongClickable() else t.isClickable()) {
                if (t.performAction(if (longPress) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK)) {
                    return (if (longPress) "long-clicked " else "clicked ") + id
                }
            }
            t = t.getParent()
        }
        val r = Rect()
        n.getBoundsInScreen(r)
        return if (tap(r.centerX(), r.centerY(), (if (longPress) 700 else 60).toLong())) (if (longPress) "long-pressed " else "tapped ") + id + " by gesture at (" + r.centerX() + "," + r.centerY() + ")" else "could not click element " + id
    }

    private fun tap(x: Int, y: Int, duration: Long): Boolean {
        if (Build.VERSION.SDK_INT < 24) {
            return false
        }
        val p = Path()
        p.moveTo(x.toFloat(), y.toFloat())
        val g: GestureDescription = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0L, duration)).build()
        val done = CountDownLatch(1)
        val ok: BooleanArray = booleanArrayOf(false)
        val sent: Boolean = dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(d: GestureDescription) {
                ok[0] = true
                done.countDown()
            }

            override fun onCancelled(d: GestureDescription) {
                done.countDown()
            }
        }, null)
        if (!sent) {
            return false
        }
        try {
            done.await(3L, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return ok[0]
    }

    override fun setText(id: Int, text: String): String {
        val n: AccessibilityNodeInfo = node(id)
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return if (n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) "text set in " + id else "element " + id + " does not accept text (is it editable?)"
    }

    override fun scroll(id: Int, direction: String): String {
        val n: AccessibilityNodeInfo = node(id)
        val d: String = if (direction == null) "" else (direction as java.lang.String).toLowerCase()
        var action: Int
        when (d) {
            "down" -> {
                action = if (Build.VERSION.SDK_INT >= 23) AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.getId() else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            }
            "up" -> {
                action = if (Build.VERSION.SDK_INT >= 23) AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.getId() else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
            "right" -> {
                action = if (Build.VERSION.SDK_INT >= 23) AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.getId() else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            }
            "left" -> {
                action = if (Build.VERSION.SDK_INT >= 23) AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.getId() else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
            else -> {
                throw IllegalArgumentException("direction must be up, down, left or right")
            }
        }
        var ok: Boolean = n.performAction(action)
        if (!ok) {
            ok = n.performAction(if (d.equals("down") || d.equals("right")) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        }
        return if (ok) "scrolled " + d else "cannot scroll " + d + " (end of list?)"
    }

    override fun global(action: String): String {
        var a: Int
        when (if (action == null) "" else (action as java.lang.String).toLowerCase()) {
            "back" -> {
                a = AccessibilityService.GLOBAL_ACTION_BACK
            }
            "home" -> {
                a = AccessibilityService.GLOBAL_ACTION_HOME
            }
            "recents" -> {
                a = AccessibilityService.GLOBAL_ACTION_RECENTS
            }
            "notifications" -> {
                a = AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            }
            "quick_settings" -> {
                a = AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            }
            "power_dialog" -> {
                a = AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
            }
            "lock_screen" -> {
                if (Build.VERSION.SDK_INT < 28) {
                    return "needs Android 9+"
                }
                a = AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            }
            "screenshot" -> {
                if (Build.VERSION.SDK_INT < 28) {
                    return "needs Android 9+"
                }
                a = AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
            }
            else -> {
                throw IllegalArgumentException("unknown action: " + action)
            }
        }
        return if (performGlobalAction(a)) "done: " + action else "system refused: " + action
    }

    override fun screenshot(): ByteArray? {
        if (Build.VERSION.SDK_INT < 30) {
            return null
        }
        val done = CountDownLatch(1)
        val out = AtomicReference<Bitmap>()
        takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(r: AccessibilityService.ScreenshotResult) {
                try {
                    val hw: Bitmap? = Bitmap.wrapHardwareBuffer(r.getHardwareBuffer(), r.getColorSpace())
                    if (hw != null) {
                        out.set(hw.copy(Bitmap.Config.ARGB_8888, false))
                    }
                    r.getHardwareBuffer().close()
                } catch (ignored: RuntimeException) {
                }
                done.countDown()
            }

            override fun onFailure(code: Int) {
                done.countDown()
            }
        })
        try {
            done.await(5L, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        val bm: Bitmap? = out.get()
        if (bm == null) {
            return null
        }
        val bo = ByteArrayOutputStream()
        bm.compress(Bitmap.CompressFormat.PNG, 100, bo)
        return bo.toByteArray()
    }

    override fun foregroundPackage(): String {
        val r: AccessibilityNodeInfo? = getRootInActiveWindow()
        return if (r == null || r.getPackageName() == null) "" else r.getPackageName().toString()
    }

    companion object {

        @JvmStatic
        fun isRunning(): Boolean {
            return ScreenBridge.Holder.current != null
        }
    }
}
