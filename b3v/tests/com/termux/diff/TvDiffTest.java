package com.termux.diff;

import android.app.Activity;
import android.app.Application;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Looper;
import android.os.SystemClock;
import android.view.ActionMode;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.view.autofill.AutofillManager;
import android.view.autofill.AutofillValue;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.FrameLayout;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalOutput;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalRenderer;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowSystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Differential test for the terminal-view module: the very same scenarios run against the Java original and against the Kotlin
 * port (two Gradle modules, same test sources); every observable effect is written to a trace file per scenario and the traces
 * of the two variants are compared with diff. Only the public API and reflection by field/method name are used.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class, shadows = TvDiffTest.NativePty.class)
public class TvDiffTest {

    // ------------------------------------------------------------------------------------------------ JNI shadow
    @Implements(className = "com.termux.terminal.JNI", isInAndroidSdk = false)
    public static class NativePty {
        static final List<String> calls = new ArrayList<>();

        @Implementation public static void __staticInitializer__() {}

        @Implementation public static void setPtyWindowSize(int fd, int rows, int columns, int width, int height) {
            calls.add("JNI.setPtyWindowSize(" + fd + "," + rows + "," + columns + "," + width + "," + height + ")");
        }

        @Implementation public static int createSubprocess(String cmd, String cwd, String[] args, String[] env, int[] pid, int rows, int cols, int width, int height) {
            throw new IllegalStateException("no process in this test");
        }
    }

    // ------------------------------------------------------------------------------------------------ trace plumbing
    private final List<String> trace = new ArrayList<>();
    @Rule public TestName testName = new TestName();

    private void t(String s) { trace.add(s); }

    @Before public void before() {
        NativePty.calls.clear();
        trace.clear();
    }

    @After public void after() throws Exception {
        for (Env e : envs) {
            try { e.view.setWorkspaceTerminalVisible(false); } catch (Throwable ignored) { }
            try { e.ac.pause().stop().destroy(); } catch (Throwable ignored) { }
        }
        envs.clear();
        String dir = System.getProperty("tv.trace.dir", "/data/local/tmp/agentdroid-verify/b3/out");
        String variant = System.getProperty("tv.variant", "unknown");
        File d = new File(dir, "trace");
        d.mkdirs();
        try (Writer w = new OutputStreamWriter(new FileOutputStream(new File(d, variant + "__" + testName.getMethodName() + ".txt")), StandardCharsets.UTF_8)) {
            for (String s : trace) { w.write(s); w.write("\n"); }
        }
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 32 && c < 127 && c != '\\' && c != '"') sb.append(c);
            else sb.append(String.format("\\u%04x", (int) c));
        }
        return sb.toString();
    }

    private static String esc(byte[] b, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            int c = b[i] & 0xff;
            if (c >= 32 && c < 127 && c != '\\') sb.append((char) c);
            else sb.append(String.format("\\x%02x", c));
        }
        return sb.toString();
    }

    static String fmt(Object o) {
        if (o == null) return "null";
        if (o instanceof CharSequence) return "\"" + escape(o.toString()) + "\"";
        if (o instanceof Number || o instanceof Boolean || o instanceof Character) return String.valueOf(o);
        if (o instanceof MotionEvent) {
            MotionEvent m = (MotionEvent) o;
            return "ME{a=" + m.getAction() + ",x=" + m.getX() + ",y=" + m.getY() + ",src=" + m.getSource() + ",btn=" + m.getButtonState() + "}";
        }
        if (o instanceof KeyEvent) {
            KeyEvent k = (KeyEvent) o;
            return "KE{a=" + k.getAction() + ",c=" + k.getKeyCode() + ",m=" + k.getMetaState() + ",r=" + k.getRepeatCount() + "}";
        }
        if (o instanceof TerminalSession) return "session";
        if (o instanceof TerminalEmulator) return "emulator";
        if (o instanceof Throwable) return o.getClass().getName();
        if (o instanceof int[]) return Arrays.toString((int[]) o);
        if (o instanceof float[]) return Arrays.toString((float[]) o);
        if (o instanceof Object[]) {
            Object[] arr = (Object[]) o;
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < arr.length; i++) { if (i > 0) sb.append(", "); sb.append(fmt(arr[i])); }
            return sb.append("]").toString();
        }
        if (o instanceof Rect) return ((Rect) o).toShortString();
        if (Proxy.isProxyClass(o.getClass())) return "proxy";
        return o.getClass().getSimpleName();
    }

    /** Records every call of an interface proxy into the trace and answers with configurable values. */
    static final class Rec implements InvocationHandler {
        final List<String> out;
        final String tag;
        final Map<String, Object> ret = new HashMap<>();

        Rec(List<String> out, String tag) { this.out = out; this.tag = tag; }

        @Override public Object invoke(Object proxy, Method m, Object[] a) {
            String n = m.getName();
            if (m.getDeclaringClass() == Object.class) {
                if (n.equals("hashCode")) return 1;
                if (n.equals("equals")) return proxy == a[0];
                return tag;
            }
            if (out.size() > 300000) throw new IllegalStateException("trace overflow");
            StringBuilder sb = new StringBuilder("  ").append(tag).append('.').append(n).append('(');
            if (a != null) for (int i = 0; i < a.length; i++) { if (i > 0) sb.append(", "); sb.append(fmt(a[i])); }
            out.add(sb.append(')').toString());
            if (ret.containsKey(n)) return ret.get(n);
            Class<?> t = m.getReturnType();
            if (t == boolean.class) return false;
            if (t == int.class) return 0;
            if (t == long.class) return 0L;
            if (t == float.class) return (a != null && a.length == 1 && a[0] instanceof Float) ? a[0] : 1f;
            if (t == Integer.class) return 0;
            return null;
        }
    }

    private static Object proxy(Class<?> cls, InvocationHandler h) {
        return Proxy.newProxyInstance(TvDiffTest.class.getClassLoader(), new Class[]{cls}, h);
    }

    // ------------------------------------------------------------------------------------------------ reflection helpers
    private static Field findField(Class<?> c, String name) throws NoSuchFieldException {
        while (c != null) {
            try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f; } catch (NoSuchFieldException e) { c = c.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }

    private static void setField(Object o, String name, Object v) throws Exception { findField(o.getClass(), name).set(o, v); }

    private static Object getField(Object o, String name) throws Exception { return findField(o.getClass(), name).get(o); }

    /** Calls the first method with this name and arity (any visibility); exceptions are reported as text, not thrown. */
    private static Object callAny(Object o, String name, Object... args) {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name) || m.getParameterTypes().length != args.length || m.isSynthetic()) continue;
                try { m.setAccessible(true); return m.invoke(o, args); }
                catch (InvocationTargetException e) { return "EXC:" + e.getCause().getClass().getName(); }
                catch (Exception e) { return "REFLECT-EXC:" + e.getClass().getName(); }
            }
        }
        return "NO-SUCH-METHOD:" + name;
    }

    private interface Body { void run() throws Exception; }

    /** The selected text of the view, or the class of the exception the call threw (reading past the screen throws in the emulator). */
    private String sel(Env e) {
        try { return fmt(e.view.getSelectedText()); } catch (Throwable ex) { return "EXC:" + ex.getClass().getName(); }
    }

    private String stored(Env e) {
        try { return fmt(e.view.getStoredSelectedText()); } catch (Throwable ex) { return "EXC:" + ex.getClass().getName(); }
    }

    /** Runs a step and records an exception as its class name only (messages of NPEs differ between Java and Kotlin). */
    private void step(String label, Body b) {
        try { b.run(); }
        catch (Throwable e) {
            Throwable c = e instanceof InvocationTargetException && e.getCause() != null ? e.getCause() : e;
            t(label + " !EXC " + c.getClass().getName() + (c instanceof NullPointerException ? "" : ": " + c.getMessage()));
        }
    }

    // ------------------------------------------------------------------------------------------------ fake framework parts
    private final class FakeMode extends ActionMode {
        final List<MenuItem> items = new ArrayList<>();
        final Menu menu = newMenu(items);
        @Override public void setTitle(CharSequence title) { t("  mode.setTitle " + fmt(title)); }
        @Override public void setTitle(int resId) { t("  mode.setTitle#" + resId); }
        @Override public void setSubtitle(CharSequence subtitle) { t("  mode.setSubtitle " + fmt(subtitle)); }
        @Override public void setSubtitle(int resId) { t("  mode.setSubtitle#" + resId); }
        @Override public void setCustomView(View view) { t("  mode.setCustomView"); }
        @Override public void invalidate() { t("  mode.invalidate"); }
        @Override public void finish() { t("  mode.finish"); }
        @Override public Menu getMenu() { return menu; }
        @Override public CharSequence getTitle() { return null; }
        @Override public CharSequence getSubtitle() { return null; }
        @Override public View getCustomView() { return null; }
        @Override public MenuInflater getMenuInflater() { return null; }
        @Override public void hide(long duration) { t("  mode.hide(" + duration + ")"); }
        @Override public void invalidateContentRect() { t("  mode.invalidateContentRect"); }
    }

    private Menu newMenu(final List<MenuItem> items) {
        return (Menu) proxy(Menu.class, (p, m, a) -> {
            String n = m.getName();
            if (n.equals("add") || n.equals("addSubMenu")) {
                int id = a.length >= 4 ? (Integer) a[1] : 0;
                Object title = a.length >= 4 ? a[3] : a[0];
                MenuItem it = newItem(id, title);
                items.add(it);
                t("  menu." + n + "(id=" + id + ", title=" + fmt(title) + ")");
                return it;
            }
            if (n.equals("size")) return items.size();
            if (n.equals("getItem")) return items.get((Integer) a[0]);
            if (n.equals("hashCode")) return 7;
            if (n.equals("equals")) return p == a[0];
            if (n.equals("toString")) return "menu";
            Class<?> r = m.getReturnType();
            if (r == boolean.class) return false;
            if (r == int.class) return 0;
            return null;
        });
    }

    private MenuItem newItem(final int id, final Object title) {
        return (MenuItem) proxy(MenuItem.class, (p, m, a) -> {
            String n = m.getName();
            if (n.equals("getItemId")) return id;
            if (n.equals("getTitle")) return String.valueOf(title);
            if (n.equals("hashCode")) return id;
            if (n.equals("equals")) return p == a[0];
            if (n.equals("toString")) return "item" + id;
            StringBuilder sb = new StringBuilder("  item[" + id + "]." + n + "(");
            if (a != null) for (int i = 0; i < a.length; i++) { if (i > 0) sb.append(", "); sb.append(fmt(a[i])); }
            t(sb.append(")").toString());
            Class<?> r = m.getReturnType();
            if (r == MenuItem.class) return p;
            if (r == boolean.class) return false;
            if (r == int.class) return 0;
            return null;
        });
    }

    private final class Host extends FrameLayout {
        final Env env;
        Host(Context c, Env env) { super(c); this.env = env; }

        @Override public ActionMode startActionModeForChild(View child, ActionMode.Callback cb, int type) {
            t("  host.startActionModeForChild(type=" + type + ")");
            env.callbacks.add(cb);
            FakeMode m = new FakeMode();
            env.lastMode = m;
            boolean created = cb.onCreateActionMode(m, m.getMenu());
            t("  host.onCreateActionMode -> " + created);
            if (!created) return null;
            boolean prepared = cb.onPrepareActionMode(m, m.getMenu());
            t("  host.onPrepareActionMode -> " + prepared);
            return m;
        }

        @Override public ActionMode startActionModeForChild(View child, ActionMode.Callback cb) {
            return startActionModeForChild(child, cb, ActionMode.TYPE_PRIMARY);
        }
    }

    private final class Env {
        ActivityController<Activity> ac;
        Activity act;
        Host host;
        TerminalView view;
        TerminalSession session;
        TerminalEmulator emu;
        Rec clientRec, sessRec;
        TerminalViewClient client;
        TerminalSessionClient sclient;
        Object queue;
        Method qread;
        FakeMode lastMode;
        final List<ActionMode.Callback> callbacks = new ArrayList<>();

        void geometry(int cols, int rows) {
            int w = (int) Math.ceil(cols * view.mRenderer.getFontWidth());
            int h = rows * view.mRenderer.getFontLineSpacing() + 3;
            view.setLayoutParams(new FrameLayout.LayoutParams(w, h));
            view.layout(0, 0, w, h);
        }

        String drain() throws Exception {
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[512];
            for (; ; ) {
                int n = (Integer) qread.invoke(queue, buf, false);
                if (n <= 0) break;
                sb.append(esc(buf, n));
            }
            return sb.toString();
        }

        void feed(String s) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            emu.append(b, b.length);
        }

        /**
         * Advances the virtual clock in 1 ms steps and runs the due tasks one at a time. Looper.idleFor() would spin forever on the
         * fling runnable, which re-posts itself without delay and only finishes when the clock moves on.
         */
        void tick(long ms) {
            ShadowLooper sl = Shadows.shadowOf(Looper.getMainLooper());
            long end = SystemClock.uptimeMillis() + ms;
            while (SystemClock.uptimeMillis() < end) {
                if (!sl.isIdle()) sl.runOneTask();
                ShadowSystemClock.advanceBy(1, TimeUnit.MILLISECONDS);
            }
            for (int i = 0; i < 16 && !sl.isIdle(); i++) sl.runOneTask();
        }
    }

    private final List<Env> envs = new ArrayList<>();

    private static void fixRenderer(TerminalRenderer r) throws Exception {
        setField(r, "mFontWidth", 8f);
        setField(r, "mFontLineSpacing", 16);
        setField(r, "mFontAscent", -13);
        setField(r, "mFontLineSpacingAndAscent", 3);
        float[] m = (float[]) getField(r, "asciiMeasures");
        Arrays.fill(m, 8f);
        m['W'] = 9f;
        m['i'] = 5f;
        m['m'] = 12f;
    }

    private Env newEnv(int cols, int rows, boolean stable, boolean preserve, boolean accessibility) throws Exception {
        final Env e = new Env();
        if (accessibility) {
            AccessibilityManager am = (AccessibilityManager) RuntimeEnvironment.application.getSystemService(Context.ACCESSIBILITY_SERVICE);
            Shadows.shadowOf(am).setEnabled(true);
        }
        e.ac = Robolectric.buildActivity(Activity.class).setup().visible();
        e.act = e.ac.get();
        e.view = new TerminalView(e.act, null);
        e.view.setFocusable(true);              // the real layout declares focusable + focusableInTouchMode
        e.view.setFocusableInTouchMode(true);
        e.view.setTextSize(16);
        fixRenderer(e.view.mRenderer);
        e.clientRec = new Rec(trace, "client");
        e.client = (TerminalViewClient) proxy(TerminalViewClient.class, e.clientRec);
        e.view.setTerminalViewClient(e.client);
        e.host = new Host(e.act, e);
        e.act.setContentView(e.host);
        e.host.addView(e.view, new FrameLayout.LayoutParams(1, 1));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        Field attach = View.class.getDeclaredField("mAttachInfo");
        attach.setAccessible(true);
        Object info = attach.get(e.view);
        Field visibility = info.getClass().getDeclaredField("mWindowVisibility");
        visibility.setAccessible(true);
        visibility.setInt(info, View.VISIBLE);
        e.sessRec = new Rec(trace, "sess");
        e.sclient = (TerminalSessionClient) proxy(TerminalSessionClient.class, e.sessRec);
        e.session = new TerminalSession("unused", "unused", new String[0], new String[0], 2000, e.sclient);
        e.session.setStableResizeEnabled(stable);
        e.session.setPreserveTranscriptScroll(preserve);
        e.emu = new TerminalEmulator(e.session, cols, rows, 10, 20, 2000, e.sclient);
        setField(e.session, "mEmulator", e.emu);
        setField(e.session, "mTerminalFileDescriptor", 11);
        setField(e.session, "mShellPid", 1);
        e.view.mTermSession = e.session;
        e.view.mEmulator = e.emu;
        e.geometry(cols, rows);
        e.queue = getField(e.session, "mTerminalToProcessIOQueue");
        e.qread = e.queue.getClass().getDeclaredMethod("read", byte[].class, boolean.class);
        e.qread.setAccessible(true);
        envs.add(e);
        t("env " + cols + "x" + rows + " stable=" + stable + " preserve=" + preserve + " view=" + e.view.getWidth() + "x" + e.view.getHeight()
                + " emuRows=" + e.emu.mRows + " emuCols=" + e.emu.mColumns + " jni=" + NativePty.calls);
        NativePty.calls.clear();
        return e;
    }

    private void seedHistory(Env e, int lines) {
        for (int i = 0; i < lines; i++) e.feed("line " + i + " the quick brown fox 中文 end\r\n");
        e.view.onScreenUpdated();
    }

    private static MotionEvent me(long down, long time, int action, float x, float y) {
        return MotionEvent.obtain(down, time, action, x, y, 0);
    }

    private static MotionEvent meFull(long down, long time, int action, float x, float y, int source, int buttons, float vscroll) {
        MotionEvent.PointerProperties[] props = {new MotionEvent.PointerProperties()};
        props[0].id = 0;
        props[0].toolType = source == InputDevice.SOURCE_MOUSE ? MotionEvent.TOOL_TYPE_MOUSE : MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords[] coords = {new MotionEvent.PointerCoords()};
        coords[0].x = x;
        coords[0].y = y;
        coords[0].setAxisValue(MotionEvent.AXIS_VSCROLL, vscroll);
        return MotionEvent.obtain(down, time, action, 1, props, coords, 0, buttons, 1f, 1f, 0, 0, source, 0);
    }

    // ================================================================================================ 1. renderer
    private final class RecCanvas extends Canvas {
        @Override public void drawColor(int color, PorterDuff.Mode mode) { t("  drawColor " + Integer.toHexString(color) + " " + mode); }

        @Override public void drawRect(float l, float tp, float r, float b, Paint p) { t("  drawRect " + l + "," + tp + "," + r + "," + b + " " + pp(p)); }

        @Override public void drawTextRun(char[] text, int index, int count, int contextIndex, int contextCount, float x, float y, boolean isRtl, Paint p) {
            t("  drawTextRun \"" + escape(new String(text, index, count)) + "\" idx=" + index + "," + count + "," + contextIndex + "," + contextCount
                    + " at " + x + "," + y + " rtl=" + isRtl + " " + pp(p));
        }

        @Override public int save() { t("  save"); return 1; }

        @Override public void restore() { t("  restore"); }

        @Override public void scale(float sx, float sy) { t("  scale " + sx + "," + sy); }
    }

    private static String pp(Paint p) {
        return "paint{color=" + Integer.toHexString(p.getColor()) + ",bold=" + p.isFakeBoldText() + ",ul=" + p.isUnderlineText()
                + ",skew=" + p.getTextSkewX() + ",strike=" + p.isStrikeThruText() + ",size=" + p.getTextSize() + "}";
    }

    @Test public void render() throws Exception {
        TerminalRenderer r = new TerminalRenderer(14, Typeface.MONOSPACE);
        fixRenderer(r);
        t("renderer font " + r.getFontWidth() + "x" + r.getFontLineSpacing());
        TerminalSessionClient sc = (TerminalSessionClient) proxy(TerminalSessionClient.class, new Rec(trace, "sess"));
        TerminalOutput out = new TerminalOutput() {
            @Override public void write(byte[] b, int o, int n) { }
            @Override public void titleChanged(String a, String b) { }
            @Override public void onCopyTextToClipboard(String s) { }
            @Override public void onPasteTextFromClipboard() { }
            @Override public void onBell() { }
            @Override public void onColorsChanged() { }
        };
        TerminalEmulator em = new TerminalEmulator(out, 24, 8, 8, 16, 50, sc);
        String[] feeds = {
                "plain text line WiMi\r\n",
                "\u001b[1mBOLD\u001b[0m \u001b[2mdim\u001b[0m \u001b[3mital\u001b[0m \u001b[4munder\u001b[0m \u001b[5mblink\u001b[0m \u001b[7minv\u001b[0m \u001b[8mhid\u001b[0m \u001b[9mstrike\u001b[0m\r\n",
                "\u001b[31mred\u001b[1;32mbgreen\u001b[0;94mhiblue\u001b[0m \u001b[38;5;200mc200\u001b[48;5;17mbg17\u001b[0m \u001b[1;31mbold-red\u001b[0m\r\n",
                "\u001b[38;2;10;20;30mtruecolor\u001b[48;2;200;100;50m+bg\u001b[2;38;2;255;255;255mdimtrue\u001b[0m\u001b[7;38;5;9minvidx\u001b[0m\r\n",
                "wide: 中文 emoji: 😀 comb: é ä \u0001end\r\n",
                "\u001b[1;3;4;9;31;42mALLATTR\u001b[0m \u001b[2;7mdiminv\u001b[0m \u001b[5;1;38;5;3mblinkbold-idx3\u001b[0m\r\n",
        };
        for (String f : feeds) { byte[] b = f.getBytes(StandardCharsets.UTF_8); em.append(b, b.length); }
        em.append("\u001b[3;5H".getBytes(), 6);
        String[] modes = {"block cursor", "\u001b[4 q", "\u001b[6 q", "\u001b[2 q", "\u001b[?25l", "\u001b[?25h\u001b[?5h", "\u001b[?5l"};
        int[][] sels = {{-1, -1, -1, -1}, {1, 1, 2, 9}, {0, 3, 5, 3}, {2, 4, 20, 7}, {3, 1, 2, 2}, {1, 1, 0, 40}};
        for (String mode : modes) {
            if (!mode.equals("block cursor")) { byte[] b = mode.getBytes(); em.append(b, b.length); }
            t("== mode " + escape(mode) + " style=" + em.getCursorStyle() + " visible=" + em.shouldCursorBeVisible() + " reverse=" + em.isReverseVideo());
            for (int[] s : sels) {
                for (int top : new int[]{0, -2}) {
                    t("-- render top=" + top + " sel=" + Arrays.toString(s));
                    RecCanvas c = new RecCanvas();
                    step("render", () -> r.render(em, c, top, s[0], s[1], s[2], s[3]));
                    if (top == 0 && s[0] == 1) {
                        t("-- render8 visibleRows=5");
                        step("render8", () -> r.render(em, c, top, s[0], s[1], s[2], s[3], 5));
                    }
                }
            }
        }
    }

    // ================================================================================================ 2. keys
    private static final class FakeKey extends KeyEvent {
        private final int unicode;

        FakeKey(int action, int code, int meta, int unicode) {
            super(1000L, 1000L, action, code, 0, meta, 0, 0, 0, InputDevice.SOURCE_KEYBOARD);
            this.unicode = unicode;
        }

        @Override public int getUnicodeChar(int metaState) { return unicode; }

        @Override public int getUnicodeChar() { return unicode; }
    }

    private static KeyEvent ke(int action, int code, int meta) {
        return new KeyEvent(1000L, 1000L, action, code, 0, meta, 0, 0, 0, InputDevice.SOURCE_KEYBOARD);
    }

    private static final int[] KEYCODES = {
            KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_INSERT,
            KeyEvent.KEYCODE_F1, KeyEvent.KEYCODE_F5, KeyEvent.KEYCODE_F12, KeyEvent.KEYCODE_NUMPAD_0, KeyEvent.KEYCODE_NUMPAD_5,
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_NUMPAD_ADD, KeyEvent.KEYCODE_NUMPAD_DOT, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_UNKNOWN, KeyEvent.KEYCODE_COMMA, KeyEvent.KEYCODE_SLASH, KeyEvent.KEYCODE_MINUS,
            KeyEvent.KEYCODE_LEFT_BRACKET, KeyEvent.KEYCODE_BACKSLASH};
    private static final int[] METAS = {
            0,
            KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON,
            KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON,
            KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON,
            KeyEvent.META_ALT_ON | KeyEvent.META_ALT_RIGHT_ON,
            KeyEvent.META_FUNCTION_ON,
            KeyEvent.META_NUM_LOCK_ON,
            KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON | KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON | KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON};

    /** Our own little key map for the FakeKey events (the framework's map is not what is under test). */
    private static int unicodeFor(int code, int meta) {
        boolean shift = (meta & KeyEvent.META_SHIFT_ON) != 0;
        if (code >= KeyEvent.KEYCODE_A && code <= KeyEvent.KEYCODE_Z) return (shift ? 'A' : 'a') + (code - KeyEvent.KEYCODE_A);
        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) return '0' + (code - KeyEvent.KEYCODE_0);
        switch (code) {
            case KeyEvent.KEYCODE_SPACE: return ' ';
            case KeyEvent.KEYCODE_COMMA: return ',';
            case KeyEvent.KEYCODE_SLASH: return '/';
            case KeyEvent.KEYCODE_MINUS: return shift ? '_' : '-';
            case KeyEvent.KEYCODE_LEFT_BRACKET: return '[';
            case KeyEvent.KEYCODE_BACKSLASH: return '\\';
            case KeyEvent.KEYCODE_ENTER: return '\n';
            case KeyEvent.KEYCODE_TAB: return '\t';
            default: return 0;
        }
    }

    private void configure(Env e, int cfg) {
        e.clientRec.ret.clear();
        switch (cfg) {
            case 1: e.clientRec.ret.put("readControlKey", true); break;
            case 2: e.clientRec.ret.put("readAltKey", true); e.clientRec.ret.put("readShiftKey", true); break;
            case 3: e.clientRec.ret.put("readFnKey", true); e.clientRec.ret.put("onKeyDown", true); break;
            case 4: e.clientRec.ret.put("onCodePoint", true); e.clientRec.ret.put("onKeyUp", true); break;
            case 5: e.clientRec.ret.put("shouldBackButtonBeMappedToEscape", true); e.clientRec.ret.put("shouldUseCtrlSpaceWorkaround", true); break;
            default: break;
        }
    }

    @Test public void keys() throws Exception {
        Env e = newEnv(80, 24, false, false, false);
        for (int cfg = 0; cfg <= 5; cfg++) {
            configure(e, cfg);
            t("== config " + cfg);
            for (int code : KEYCODES) {
                for (int meta : METAS) {
                    KeyEvent down = ke(KeyEvent.ACTION_DOWN, code, meta);
                    final boolean[] r = new boolean[1];
                    step("D", () -> r[0] = e.view.onKeyDown(code, down));
                    t("D code=" + code + " meta=" + meta + " -> " + r[0] + " out=" + e.drain());
                    if (cfg == 0 || cfg == 4) {
                        KeyEvent up = ke(KeyEvent.ACTION_UP, code, meta);
                        step("U", () -> r[0] = e.view.onKeyUp(code, up));
                        t("U code=" + code + " meta=" + meta + " -> " + r[0] + " out=" + e.drain());
                    }
                }
            }
        }
        // application cursor keys + keypad mode change what the arrow/keypad keys send
        configure(e, 0);
        e.feed("\u001b[?1h\u001b=");
        t("== application modes cursorKeys=" + e.emu.isCursorKeysApplicationMode() + " keypad=" + e.emu.isKeypadApplicationMode());
        for (int code : KEYCODES) {
            for (int meta : new int[]{0, METAS[1], METAS[2], METAS[6]}) {
                KeyEvent down = ke(KeyEvent.ACTION_DOWN, code, meta);
                final boolean[] r = new boolean[1];
                step("D", () -> r[0] = e.view.onKeyDown(code, down));
                t("D code=" + code + " meta=" + meta + " -> " + r[0] + " out=" + e.drain());
            }
        }
        e.feed("\u001b[?1l\u001b>");
        // key events with our own key map: letters, ctrl/alt/shift, digits and control combinations
        for (int cfg : new int[]{0, 1, 2}) {
            configure(e, cfg);
            t("== fake-key config " + cfg);
            for (int code : KEYCODES) {
                for (int meta : METAS) {
                    FakeKey down = new FakeKey(KeyEvent.ACTION_DOWN, code, meta, unicodeFor(code, meta));
                    final boolean[] r = new boolean[1];
                    step("FD", () -> r[0] = e.view.onKeyDown(code, down));
                    t("FD code=" + code + " meta=" + meta + " uc=" + unicodeFor(code, meta) + " -> " + r[0] + " out=" + e.drain());
                }
            }
        }
        // combining accents (dead keys): accent then letter, accent twice, accent then non-composable, accent then control
        configure(e, 0);
        int acc = KeyCharacterMap.COMBINING_ACCENT;
        int[][] seqs = {
                {acc | 0x300, 'a'}, {acc | 0x301, acc | 0x301, 'e'}, {acc | 0x308, 'z'}, {acc | 0x303, 'n', 'n'}, {acc | 0x302, ' '},
                {acc | 0x300}, {'a', acc | 0x301, 'o'}, {acc | 0x301, '\n'}};
        for (int[] seq : seqs) {
            t("== dead-key sequence " + Arrays.toString(seq));
            for (int u : seq) {
                FakeKey down = new FakeKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0, u);
                final boolean[] r = new boolean[1];
                step("DK", () -> r[0] = e.view.onKeyDown(KeyEvent.KEYCODE_A, down));
                t("DK u=" + u + " -> " + r[0] + " out=" + e.drain());
            }
        }
        // ACTION_MULTIPLE + KEYCODE_UNKNOWN delivers a string
        KeyEvent multi = new KeyEvent(1000L, "héllo 中", 0, 0);
        step("MULTI", () -> t("MULTI -> " + e.view.onKeyDown(KeyEvent.KEYCODE_UNKNOWN, multi) + " out=" + e.drain()));
        // shift+page up/down scroll the history instead of being sent
        seedHistory(e, 80);
        t("topRow before page keys=" + e.view.getTopRow());
        for (int i = 0; i < 3; i++) {
            step("PGUP", () -> t("PGUP -> " + e.view.onKeyDown(KeyEvent.KEYCODE_PAGE_UP, ke(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.META_SHIFT_ON))
                    + " top=" + e.view.getTopRow() + " out=" + e.drain()));
        }
        step("PGDN", () -> t("PGDN -> " + e.view.onKeyDown(KeyEvent.KEYCODE_PAGE_DOWN, ke(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.META_SHIFT_ON))
                + " top=" + e.view.getTopRow() + " out=" + e.drain()));
        // missing emulator / session
        e.view.mEmulator = null;
        step("NOEMU-D", () -> t("noemu down -> " + e.view.onKeyDown(KeyEvent.KEYCODE_A, ke(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0))));
        step("NOEMU-U", () -> t("noemu up -> " + e.view.onKeyUp(KeyEvent.KEYCODE_A, ke(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A, 0))));
        step("NOEMU-UB", () -> t("noemu up back -> " + e.view.onKeyUp(KeyEvent.KEYCODE_BACK, ke(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0))));
        e.view.mEmulator = e.emu;
        e.view.mTermSession = null;
        step("NOSESS-CP", () -> e.view.inputCodePoint('a', false, false));
        step("NOSESS-D", () -> t("nosess down -> " + e.view.onKeyDown(KeyEvent.KEYCODE_A, new FakeKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0, 'a'))));
        e.view.mTermSession = e.session;
    }

    @Test public void keyPreImeAndBack() throws Exception {
        Env e = newEnv(80, 24, false, false, false);
        for (int cfg : new int[]{0, 5}) {
            configure(e, cfg);
            for (int selecting = 0; selecting <= 1; selecting++) {
                t("== cfg " + cfg + " selecting=" + selecting);
                for (int action : new int[]{KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
                    for (int code : new int[]{KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_A}) {
                        for (int meta : new int[]{0, KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON}) {
                            if (selecting == 1 && !e.view.isSelectingText()) {
                                step("sel", () -> e.view.startTextSelectionMode(me(1, 1, MotionEvent.ACTION_DOWN, 20, 30)));
                            }
                            KeyEvent ev = ke(action, code, meta);
                            final boolean[] r = new boolean[1];
                            step("pre", () -> r[0] = e.view.onKeyPreIme(code, ev));
                            t("preIme a=" + action + " code=" + code + " meta=" + meta + " -> " + r[0] + " selecting=" + e.view.isSelectingText() + " out=" + e.drain());
                        }
                    }
                }
            }
        }
    }

    @Test public void keysLogged() throws Exception {
        Env e = newEnv(80, 24, false, false, false);
        e.view.setIsTerminalViewKeyLoggingEnabled(true);
        try {
            configure(e, 0);
            step("D", () -> t("D -> " + e.view.onKeyDown(KeyEvent.KEYCODE_A, new FakeKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, KeyEvent.META_SHIFT_ON, 'A')) + " out=" + e.drain()));
            step("D2", () -> t("D2 -> " + e.view.onKeyDown(KeyEvent.KEYCODE_DPAD_UP, ke(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP, 0)) + " out=" + e.drain()));
            step("D3", () -> t("D3 -> " + e.view.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP, ke(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP, 0)) + " out=" + e.drain()));
            step("U", () -> t("U -> " + e.view.onKeyUp(KeyEvent.KEYCODE_A, ke(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A, 0))));
            step("P", () -> t("P -> " + e.view.onKeyPreIme(KeyEvent.KEYCODE_BACK, ke(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0))));
            step("CP", () -> e.view.inputCodePoint('x', true, true));
            t("out=" + e.drain());
            EditorInfo ei = new EditorInfo();
            final InputConnection[] ic = new InputConnection[1];
            step("ic", () -> ic[0] = e.view.onCreateInputConnection(ei));
            step("commit", () -> ic[0].commitText("ab", 1));
            step("finish", () -> { ic[0].setComposingText("cd", 1); ic[0].finishComposingText(); });
            step("del", () -> ic[0].deleteSurroundingText(2, 0));
            t("out=" + e.drain());
        } finally {
            e.view.setIsTerminalViewKeyLoggingEnabled(false);
        }
    }

    @Test public void inputCodePoints() throws Exception {
        Env e = newEnv(80, 24, false, false, false);
        int[] cps = {-1, 0, 1, 7, 27, 32, '0', '2', '3', '4', '5', '6', '7', '8', '9', '/', '[', '\\', ']', '^', '_', '`', '@', 'A', 'M', 'Z', 'a', 'm', 'z', '~',
                127, 128, 0xe9, 0x2dc, 0x2cb, 0x2c6, 0x20ac, 0x4e2d, 0xd7ff, 0xd800, 0xdfff, 0xe000, 0x1f600, 0x10ffff, 0x110000};
        for (int cfg : new int[]{0, 1, 2, 4}) {
            configure(e, cfg);
            t("== config " + cfg);
            for (int cp : cps) {
                for (int flags = 0; flags < 4; flags++) {
                    boolean ctrl = (flags & 1) != 0, alt = (flags & 2) != 0;
                    step("cp", () -> e.view.inputCodePoint(cp, ctrl, alt));
                    t("cp=" + cp + " ctrl=" + ctrl + " alt=" + alt + " out=" + e.drain());
                }
            }
        }
        // handleKeyCode: every code/modifier combination without an event
        configure(e, 0);
        for (int code : KEYCODES) {
            for (int keyMod = 0; keyMod < 16; keyMod++) {
                final boolean[] r = new boolean[1];
                final int km = keyMod;
                step("hk", () -> r[0] = e.view.handleKeyCode(code, km));
                t("handleKeyCode " + code + " mod=" + keyMod + " -> " + r[0] + " out=" + e.drain());
            }
        }
        seedHistory(e, 60);
        for (int code : new int[]{KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_A}) {
            for (int keyMod : new int[]{0, 1, 2, 4}) {
                final boolean[] r = new boolean[1];
                step("hka", () -> r[0] = e.view.handleKeyCodeAction(code, keyMod));
                t("handleKeyCodeAction " + code + " mod=" + keyMod + " -> " + r[0] + " top=" + e.view.getTopRow());
            }
        }
    }

    // ================================================================================================ 3. IME
    @Test public void ime() throws Exception {
        Env e = newEnv(80, 24, false, false, false);
        for (int mode = 0; mode < 4; mode++) {
            e.clientRec.ret.clear();
            e.clientRec.ret.put("isTerminalViewSelected", (mode & 1) != 0);
            e.clientRec.ret.put("shouldEnforceCharBasedInput", (mode & 2) != 0);
            EditorInfo ei = new EditorInfo();
            final InputConnection[] ic = new InputConnection[1];
            step("create", () -> ic[0] = e.view.onCreateInputConnection(ei));
            t("== create mode=" + mode + " ic=" + (ic[0] != null) + " inputType=" + Integer.toHexString(ei.inputType) + " imeOptions=" + Integer.toHexString(ei.imeOptions)
                    + " isTextEditor=" + e.view.onCheckIsTextEditor());
        }
        e.view.setSoftKeyboardInputEnabled(false);
        EditorInfo none = new EditorInfo();
        t("soft keyboard disabled: ic=" + e.view.onCreateInputConnection(none) + " enabled=" + e.view.isSoftKeyboardInputEnabled() + " textEditor=" + e.view.onCheckIsTextEditor());
        e.view.setSoftKeyboardInputEnabled(true);
        e.clientRec.ret.clear();
        EditorInfo ei = new EditorInfo();
        final InputConnection ic = e.view.onCreateInputConnection(ei);
        String[] texts = {"abc", "Hello, wörld", "line1\nline2", "tab\there", "\u0003\u0004\u001a", "\u001b[A", "\u001f\u001e\u001d\u001c", "😀",
                "x\uD83D", "ÿß", "\u0000", "ctrl\u0001"};
        for (int shift = 0; shift <= 1; shift++) {
            e.clientRec.ret.put("readShiftKey", shift == 1);
            e.clientRec.ret.put("readControlKey", false);
            final boolean shiftOn = shift == 1;
            for (String text : texts) {
                step("commit", () -> t("commit " + fmt(text) + " shift=" + shiftOn + " -> " + ic.commitText(text, 1)));
                t("  out=" + e.drain());
            }
        }
        e.clientRec.ret.clear();
        step("compose", () -> t("setComposing -> " + ic.setComposingText("comp", 1)));
        t("  out(before finish)=" + e.drain());
        step("finish", () -> t("finishComposing -> " + ic.finishComposingText()));
        t("  out=" + e.drain());
        step("finish2", () -> t("finishComposing(empty) -> " + ic.finishComposingText()));
        t("  out=" + e.drain());
        for (int[] d : new int[][]{{0, 0}, {1, 0}, {3, 0}, {2, 2}}) {
            step("delete", () -> t("deleteSurrounding " + d[0] + "," + d[1] + " -> " + ic.deleteSurroundingText(d[0], d[1])));
            t("  out=" + e.drain());
        }
        step("key", () -> t("sendKeyEvent enter -> " + ic.sendKeyEvent(ke(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0))));
        t("  out=" + e.drain());
        // commit without emulator, and while selecting text
        e.view.mEmulator = null;
        step("commit-noemu", () -> t("commit no emulator -> " + ic.commitText("zz", 1)));
        step("finish-noemu", () -> { ic.setComposingText("yy", 1); t("finish no emulator -> " + ic.finishComposingText()); });
        e.view.mEmulator = e.emu;
        t("  out=" + e.drain());
        step("sel", () -> e.view.startTextSelectionMode(me(1, 1, MotionEvent.ACTION_DOWN, 20, 30)));
        t("selecting=" + e.view.isSelectingText());
        step("commit-sel", () -> t("commit while selecting -> " + ic.commitText("q", 1)));
        t("  selecting=" + e.view.isSelectingText() + " out=" + e.drain());
    }

    // ================================================================================================ 4. touch, gestures
    private Object listener(Env e) throws Exception {
        return getField(getField(e.view, "mGestureRecognizer"), "mListener");
    }

    private Object lcall(Object l, String name, Object... args) {
        try {
            Class<?> li = Class.forName("com.termux.view.GestureAndScaleRecognizer$Listener");
            for (Method m : li.getMethods()) {
                if (m.getName().equals(name) && m.getParameterTypes().length == args.length) {
                    m.setAccessible(true);
                    return m.invoke(l, args);
                }
            }
            return "NO-SUCH-LISTENER-METHOD";
        } catch (InvocationTargetException ex) {
            Throwable c = ex.getCause();
            return "EXC:" + c.getClass().getName() + (c instanceof NullPointerException ? "" : ":" + c.getMessage());
        } catch (Exception ex) {
            return "REFLECT-EXC:" + ex;
        }
    }

    @Test public void gestureListenerDirect() throws Exception {
        for (int variant = 0; variant < 4; variant++) {
            Env e = newEnv(80, 24, false, variant == 3, false);
            seedHistory(e, 100);
            Object l = listener(e);
            String[] names = {"normal", "alternate buffer", "mouse tracking", "preserving scroll"};
            if (variant == 1) e.feed("\u001b[?1049h");
            if (variant == 2) e.feed("\u001b[?1000h\u001b[?1006h");
            t("=== variant " + names[variant] + " alt=" + e.emu.isAlternateBufferActive() + " mouse=" + e.emu.isMouseTrackingActive());
            long now = SystemClock.uptimeMillis();
            MotionEvent finger = me(now, now, MotionEvent.ACTION_MOVE, 41, 100);
            MotionEvent mouse = meFull(now, now, MotionEvent.ACTION_MOVE, 41, 100, InputDevice.SOURCE_MOUSE, MotionEvent.BUTTON_PRIMARY, 0f);
            t("onDown -> " + lcall(l, "onDown", 10f, 20f));
            t("onDoubleTap -> " + lcall(l, "onDoubleTap", finger));
            for (float dy : new float[]{5f, 7f, 20f, -35f, 100f, -3f, 0f, 16f, -16f, 47.5f, -47.5f, 250f, -400f}) {
                t("onScroll dy=" + dy + " -> " + lcall(l, "onScroll", finger, 0f, dy) + " top=" + e.view.getTopRow() + " out=" + e.drain());
            }
            t("onScroll mouse -> " + lcall(l, "onScroll", mouse, 0f, 33f) + " top=" + e.view.getTopRow() + " out=" + e.drain());
            t("onUp finger -> " + lcall(l, "onUp", finger) + " out=" + e.drain());
            t("onUp mouse -> " + lcall(l, "onUp", mouse) + " out=" + e.drain());
            t("onSingleTapUp -> " + lcall(l, "onSingleTapUp", finger) + " out=" + e.drain());
            t("onScale -> " + lcall(l, "onScale", 5f, 6f, 1.25f) + " / " + lcall(l, "onScale", 5f, 6f, 0.5f));
            e.clientRec.ret.put("onLongPress", true);
            t("onLongPress consumed -> " + lcall(l, "onLongPress", finger) + " selecting=" + e.view.isSelectingText());
            e.clientRec.ret.put("onLongPress", false);
            t("onLongPress -> " + lcall(l, "onLongPress", finger) + " selecting=" + e.view.isSelectingText());
            t("onLongPress again -> " + lcall(l, "onLongPress", finger) + " selecting=" + e.view.isSelectingText());
            t("onScale while selecting -> " + lcall(l, "onScale", 5f, 6f, 2f));
            t("onSingleTapUp while selecting -> " + lcall(l, "onSingleTapUp", finger) + " selecting=" + e.view.isSelectingText());
            // fling: the scroller animation is driven by the looper
            for (float vy : new float[]{3000f, -3000f, 400f}) {
                t("onFling vy=" + vy + " -> " + lcall(l, "onFling", finger, 0f, vy) + " top=" + e.view.getTopRow());
                for (int i = 0; i < 12; i++) {
                    e.tick(16);
                    t("  fling step " + i + " top=" + e.view.getTopRow() + " out=" + e.drain());
                }
                e.tick(2000);
                t("  fling end top=" + e.view.getTopRow() + " out=" + e.drain());
            }
            t("onFling twice -> " + lcall(l, "onFling", finger, 0f, 2000f) + " / " + lcall(l, "onFling", finger, 0f, 2000f));
            e.tick(40);
            // fling that is interrupted by a mouse tracking mode change
            e.feed(variant == 2 ? "\u001b[?1000l" : "\u001b[?1000h");
            e.tick(500);
            t("  fling after mode change top=" + e.view.getTopRow() + " mouse=" + e.emu.isMouseTrackingActive() + " out=" + e.drain());
            // no emulator
            e.view.mEmulator = null;
            for (String n : new String[]{"onSingleTapUp", "onFling", "onScroll", "onScale"}) {
                Object r = n.equals("onScroll") ? lcall(l, n, finger, 0f, 5f) : n.equals("onScale") ? lcall(l, n, 1f, 1f, 1f) : lcall(l, n, finger, 0f, 5f);
                t("no emulator " + n + " -> " + r);
            }
            t("no emulator onUp -> " + lcall(l, "onUp", finger));
            e.view.mEmulator = e.emu;
            finger.recycle();
            mouse.recycle();
        }
    }

    @Test public void touchEvents() throws Exception {
        for (int variant = 0; variant < 3; variant++) {
            Env e = newEnv(80, 24, false, false, false);
            seedHistory(e, 100);
            if (variant == 1) e.feed("\u001b[?1049h");
            if (variant == 2) e.feed("\u001b[?1000h\u001b[?1006h");
            t("=== variant " + variant);
            long t0 = SystemClock.uptimeMillis();
            // tap
            step("tap", () -> {
                t("DOWN -> " + e.view.onTouchEvent(me(t0, t0, MotionEvent.ACTION_DOWN, 50, 60)));
                e.tick(30);
                t("UP -> " + e.view.onTouchEvent(me(t0, t0 + 30, MotionEvent.ACTION_UP, 50, 60)));
                e.tick(400);
                t("  after tap out=" + e.drain() + " top=" + e.view.getTopRow());
            });
            // drag down and up in small steps
            long t1 = SystemClock.uptimeMillis();
            step("drag", () -> {
                t("DOWN -> " + e.view.onTouchEvent(me(t1, t1, MotionEvent.ACTION_DOWN, 50, 200)));
                float y = 200;
                for (int i = 1; i <= 14; i++) {
                    y += (i % 3 == 0 ? -11 : 13) * (i < 8 ? 1 : -1);
                    e.tick(10);
                    t("MOVE y=" + y + " -> " + e.view.onTouchEvent(me(t1, t1 + 10L * i, MotionEvent.ACTION_MOVE, 50, y)) + " top=" + e.view.getTopRow() + " out=" + e.drain());
                }
                t("UP -> " + e.view.onTouchEvent(me(t1, t1 + 200, MotionEvent.ACTION_UP, 50, y)) + " top=" + e.view.getTopRow() + " out=" + e.drain());
                e.tick(600);
                t("  settled top=" + e.view.getTopRow() + " out=" + e.drain());
            });
            // long press selects text
            long t2 = SystemClock.uptimeMillis();
            step("longpress", () -> {
                t("DOWN -> " + e.view.onTouchEvent(me(t2, t2, MotionEvent.ACTION_DOWN, 90, 80)));
                e.tick(1200);
                t("  after long press selecting=" + e.view.isSelectingText() + " selected=" + sel(e) + " out=" + e.drain());
                t("MOVE -> " + e.view.onTouchEvent(me(t2, t2 + 1300, MotionEvent.ACTION_MOVE, 100, 90)));
                t("UP -> " + e.view.onTouchEvent(me(t2, t2 + 1400, MotionEvent.ACTION_UP, 100, 90)) + " selecting=" + e.view.isSelectingText());
                e.tick(600);
                t("  settled selecting=" + e.view.isSelectingText() + " out=" + e.drain());
            });
            // tap while selecting ends the selection
            long t3 = SystemClock.uptimeMillis();
            step("tap-sel", () -> {
                t("DOWN -> " + e.view.onTouchEvent(me(t3, t3, MotionEvent.ACTION_DOWN, 50, 60)));
                t("UP -> " + e.view.onTouchEvent(me(t3, t3 + 30, MotionEvent.ACTION_UP, 50, 60)));
                e.tick(400);
                t("  selecting=" + e.view.isSelectingText() + " out=" + e.drain());
            });
            // no emulator
            e.view.mEmulator = null;
            step("noemu", () -> t("no emulator touch -> " + e.view.onTouchEvent(me(1, 1, MotionEvent.ACTION_DOWN, 5, 5))));
            e.view.mEmulator = e.emu;
        }
    }

    @Test public void mouseAndWheel() throws Exception {
        for (int variant = 0; variant < 3; variant++) {
            Env e = newEnv(80, 24, false, false, false);
            seedHistory(e, 100);
            if (variant == 1) e.feed("\u001b[?1049h");
            if (variant == 2) e.feed("\u001b[?1000h\u001b[?1002h\u001b[?1006h");
            t("=== variant " + variant);
            long n = SystemClock.uptimeMillis();
            for (float v : new float[]{1f, -1f, 0.5f, 0f}) {
                step("wheel", () -> t("wheel " + v + " -> " + e.view.onGenericMotionEvent(meFull(n, n, MotionEvent.ACTION_SCROLL, 40, 70, InputDevice.SOURCE_MOUSE, 0, v))
                        + " top=" + e.view.getTopRow() + " out=" + e.drain()));
            }
            step("wheel-finger", () -> t("wheel from touch source -> " + e.view.onGenericMotionEvent(meFull(n, n, MotionEvent.ACTION_SCROLL, 40, 70, InputDevice.SOURCE_TOUCHSCREEN, 0, 1f))));
            step("hover", () -> t("hover move -> " + e.view.onGenericMotionEvent(meFull(n, n, MotionEvent.ACTION_HOVER_MOVE, 40, 70, InputDevice.SOURCE_MOUSE, 0, 0f))));
            // mouse buttons
            for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP}) {
                for (int buttons : new int[]{MotionEvent.BUTTON_PRIMARY, MotionEvent.BUTTON_SECONDARY, MotionEvent.BUTTON_TERTIARY, 0}) {
                    if (buttons == MotionEvent.BUTTON_TERTIARY) {
                        ClipboardManager cm = (ClipboardManager) e.act.getSystemService(Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(ClipData.newPlainText("x", "pasted text"));
                    }
                    MotionEvent ev = meFull(n, n + 5, action, 33, 55, InputDevice.SOURCE_MOUSE, buttons, 0f);
                    step("mouse", () -> t("mouse a=" + MotionEvent.actionToString(action) + " buttons=" + buttons + " -> " + e.view.onTouchEvent(ev) + " top=" + e.view.getTopRow()
                            + " selecting=" + e.view.isSelectingText() + " out=" + e.drain()));
                    ev.recycle();
                }
            }
            // columns and rows of positions, with and without scroll
            e.view.setTopRow(-5);
            for (float[] p : new float[][]{{0, 0}, {7.9f, 3f}, {8f, 19f}, {100.5f, 200.25f}, {639f, 380f}, {-5f, -5f}, {1000f, 1000f}}) {
                MotionEvent ev = me(n, n, MotionEvent.ACTION_MOVE, p[0], p[1]);
                t("columnAndRow " + Arrays.toString(p) + " scroll=" + Arrays.toString(e.view.getColumnAndRow(ev, true)) + " noscroll=" + Arrays.toString(e.view.getColumnAndRow(ev, false)));
                ev.recycle();
            }
        }
    }

    @Test public void recognizerStandalone() throws Exception {
        Env e = newEnv(80, 24, false, false, false);
        Class<?> rc = Class.forName("com.termux.view.GestureAndScaleRecognizer");
        Class<?> lc = Class.forName("com.termux.view.GestureAndScaleRecognizer$Listener");
        Constructor<?> ctor = rc.getDeclaredConstructor(Context.class, lc);
        ctor.setAccessible(true);
        Rec lrec = new Rec(trace, "listener");
        lrec.ret.put("onUp", false);
        Object rec = ctor.newInstance(e.act, proxy(lc, lrec));
        Method onTouch = rc.getDeclaredMethod("onTouchEvent", MotionEvent.class);
        onTouch.setAccessible(true);
        Method inProgress = rc.getDeclaredMethod("isInProgress");
        inProgress.setAccessible(true);
        Field after = findField(rc, "isAfterLongPress");
        Body[] none = {};
        // single tap
        long t0 = SystemClock.uptimeMillis();
        feedRec(e, onTouch, inProgress, after, rec, "tap", new MotionEvent[]{me(t0, t0, 0, 50, 50), me(t0, t0 + 40, 1, 50, 50)}, new long[]{0, 40});
        e.tick(500);
        // double tap
        long t1 = SystemClock.uptimeMillis();
        feedRec(e, onTouch, inProgress, after, rec, "double tap", new MotionEvent[]{me(t1, t1, 0, 50, 50), me(t1, t1 + 40, 1, 50, 50), me(t1 + 100, t1 + 100, 0, 52, 51), me(t1 + 100, t1 + 140, 1, 52, 51)}, new long[]{0, 40, 100, 140});
        e.tick(500);
        // scroll
        long t2 = SystemClock.uptimeMillis();
        feedRec(e, onTouch, inProgress, after, rec, "scroll", new MotionEvent[]{me(t2, t2, 0, 50, 50), me(t2, t2 + 10, 2, 50, 60), me(t2, t2 + 20, 2, 50, 90), me(t2, t2 + 30, 2, 52, 140), me(t2, t2 + 40, 1, 52, 140)}, new long[]{0, 10, 20, 30, 40});
        e.tick(500);
        // long press, then movement, then up
        long t3 = SystemClock.uptimeMillis();
        feedRec(e, onTouch, inProgress, after, rec, "long press", new MotionEvent[]{me(t3, t3, 0, 50, 50)}, new long[]{0});
        e.tick(1500);
        t("  afterLongPress=" + after.get(rec) + " inProgress=" + inProgress.invoke(rec));
        feedRec(e, onTouch, inProgress, after, rec, "after long press", new MotionEvent[]{me(t3, t3 + 1500, 2, 60, 70), me(t3, t3 + 1510, 1, 60, 70)}, new long[]{1500, 1510});
        t("  afterLongPress=" + after.get(rec));
        // pinch
        long t4 = SystemClock.uptimeMillis();
        MotionEvent.PointerProperties[] p2 = {new MotionEvent.PointerProperties(), new MotionEvent.PointerProperties()};
        p2[0].id = 0; p2[1].id = 1;
        p2[0].toolType = MotionEvent.TOOL_TYPE_FINGER; p2[1].toolType = MotionEvent.TOOL_TYPE_FINGER;
        List<MotionEvent> pinch = new ArrayList<>();
        List<Long> pinchT = new ArrayList<>();
        float[][] pos = {{100, 100, 200, 200}, {90, 90, 210, 210}, {70, 70, 230, 230}, {60, 60, 240, 240}, {80, 80, 220, 220}};
        pinch.add(me(t4, t4, 0, 100, 100));
        pinchT.add(0L);
        for (int i = 0; i < pos.length; i++) {
            MotionEvent.PointerCoords[] pc = {new MotionEvent.PointerCoords(), new MotionEvent.PointerCoords()};
            pc[0].x = pos[i][0]; pc[0].y = pos[i][1]; pc[1].x = pos[i][2]; pc[1].y = pos[i][3];
            pc[0].pressure = 1f; pc[1].pressure = 1f; pc[0].size = 1f; pc[1].size = 1f;
            int action = i == 0 ? (MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT)) : MotionEvent.ACTION_MOVE;
            pinch.add(MotionEvent.obtain(t4, t4 + 20L * (i + 1), action, 2, p2, pc, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0));
            pinchT.add(20L * (i + 1));
        }
        MotionEvent.PointerCoords[] pc1 = {new MotionEvent.PointerCoords()};
        pc1[0].x = 80; pc1[0].y = 80; pc1[0].pressure = 1f; pc1[0].size = 1f;
        MotionEvent.PointerProperties[] pp1 = {p2[0]};
        pinch.add(MotionEvent.obtain(t4, t4 + 200, MotionEvent.ACTION_UP, 1, pp1, pc1, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0));
        pinchT.add(200L);
        long[] pt = new long[pinchT.size()];
        for (int i = 0; i < pt.length; i++) pt[i] = pinchT.get(i);
        feedRec(e, onTouch, inProgress, after, rec, "pinch", pinch.toArray(new MotionEvent[0]), pt);
        e.tick(500);
    }

    private void feedRec(Env e, Method onTouch, Method inProgress, Field after, Object rec, String label, MotionEvent[] events, long[] times) throws Exception {
        t("--- " + label);
        for (int i = 0; i < events.length; i++) {
            Object r;
            try { r = onTouch.invoke(rec, events[i]); } catch (InvocationTargetException ex) { r = "EXC:" + ex.getCause().getClass().getName(); }
            t("  event " + fmt(events[i]) + " -> " + r + " inProgress=" + inProgress.invoke(rec));
            e.tick(i + 1 < times.length ? Math.max(1, times[i + 1] - times[i]) : 1);
        }
    }

    // ================================================================================================ 5. text selection
    private TextSelectionAccess sel(Env e) throws Exception {
        return new TextSelectionAccess(getField(e.view, "mTextSelectionCursorController"));
    }

    private final class TextSelectionAccess {
        final Object c;
        TextSelectionAccess(Object c) { this.c = c; }
    }

    @Test public void selection() throws Exception {
        Env e = newEnv(40, 12, false, false, false);
        e.feed("hello world foo_bar baz\r\n中文 mixed 😀 text here\r\n  indented   spaces  \r\nlast line of text\r\n");
        for (int i = 0; i < 30; i++) e.feed("history line number " + i + "\r\n");
        e.view.onScreenUpdated();
        t("view state: focusable=" + e.view.isFocusable() + " touchFocusable=" + e.view.isFocusableInTouchMode() + " inTouchMode=" + e.view.isInTouchMode()
                + " shown=" + e.view.isShown() + " attached=" + e.view.isAttachedToWindow() + " size=" + e.view.getWidth() + "x" + e.view.getHeight());
        float[][] starts = {{20, 30}, {100, 50}, {3, 3}, {300, 150}, {0, 0}, {45, 100}, {500, 500}};
        for (float[] s : starts) {
            t("=== selection start at " + Arrays.toString(s) + " top=" + e.view.getTopRow());
            e.callbacks.clear();
            step("start", () -> e.view.startTextSelectionMode(me(1, 1, MotionEvent.ACTION_DOWN, s[0], s[1])));
            t("  selecting=" + e.view.isSelectingText() + " selected=" + sel(e) + " stored=" + stored(e));
            int[] selectors = new int[4];
            step("selectors", () -> {
                Object c = getField(e.view, "mTextSelectionCursorController");
                callAny(c, "getSelectors", (Object) selectors);
                t("  selectors=" + Arrays.toString(selectors) + " actionMode=" + (callAny(c, "getActionMode") != null) + " active=" + callAny(c, "isActive"));
            });
            step("rect", () -> {
                if (!e.callbacks.isEmpty() && e.callbacks.get(0) instanceof ActionMode.Callback2) {
                    Rect r = new Rect();
                    ((ActionMode.Callback2) e.callbacks.get(0)).onGetContentRect(e.lastMode, e.view, r);
                    t("  contentRect=" + r.toShortString());
                }
            });
            // moving the handles
            step("drag", () -> {
                Object c = getField(e.view, "mTextSelectionCursorController");
                Object startH = getField(c, "mStartHandle"), endH = getField(c, "mEndHandle");
                int[][] moves = {{10, 10}, {200, 30}, {5, 80}, {300, 60}, {-10, 5}, {120, 400}, {40, 20}};
                for (int i = 0; i < moves.length; i++) {
                    Object h = i % 2 == 0 ? startH : endH;
                    Object r = callAny(c, "updatePosition", h, moves[i][0], moves[i][1]);
                    int[] sl = new int[4];
                    callAny(c, "getSelectors", (Object) sl);
                    t("  updatePosition(" + (i % 2 == 0 ? "start" : "end") + "," + moves[i][0] + "," + moves[i][1] + ") -> " + fmt(r) + " selectors=" + Arrays.toString(sl)
                            + " selected=" + sel(e) + " startDragged=" + callAny(c, "isSelectionStartDragged") + " endDragged=" + callAny(c, "isSelectionEndDragged"));
                }
            });
            // scroll output while selecting
            step("scroll", () -> {
                for (int i = 0; i < 3; i++) {
                    e.feed("new output " + i + "\r\n");
                    e.view.onScreenUpdated();
                    t("  after output " + i + " selecting=" + e.view.isSelectingText() + " top=" + e.view.getTopRow() + " selected=" + sel(e));
                }
            });
            // action items
            if (!e.callbacks.isEmpty() && e.lastMode != null && e.view.isSelectingText()) {
                final ActionMode.Callback cb = e.callbacks.get(0);
                final FakeMode mode = e.lastMode;
                for (MenuItem item : mode.items) {
                    t("  menu item " + item.getItemId() + " " + item.getTitle());
                }
                Object c = getField(e.view, "mTextSelectionCursorController");
                int copy = (Integer) getField(c, "ACTION_COPY"), paste = (Integer) getField(c, "ACTION_PASTE"), more = (Integer) getField(c, "ACTION_MORE");
                int which = (int) (s[0] + s[1]) % 4;
                final int id = which == 0 ? copy : which == 1 ? paste : which == 2 ? more : 99;
                MenuItem target = null;
                for (MenuItem it : mode.items) if (it.getItemId() == id) target = it;
                if (target == null) target = newItem(id, "synthetic");
                final MenuItem tgt = target;
                step("click", () -> t("  onActionItemClicked(" + id + ") -> " + cb.onActionItemClicked(mode, tgt)));
                t("  after click selecting=" + e.view.isSelectingText() + " stored=" + stored(e));
                step("closed", () -> { e.view.onContextMenuClosed(null); t("  after context menu closed stored=" + stored(e)); });
                step("destroy", () -> { cb.onDestroyActionMode(mode); t("  after onDestroyActionMode selecting=" + e.view.isSelectingText()); });
            }
            step("stop", () -> { e.view.stopTextSelectionMode(); t("  after stop selecting=" + e.view.isSelectingText()); });
        }
        // the 300 ms guard of hide(): a selection that was just started cannot be closed by a touch immediately
        t("=== quick hide");
        step("quick", () -> {
            e.view.startTextSelectionMode(me(1, 1, MotionEvent.ACTION_DOWN, 20, 30));
            t("  started selecting=" + e.view.isSelectingText());
            e.view.stopTextSelectionMode();
            t("  stopped at once selecting=" + e.view.isSelectingText());
            SystemClock.sleep(400);
            Thread.sleep(400);
            e.view.stopTextSelectionMode();
            t("  stopped later selecting=" + e.view.isSelectingText());
        });
        // floating toolbar visibility while the finger moves
        t("=== toolbar");
        step("toolbar", () -> {
            e.view.startTextSelectionMode(me(1, 1, MotionEvent.ACTION_DOWN, 20, 30));
            for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL}) {
                e.view.updateFloatingToolbarVisibility(me(1, 1, action, 20, 30));
                t("  toolbar a=" + action);
                e.tick(100);
                e.tick(400);
            }
            e.view.stopTextSelectionMode();
        });
        // selection keeps text visible: onScreenUpdated at the end of history stops it
        t("=== end of history");
        step("history", () -> {
            e.view.setTopRow(-3);
            e.view.startTextSelectionMode(me(1, 1, MotionEvent.ACTION_DOWN, 20, 30));
            for (int i = 0; i < 80; i++) {
                e.feed("x" + i + "\r\n");
                e.view.onScreenUpdated();
                if (i % 10 == 0 || !e.view.isSelectingText()) t("  out " + i + " selecting=" + e.view.isSelectingText() + " top=" + e.view.getTopRow());
                if (!e.view.isSelectingText()) break;
            }
        });
    }

    // ================================================================================================ 6. coordinates, scroll metrics, resize helpers
    @Test public void metricsAndCoordinates() throws Exception {
        Env e = newEnv(80, 24, false, false, false);
        t("empty view metrics: range=" + callAny(e.view, "computeVerticalScrollRange") + " extent=" + callAny(e.view, "computeVerticalScrollExtent") + " offset=" + callAny(e.view, "computeVerticalScrollOffset"));
        seedHistory(e, 150);
        for (int top : new int[]{0, -1, -17, -100, -1000, 5}) {
            e.view.setTopRow(top);
            t("topRow=" + top + " -> getTopRow=" + e.view.getTopRow() + " visible=" + e.view.getVisibleTopRow() + " range=" + callAny(e.view, "computeVerticalScrollRange")
                    + " extent=" + callAny(e.view, "computeVerticalScrollExtent") + " offset=" + callAny(e.view, "computeVerticalScrollOffset"));
            e.view.onScreenUpdated();
            t("   after onScreenUpdated top=" + e.view.getTopRow());
        }
        for (float x : new float[]{-3f, 0f, 7.99f, 8f, 100.4f, 639.9f, 5000f}) t("getCursorX(" + x + ")=" + e.view.getCursorX(x));
        for (float y : new float[]{-3f, 0f, 2.9f, 3f, 18.99f, 19f, 200.2f, 5000f}) t("getCursorY(" + y + ")=" + e.view.getCursorY(y));
        for (int c : new int[]{-1, 0, 1, 39, 79, 200}) t("getPointX(" + c + ")=" + e.view.getPointX(c));
        e.view.setTopRow(-7);
        for (int r : new int[]{-10, -7, -1, 0, 1, 23, 40}) t("getPointY(" + r + ")=" + e.view.getPointY(r));
        step("setTextSize", () -> e.view.setTextSize(20));
        t("after setTextSize(20): renderer font=" + e.view.mRenderer.getFontWidth() + "x" + e.view.mRenderer.getFontLineSpacing() + " jni=" + NativePty.calls);
        fixRenderer(e.view.mRenderer);
        step("typeface", () -> e.view.setTypeface(Typeface.SERIF));
        fixRenderer(e.view.mRenderer);
        t("after setTypeface: renderer font=" + e.view.mRenderer.getFontWidth() + " jni=" + NativePty.calls);
        e.geometry(60, 20);
        t("after geometry 60x20: rows=" + e.emu.mRows + " cols=" + e.emu.mColumns + " jni=" + NativePty.calls);
        // attach another session
        TerminalSession other = new TerminalSession("unused", "unused", new String[0], new String[0], 2000, e.sclient);
        t("attach same -> " + e.view.attachSession(e.session));
        step("attach other", () -> t("attach other -> " + e.view.attachSession(other)));
        t("attach null -> " + e.view.attachSession(null) + " top=" + e.view.getTopRow() + " current=" + (e.view.getCurrentSession() == null));
        e.view.updateSize();
        e.view.mRenderer = null;
        step("updateSize no renderer", () -> { e.view.updateSize(); t("updateSize without renderer ok"); });
        t("opaque=" + e.view.isOpaque() + " textEditor=" + e.view.onCheckIsTextEditor() + " softkb=" + e.view.isSoftKeyboardInputEnabled());
    }

    @Test public void accessibilityText() throws Exception {
        Env e = newEnv(30, 6, false, false, true);
        e.feed("first row\r\nsecond 中文 row\r\n\r\nfourth");
        e.view.onScreenUpdated();
        t("contentDescription=" + fmt(e.view.getContentDescription()));
        seedHistory(e, 12);
        t("contentDescription after history=" + fmt(e.view.getContentDescription()));
    }

    @Test public void cursorBlinker() throws Exception {
        Env e = newEnv(40, 10, false, false, false);
        for (int rate : new int[]{-5, 0, 50, 99, 100, 101, 500, 1999, 2000, 2001, 100000}) {
            t("setTerminalCursorBlinkerRate(" + rate + ") -> " + e.view.setTerminalCursorBlinkerRate(rate));
        }
        t("rate 500 -> " + e.view.setTerminalCursorBlinkerRate(500));
        for (boolean[] a : new boolean[][]{{true, false}, {true, true}, {false, false}, {false, true}}) {
            e.view.setTerminalCursorBlinkerState(a[0], a[1]);
            t("state(" + a[0] + "," + a[1] + ")");
            for (int i = 0; i < 5; i++) {
                e.tick(250);
                t("  tick " + i + " cursorVisible=" + e.emu.shouldCursorBeVisible() + " enabled=" + e.emu.isCursorEnabled());
            }
        }
        e.view.setTerminalCursorBlinkerState(true, false);
        e.tick(600);
        t("blinking, change rate: " + e.view.setTerminalCursorBlinkerRate(200));
        for (int i = 0; i < 4; i++) { e.tick(100); t("  tick " + i + " cursorVisible=" + e.emu.shouldCursorBeVisible()); }
        e.feed("\u001b[?25l");
        e.view.setTerminalCursorBlinkerState(true, true);
        e.tick(400);
        t("cursor hidden, start only if enabled: visible=" + e.emu.shouldCursorBeVisible());
        e.feed("\u001b[?25h");
        e.view.setTerminalCursorBlinkerState(true, true);
        for (int i = 0; i < 4; i++) { e.tick(120); t("  tick " + i + " cursorVisible=" + e.emu.shouldCursorBeVisible()); }
        // detach and attach stop/restart the blinker
        step("detach", () -> callAny(e.view, "onDetachedFromWindow"));
        e.tick(500);
        t("after detach visible=" + e.emu.shouldCursorBeVisible());
        step("attach", () -> callAny(e.view, "onAttachedToWindow"));
        for (int i = 0; i < 3; i++) { e.tick(250); t("  after attach tick " + i + " visible=" + e.emu.shouldCursorBeVisible()); }
        e.view.setTerminalCursorBlinkerState(false, false);
        e.tick(1000);
        t("stopped: visible=" + e.emu.shouldCursorBeVisible());
        // key press shows the cursor
        e.view.setTerminalCursorBlinkerState(true, false);
        e.tick(260);
        step("key", () -> e.view.handleKeyCode(KeyEvent.KEYCODE_A, 0));
        t("after handleKeyCode visible=" + e.emu.shouldCursorBeVisible());
    }

    @Test public void autofill() throws Exception {
        Env e = newEnv(40, 10, false, false, false);
        AutofillManager afm = e.act.getSystemService(AutofillManager.class);
        t("autofill manager available=" + (afm != null));
        if (afm != null) Shadows.shadowOf(afm).setEnabled(true);
        t("type=" + e.view.getAutofillType() + " importance=" + e.view.getImportantForAutofill() + " hints=" + fmt(e.view.getAutofillHints()) + " value=" + fmt(e.view.getAutofillValue()));
        t("enabled=" + e.view.isAutoFillEnabled() + " manager=" + (e.view.getAutoFillManagerService() != null));
        step("user", () -> e.view.requestAutoFillUsername());
        t("after username request: type=" + e.view.getAutofillType() + " importance=" + e.view.getImportantForAutofill() + " hints=" + fmt(e.view.getAutofillHints()));
        step("pass", () -> e.view.requestAutoFillPassword());
        step("custom", () -> e.view.requestAutoFill(new String[]{"a", "b"}));
        step("empty", () -> e.view.requestAutoFill(new String[0]));
        step("null", () -> e.view.requestAutoFill(null));
        t("after requests: type=" + e.view.getAutofillType() + " hints=" + fmt(e.view.getAutofillHints()));
        step("cancel", () -> e.view.cancelRequestAutoFill());
        t("after cancel: type=" + e.view.getAutofillType() + " hints=" + fmt(e.view.getAutofillHints()));
        step("fill", () -> e.view.autofill(AutofillValue.forText("s3crét")));
        t("after autofill: type=" + e.view.getAutofillType() + " importance=" + e.view.getImportantForAutofill() + " hints=" + fmt(e.view.getAutofillHints()) + " out=" + e.drain());
        step("fill-list", () -> e.view.autofill(AutofillValue.forList(1)));
        t("after non-text autofill out=" + e.drain());
    }

    // ================================================================================================ 7. stable resize sampler (same logic as the existing tests, but traced)
    @Test public void stableResizeTrace() throws Exception {
        Env e = newEnv(80, 35, true, true, false);
        seedHistory(e, 120);
        e.view.setTopRow(-12);
        t("start top=" + e.view.getTopRow() + " visible=" + e.view.getVisibleTopRow());
        for (int rows : new int[]{32, 28, 23, 19}) {
            e.geometry(80, rows);
            e.tick(30);
            t("geometry " + rows + " -> emuRows=" + e.emu.mRows + " top=" + e.view.getTopRow() + " visible=" + e.view.getVisibleTopRow() + " jni=" + NativePty.calls);
        }
        e.tick(200);
        t("settled emuRows=" + e.emu.mRows + " top=" + e.view.getTopRow() + " jni=" + NativePty.calls);
        e.geometry(70, 19);
        e.tick(200);
        t("columns changed emuCols=" + e.emu.mColumns + " top=" + e.view.getTopRow() + " jni=" + NativePty.calls);
        e.view.setWorkspaceTerminalVisible(false);
        e.geometry(70, 25);
        e.tick(500);
        t("hidden resize emuRows=" + e.emu.mRows + " jni=" + NativePty.calls);
        e.view.setWorkspaceTerminalVisible(true);
        e.tick(200);
        t("shown again emuRows=" + e.emu.mRows + " jni=" + NativePty.calls);
        e.geometry(70, 12);
        MotionEvent ev = me(1, 1, MotionEvent.ACTION_DOWN, 4, 40);
        t("touch applies pending geometry -> " + e.view.onTouchEvent(ev) + " emuRows=" + e.emu.mRows + " jni=" + NativePty.calls);
        ev.recycle();
        e.geometry(70, 14);
        e.view.updateSize();
        e.tick(500);
        t("after updateSize emuRows=" + e.emu.mRows + " jni=" + NativePty.calls);
        final long[] listenerCalls = {0};
        e.view.setTranscriptTapListener(line -> { listenerCalls[0]++; t("  tap listener line=" + fmt(line)); return listenerCalls[0] % 2 == 1; });
        e.feed("\u001b[?1000h");
        long now = SystemClock.uptimeMillis();
        for (int i = 0; i < 3; i++) {
            step("tap" + i, () -> {
                e.view.onTouchEvent(me(now, now, MotionEvent.ACTION_DOWN, 8, 24));
                e.view.onTouchEvent(me(now, now + 20, MotionEvent.ACTION_UP, 8, 24));
            });
            t("tap " + i + " listenerCalls=" + listenerCalls[0] + " out=" + e.drain());
        }
        e.view.setTranscriptTapListener(null);
    }
}
