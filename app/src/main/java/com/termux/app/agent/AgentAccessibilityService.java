package com.termux.app.agent;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.agentdroid.agent.ScreenBridge;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Lets the agent read the screen as a tree of elements and act on them by number, without root. */
public class AgentAccessibilityService extends AccessibilityService implements ScreenBridge {
    private final List<AccessibilityNodeInfo> nodes = new ArrayList<>();

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        ScreenBridge.Holder.current = this;
    }

    @Override public boolean onUnbind(android.content.Intent intent) {
        if (ScreenBridge.Holder.current == this) ScreenBridge.Holder.current = null;
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        if (ScreenBridge.Holder.current == this) ScreenBridge.Holder.current = null;
        super.onDestroy();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override public void onInterrupt() { }

    public static boolean isRunning() { return ScreenBridge.Holder.current != null; }

    // ---------- ScreenBridge ----------

    @Override public synchronized String read(boolean all) {
        nodes.clear();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "(no active window; the app may block reading — use screenshot)";
        StringBuilder sb = new StringBuilder();
        sb.append("package: ").append(root.getPackageName()).append('\n');
        walk(root, sb, all, 0);
        if (nodes.isEmpty()) sb.append("(no elements; use screenshot)\n");
        return sb.toString();
    }

    private void walk(AccessibilityNodeInfo n, StringBuilder sb, boolean all, int depth) {
        if (n == null || depth > 60) return;
        if (n.isVisibleToUser()) {
            CharSequence text = n.isPassword() ? (n.getText() == null ? "" : "••••") : n.getText();
            CharSequence desc = n.getContentDescription();
            boolean has = (text != null && text.length() > 0) || (desc != null && desc.length() > 0);
            boolean act = n.isClickable() || n.isLongClickable() || n.isScrollable() || n.isEditable() || n.isCheckable();
            if (all || has || act) {
                Rect r = new Rect();
                n.getBoundsInScreen(r);
                if (r.width() > 0 && r.height() > 0) {
                    nodes.add(n);
                    sb.append('[').append(nodes.size() - 1).append("] ");
                    CharSequence cls = n.getClassName();
                    if (cls != null) { String c = cls.toString(); sb.append(c.substring(c.lastIndexOf('.') + 1)).append(' '); }
                    if (text != null && text.length() > 0) sb.append('"').append(text.toString().replace("\n", " ")).append("\" ");
                    if (desc != null && desc.length() > 0) sb.append("desc=\"").append(desc.toString().replace("\n", " ")).append("\" ");
                    String id = n.getViewIdResourceName();
                    if (id != null) sb.append("id=").append(id.substring(id.indexOf('/') + 1)).append(' ');
                    if (n.isClickable()) sb.append("clickable ");
                    if (n.isLongClickable()) sb.append("long-clickable ");
                    if (n.isScrollable()) sb.append("scrollable ");
                    if (n.isEditable()) sb.append("editable ");
                    if (n.isCheckable()) sb.append(n.isChecked() ? "checked " : "unchecked ");
                    if (n.isSelected()) sb.append("selected ");
                    if (n.isFocused()) sb.append("focused ");
                    if (!n.isEnabled()) sb.append("disabled ");
                    sb.append("@(").append(r.centerX()).append(',').append(r.centerY()).append(")\n");
                }
            }
        }
        for (int i = 0; i < n.getChildCount(); i++) walk(n.getChild(i), sb, all, depth + 1);
    }

    private synchronized AccessibilityNodeInfo node(int id) {
        if (id < 0 || id >= nodes.size()) throw new IllegalArgumentException("no element " + id + "; call ui_read again");
        AccessibilityNodeInfo n = nodes.get(id);
        n.refresh();
        return n;
    }

    @Override public String click(int id) { return act(id, false); }

    @Override public String longClick(int id) { return act(id, true); }

    private String act(int id, boolean longPress) {
        AccessibilityNodeInfo n = node(id);
        AccessibilityNodeInfo t = n;
        int guard = 0;
        while (t != null && guard++ < 8) {
            if (longPress ? t.isLongClickable() : t.isClickable()) {
                if (t.performAction(longPress ? AccessibilityNodeInfo.ACTION_LONG_CLICK : AccessibilityNodeInfo.ACTION_CLICK)) {
                    return (longPress ? "long-clicked " : "clicked ") + id;
                }
            }
            t = t.getParent();
        }
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        return tap(r.centerX(), r.centerY(), longPress ? 700 : 60)
                ? (longPress ? "long-pressed " : "tapped ") + id + " by gesture at (" + r.centerX() + "," + r.centerY() + ")"
                : "could not click element " + id;
    }

    private boolean tap(int x, int y, long duration) {
        if (Build.VERSION.SDK_INT < 24) return false;
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription g = new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p, 0, duration)).build();
        final CountDownLatch done = new CountDownLatch(1);
        final boolean[] ok = {false};
        boolean sent = dispatchGesture(g, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription d) { ok[0] = true; done.countDown(); }
            @Override public void onCancelled(GestureDescription d) { done.countDown(); }
        }, null);
        if (!sent) return false;
        try { done.await(3, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return ok[0];
    }

    @Override public String setText(int id, String text) {
        AccessibilityNodeInfo n = node(id);
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                ? "text set in " + id : "element " + id + " does not accept text (is it editable?)";
    }

    @Override public String scroll(int id, String direction) {
        AccessibilityNodeInfo n = node(id);
        String d = direction == null ? "" : direction.toLowerCase();
        int action;
        switch (d) {
            case "down": action = Build.VERSION.SDK_INT >= 23 ? AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.getId() : AccessibilityNodeInfo.ACTION_SCROLL_FORWARD; break;
            case "up": action = Build.VERSION.SDK_INT >= 23 ? AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.getId() : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD; break;
            case "right": action = Build.VERSION.SDK_INT >= 23 ? AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.getId() : AccessibilityNodeInfo.ACTION_SCROLL_FORWARD; break;
            case "left": action = Build.VERSION.SDK_INT >= 23 ? AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.getId() : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD; break;
            default: throw new IllegalArgumentException("direction must be up, down, left or right");
        }
        boolean ok = n.performAction(action);
        if (!ok) ok = n.performAction(d.equals("down") || d.equals("right") ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
        return ok ? "scrolled " + d : "cannot scroll " + d + " (end of list?)";
    }

    @Override public String global(String action) {
        int a;
        switch (action == null ? "" : action.toLowerCase()) {
            case "back": a = GLOBAL_ACTION_BACK; break;
            case "home": a = GLOBAL_ACTION_HOME; break;
            case "recents": a = GLOBAL_ACTION_RECENTS; break;
            case "notifications": a = GLOBAL_ACTION_NOTIFICATIONS; break;
            case "quick_settings": a = GLOBAL_ACTION_QUICK_SETTINGS; break;
            case "power_dialog": a = GLOBAL_ACTION_POWER_DIALOG; break;
            case "lock_screen": if (Build.VERSION.SDK_INT < 28) return "needs Android 9+"; a = GLOBAL_ACTION_LOCK_SCREEN; break;
            case "screenshot": if (Build.VERSION.SDK_INT < 28) return "needs Android 9+"; a = GLOBAL_ACTION_TAKE_SCREENSHOT; break;
            default: throw new IllegalArgumentException("unknown action: " + action);
        }
        return performGlobalAction(a) ? "done: " + action : "system refused: " + action;
    }

    @Override public byte[] screenshot() {
        if (Build.VERSION.SDK_INT < 30) return null;
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Bitmap> out = new AtomicReference<>();
        takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
            @Override public void onSuccess(ScreenshotResult r) {
                try {
                    Bitmap hw = Bitmap.wrapHardwareBuffer(r.getHardwareBuffer(), r.getColorSpace());
                    if (hw != null) out.set(hw.copy(Bitmap.Config.ARGB_8888, false));
                    r.getHardwareBuffer().close();
                } catch (RuntimeException ignored) { }
                done.countDown();
            }
            @Override public void onFailure(int code) { done.countDown(); }
        });
        try { done.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        Bitmap bm = out.get();
        if (bm == null) return null;
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        bm.compress(Bitmap.CompressFormat.PNG, 100, bo);
        return bo.toByteArray();
    }

    @Override public String foregroundPackage() {
        AccessibilityNodeInfo r = getRootInActiveWindow();
        return r == null || r.getPackageName() == null ? "" : r.getPackageName().toString();
    }
}
