package com.termux.terminal;

import android.os.Handler;

import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/**
 * Session-level harness: real TerminalSession objects talk to real processes through a real PTY
 * (the native library is chosen with -Djava.library.path). Public API only, so the very same compiled
 * harness runs against the Java original and against the Kotlin port.
 *
 * TerminalSession closes the pty master as soon as the child has exited, so output that is still unread at that
 * moment is lost (upstream design, same in both implementations). To keep the comparison deterministic every
 * scenario synchronises: the child prints a marker and waits for a line from the harness before it exits.
 * Scenarios that are about the exit itself (failed exec, burst before exit) only compare the exit state.
 */
public class SesH {
    static PrintStream out;
    static final String CWD = System.getProperty("ses.cwd", "/tmp");

    // ------------------------------------------------------------------ helpers
    static String sha(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    static String q(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x20 && c < 0x7f && c != '"' && c != '\\') sb.append(c);
            else if (c == '\n') sb.append("\\n");
            else sb.append(String.format("\\u%04x", (int) c));
        }
        return sb.append('"').toString();
    }

    static String firstLine(String s) {
        if (s == null) return null;
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }

    static String exc(Throwable t) { return t.getClass().getName(); }

    static String shortText(String s) {
        if (s.length() <= 1500) return q(s);
        String[] lines = s.split("\n", -1);
        return "lines=" + lines.length + " sha=" + sha(s) + " first=" + q(lines[0]) + " last2=" + q(lines.length > 1 ? lines[lines.length - 2] : "") + "|" + q(lines[lines.length - 1]);
    }

    static String lastNonEmptyLine(String s) {
        if (s == null) return "null";
        String[] lines = s.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) if (!lines[i].isEmpty()) return lines[i];
        return "";
    }

    // ------------------------------------------------------------------ client
    static final class Cli implements TerminalSessionClient {
        final List<String> events = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch finished = new CountDownLatch(1);
        volatile boolean sawText;
        final Integer cursorStyle;

        Cli(Integer cursorStyle) { this.cursorStyle = cursorStyle; }

        @Override public void onTextChanged(TerminalSession s) { sawText = true; }
        @Override public void onTitleChanged(TerminalSession s) { events.add("title:" + s.getTitle()); }
        @Override public void onSessionFinished(TerminalSession s) { events.add("finished"); finished.countDown(); }
        @Override public void onCopyTextToClipboard(TerminalSession s, String text) { events.add("copy:" + q(text)); }
        @Override public void onPasteTextFromClipboard(TerminalSession s) { events.add("paste"); }
        @Override public void onBell(TerminalSession s) { events.add("bell"); }
        @Override public void onColorsChanged(TerminalSession s) { events.add("colors"); }
        @Override public void onTerminalCursorStateChange(boolean state) { events.add("cursorstate:" + state); }
        @Override public Integer getTerminalCursorStyle() { return cursorStyle; }
        @Override public void logError(String tag, String message) { events.add("logE:" + tag + ":" + q(firstLine(message))); }
        @Override public void logWarn(String tag, String message) { events.add("logW:" + tag + ":" + q(firstLine(message))); }
        @Override public void logInfo(String tag, String message) { events.add("logI:" + tag + ":" + q(firstLine(message))); }
        @Override public void logDebug(String tag, String message) { }
        @Override public void logVerbose(String tag, String message) { }
        @Override public void logStackTraceWithMessage(String tag, String message, Exception e) { events.add("logST:" + tag + ":" + q(firstLine(message)) + ":" + exc(e)); }
        @Override public void logStackTrace(String tag, Exception e) { events.add("logST2:" + tag + ":" + exc(e)); }
    }

    // ------------------------------------------------------------------ one session
    interface Body { void run(Ctx c) throws Exception; }

    static final class Ctx {
        final TerminalSession s;
        final Cli cli;
        Ctx(TerminalSession s, Cli cli) { this.s = s; this.cli = cli; }

        void start(int cols, int rows) { Handler.runOnMain(() -> s.updateSize(cols, rows, 8, 16)); }
        void main(Runnable r) { Handler.runOnMain(r); }
        boolean waitFinished(long ms) throws InterruptedException { return cli.finished.await(ms, TimeUnit.MILLISECONDS); }
        String screen() {
            String[] r = new String[1];
            Handler.runOnMain(() -> { TerminalEmulator e = s.getEmulator(); r[0] = e == null ? null : e.getScreen().getTranscriptText(); });
            return r[0];
        }
        boolean waitText(String sub, long ms) throws InterruptedException {
            long end = System.currentTimeMillis() + ms;
            while (System.currentTimeMillis() < end) {
                String t = screen();
                if (t != null && t.contains(sub)) return true;
                Thread.sleep(10);
            }
            return false;
        }
        void need(String sub) throws InterruptedException {
            if (!waitText(sub, 15000)) throw new IllegalStateException("never saw " + q(sub));
        }
        void write(String text) { byte[] b = text.getBytes(StandardCharsets.UTF_8); Handler.runOnMain(() -> s.write(b, 0, b.length)); }
        /** Wait for the marker, then let the child go on (it is blocked in `read`). */
        void release(String marker) throws InterruptedException { need(marker); write("\r"); }
        void finish() throws InterruptedException { if (!waitFinished(20000)) throw new IllegalStateException("not finished"); }
    }

    static String describe(Ctx c, boolean statusOnly) {
        String screen = c.screen();
        StringBuilder sb = new StringBuilder();
        int pid = c.s.getPid();
        sb.append("running=").append(c.s.isRunning()).append(" pid=").append(pid > 0 ? "+" : String.valueOf(pid));
        sb.append(" exit=").append(c.s.getExitStatus());
        sb.append(" title=").append(q(c.s.getTitle()));
        sb.append(" text=").append(c.cli.sawText);
        sb.append(" events=").append(c.cli.events);
        if (statusOnly) sb.append(" status=").append(q(lastNonEmptyLine(screen)));
        else sb.append(" screen=").append(screen == null ? "null" : shortText(screen));
        return sb.toString();
    }

    static void scenario(String name, boolean statusOnly, String shell, String cwd, String[] args, String[] env, Integer transcriptRows, Integer cursorStyle, Body body) {
        String res;
        Cli cli = new Cli(cursorStyle);
        TerminalSession s = new TerminalSession(shell, cwd, args, env, transcriptRows, cli);
        Ctx c = new Ctx(s, cli);
        try {
            body.run(c);
            res = describe(c, statusOnly);
        } catch (Throwable t) {
            res = "EXC " + exc(t) + " " + q(firstLine(String.valueOf(t.getMessage()))) + " | " + describe(c, statusOnly);
        }
        out.println(name + " => " + res);
        out.flush();
        try { s.finishIfRunning(); } catch (Throwable ignored) { }
    }

    /** The tty echo of the line that releases the child can still be unread when the master is closed at exit, so the echo is switched off. */
    static String[] sh(String script) { return new String[]{"sh", "-c", "stty -echo; " + script}; }
    static final String[] ENV = {"PATH=/usr/bin:/bin", "TERM=xterm-256color", "FOO=bar"};
    /** child tail: tell the harness we are done, wait for its line */
    static final String DONE = "printf 'DONE\\n'; read z";

    /** start, wait for DONE, release the child, wait for the exit */
    static final Body RUN_TO_DONE = c -> { c.start(40, 12); c.release("DONE"); c.finish(); };

    // ------------------------------------------------------------------ main
    public static void main(String[] argv) throws Exception {
        out = new PrintStream(new java.io.BufferedOutputStream(System.out, 1 << 16), false, "UTF-8");
        new File(CWD).mkdirs();

        // exit codes and signals (the child waits until the harness has seen its output)
        scenario("s01_exit0", false, "/bin/sh", CWD, sh("echo hello; echo err >&2; " + DONE + "; exit 0"), ENV, null, null, RUN_TO_DONE);
        scenario("s02_exit7", false, "/bin/sh", CWD, sh("echo x; " + DONE + "; exit 7"), ENV, null, null, RUN_TO_DONE);
        scenario("s03_sigkill", false, "/bin/sh", CWD, sh(DONE + "; kill -9 $$"), ENV, null, null, RUN_TO_DONE);
        scenario("s03b_sigterm", false, "/bin/sh", CWD, sh(DONE + "; kill -TERM $$"), ENV, null, null, RUN_TO_DONE);
        scenario("s03c_exit255_256", false, "/bin/sh", CWD, sh("(exit 255); printf 'a=%s\\n' $?; (exit 256); printf 'b=%s\\n' $?; " + DONE + "; exit 255"), ENV, null, null, RUN_TO_DONE);
        // finishIfRunning kills the shell
        scenario("s04_finish_if_running", false, "/bin/sh", CWD, sh("printf READY\\\\n; sleep 30"), ENV, null, null, c -> {
            c.start(40, 12);
            c.need("READY");
            c.main(() -> c.s.finishIfRunning());
            c.finish();
            c.main(() -> c.s.finishIfRunning()); // no-op after exit
        });
        // UTF-8 input through write() and writeCodePoint()
        scenario("s05_input", false, "/bin/sh", CWD, sh("stty -echo; printf 'READY\\n'; read x; printf 'got:%s\\n' \"$x\"; read y; printf 'got2:%s\\n' \"$y\"; " + DONE), ENV, null, null, c -> {
            c.start(60, 12);
            c.need("READY");
            c.write("héllo");
            c.main(() -> { c.s.writeCodePoint(false, 0x20AC); c.s.writeCodePoint(false, 0x1F600); c.s.writeCodePoint(false, 'z'); });
            c.write("\r");
            c.need("got:");
            c.main(() -> c.s.writeCodePoint(false, 'q'));
            c.write("\r");
            c.release("DONE");
            c.finish();
        });
        // window size is propagated to the pty
        scenario("s06_winsize", false, "/bin/sh", CWD, sh("stty -echo; stty size; printf 'READY\\n'; read x; stty size; printf 'READY2\\n'; read y; stty size; " + DONE), ENV, null, null, c -> {
            c.start(40, 12);
            c.need("READY");
            c.main(() -> c.s.updateSize(50, 20, 8, 16));
            c.write("\r");
            c.need("READY2");
            c.main(() -> c.s.updateSize(33, 7, 5, 9));
            c.write("\r");
            c.release("DONE");
            c.finish();
            int[] dims = new int[2];
            c.main(() -> { dims[0] = c.s.getEmulator().mColumns; dims[1] = c.s.getEmulator().mRows; });
            out.println("  s06 emulator size " + dims[0] + "x" + dims[1]);
        });
        // environment and working directory
        scenario("s07_env_cwd", false, "/bin/sh", CWD, sh("printf 'cwd=%s\\n' \"$PWD\"; printf 'FOO=%s\\n' \"$FOO\"; printf 'BAR=%s\\n' \"${BAR-unset}\"; printf 'TERM=%s\\n' \"$TERM\"; printf 'HOME=%s\\n' \"${HOME-unset}\"; " + DONE), ENV, null, null, RUN_TO_DONE);
        scenario("s07b_empty_env", false, "/bin/sh", CWD, sh("printf 'FOO=%s\\n' \"${FOO-unset}\"; " + DONE), new String[0], null, null, RUN_TO_DONE);
        scenario("s07c_null_env_args", true, "/bin/echo", CWD, null, null, null, null, c -> { c.start(60, 12); c.finish(); });
        // failing exec / chdir
        scenario("s08_exec_fail", true, "/nonexistent/prog", CWD, new String[]{"prog"}, ENV, null, null, c -> { c.start(60, 12); c.finish(); });
        scenario("s09_chdir_fail", false, "/bin/sh", "/nonexistent/dir", sh("printf 'after\\n'; " + DONE), ENV, null, null, RUN_TO_DONE);
        // title through OSC
        scenario("s10_title", false, "/bin/sh", CWD, sh("printf '\\033]0;mytitle\\007\\033]2;second\\033\\\\'; printf 'READY\\n'; read x; " + DONE), ENV, null, null, c -> {
            c.start(60, 12);
            c.need("READY");
            c.write("\r");
            c.release("DONE");
            c.finish();
        });
        // bell, colors, clipboard
        scenario("s11_bell_colors_clip", false, "/bin/sh", CWD, sh("printf '\\a\\033]4;1;rgb:ff/00/00\\007\\033]52;c;SGVsbG8=\\007\\033]52;c;?\\007\\033]10;#123456\\007'; " + DONE), ENV, null, 2, RUN_TO_DONE);
        // large outputs (flow control between reader thread and main thread)
        scenario("s12_seq3000", false, "/bin/sh", CWD, sh("seq 1 3000; " + DONE), ENV, 5000, null, RUN_TO_DONE);
        scenario("s12b_yes_40000", false, "/bin/sh", CWD, sh("yes abcdefghij | head -n 40000; printf 'END\\n'; " + DONE), ENV, null, null, c -> { c.start(80, 24); c.release("DONE"); c.finish(); });
        scenario("s12c_transcript100", false, "/bin/sh", CWD, sh("seq 1 500; " + DONE), ENV, 100, null, RUN_TO_DONE);
        // reset()
        scenario("s13_reset", false, "/bin/sh", CWD, sh("printf 'AAA\\nBBB\\n'; printf 'READY\\n'; read x; printf 'after\\n'; " + DONE), ENV, null, null, c -> {
            c.start(40, 12);
            c.need("READY");
            c.cli.sawText = false;
            c.main(() -> c.s.reset());
            out.println("  s13 after reset: " + q(c.screen()) + " textNotified=" + c.cli.sawText);
            c.write("\r");
            c.release("DONE");
            c.finish();
        });
        // synchronized frames: output is withheld until ?2026l or the 200 ms timeout
        scenario("s14_sync_frames", false, "/bin/sh", CWD, sh("printf '\\033[?2026hBEGIN\\n'; sleep 1; printf 'END\\033[?2026l\\n'; printf 'READY\\n'; read x; " + DONE), ENV, null, null, c -> {
            c.main(() -> c.s.setSynchronizedFramesEnabled(true));
            c.start(60, 12);
            c.need("BEGIN");
            c.need("READY");
            c.main(() -> c.s.setSynchronizedFramesEnabled(false));
            c.write("\r");
            c.release("DONE");
            c.finish();
        });
        scenario("s14b_sync_flush_on_exit", true, "/bin/sh", CWD, sh("printf '\\033[?2026hHELD'; read x"), ENV, null, null, c -> {
            c.main(() -> c.s.setSynchronizedFramesEnabled(true));
            c.start(60, 12);
            Thread.sleep(60);
            c.write("\r");
            c.finish();
        });
        // a resize flushes a held synchronized frame at once (the 200 ms timeout has not expired yet)
        scenario("s23_sync_flush_on_resize", true, "/bin/sh", CWD, sh("printf 'READY\\n'; printf '\\033[?2026hHELD\\n'; sleep 30"), ENV, null, null, c -> {
            c.main(() -> c.s.setSynchronizedFramesEnabled(true));
            c.start(60, 12);
            c.need("READY");
            Thread.sleep(60);
            c.main(() -> c.s.updateSize(50, 20, 8, 16));
            out.println("  s23 snapshot=" + q(c.screen()));
            c.main(() -> c.s.finishIfRunning());
            c.finish();
        });
        // state before start and after exit
        scenario("s15_states", true, "/bin/sh", CWD, sh("read x; exit 3"), ENV, null, null, c -> {
            StringBuilder sb = new StringBuilder();
            sb.append("before: pid=").append(c.s.getPid()).append(" running=").append(c.s.isRunning()).append(" exit=").append(c.s.getExitStatus())
                .append(" title=").append(c.s.getTitle()).append(" cwd=").append(c.s.getCwd()).append(" emu=").append(c.s.getEmulator());
            c.write("ignored"); // not started: must be ignored
            c.main(() -> c.s.writeCodePoint(false, 'x'));
            try { c.main(() -> c.s.reset()); sb.append(" reset=ok"); } catch (Throwable t) { sb.append(" reset=").append(exc(t)); }
            c.start(40, 12);
            Thread.sleep(100);
            c.write("\r");
            c.finish();
            sb.append(" | after: pid=").append(c.s.getPid()).append(" running=").append(c.s.isRunning()).append(" exit=").append(c.s.getExitStatus()).append(" cwd=").append(c.s.getCwd());
            c.write("ignored after exit");
            c.main(() -> c.s.writeCodePoint(true, 'y'));
            c.main(() -> c.s.finishIfRunning());
            out.println("  s15 " + sb);
        });
        // getCwd of a running session
        scenario("s16_getcwd", false, "/bin/sh", CWD, sh("printf READY\\\\n; sleep 30"), ENV, null, null, c -> {
            c.start(40, 12);
            c.need("READY");
            out.println("  s16 cwd=" + c.s.getCwd() + " pid>0=" + (c.s.getPid() > 0));
            c.main(() -> c.s.finishIfRunning());
            c.finish();
        });
        // the client can be replaced while running
        scenario("s17_swap_client", false, "/bin/sh", CWD, sh("printf 'READY\\n'; read x; printf '\\a'; " + DONE), ENV, null, null, c -> {
            Cli second = new Cli(null);
            c.start(40, 12);
            c.need("READY");
            c.main(() -> c.s.updateTerminalSessionClient(second));
            c.write("\r");
            c.release("DONE");
            if (!second.finished.await(20000, TimeUnit.MILLISECONDS)) throw new IllegalStateException("not finished");
            out.println("  s17 first=" + c.cli.events + " second=" + second.events);
        });
        // bytes produced by writeCodePoint
        scenario("s18_bytes", false, "/bin/sh", CWD, sh("stty -echo -icanon min 1; printf 'READY\\n'; od -An -tx1 -N 17; " + DONE), ENV, null, null, c -> {
            c.start(80, 12);
            c.need("READY");
            StringBuilder errs = new StringBuilder();
            c.main(() -> {
                c.s.writeCodePoint(true, 'x');
                c.s.writeCodePoint(false, 0xE9);
                c.s.writeCodePoint(false, 0x20AC);
                c.s.writeCodePoint(false, 0x1F600);
                c.s.writeCodePoint(true, 0x10FFFF);
                c.s.writeCodePoint(false, 0x7F);
                for (int bad : new int[]{0x110000, 0xD800, 0xDFFF, Integer.MAX_VALUE}) {
                    try { c.s.writeCodePoint(false, bad); errs.append(" ok:").append(bad); } catch (Throwable t) { errs.append(" ").append(exc(t)).append(":").append(q(t.getMessage())); }
                }
            });
            out.println("  s18 errs=" + errs);
            c.need("7f");
            c.write("\r");
            c.release("DONE");
            c.finish();
        });
        // several sessions at once: creation phase, run phase, shutdown phase
        {
            final int N = 6;
            final Cli[] clis = new Cli[N];
            final Ctx[] ctxs = new Ctx[N];
            final String[] results = new String[N];
            List<Thread> ts = new ArrayList<>();
            for (int k = 0; k < N; k++) {
                final int n = k;
                clis[n] = new Cli(null);
                TerminalSession s = new TerminalSession("/bin/sh", CWD, sh("printf 'READY" + n + "\\n'; read x; seq " + (n * 100) + " " + (n * 100 + 150) + "; printf 'DONE" + n + "\\n'; read y; exit " + n), ENV, null, clis[n]);
                ctxs[n] = new Ctx(s, clis[n]);
                Thread t = new Thread(() -> ctxs[n].start(40, 10));
                ts.add(t);
                t.start();
            }
            for (Thread t : ts) t.join();
            try {
                for (int k = 0; k < N; k++) ctxs[k].need("READY" + k);
                for (int k = 0; k < N; k++) ctxs[k].write("\r");
                for (int k = 0; k < N; k++) ctxs[k].need("DONE" + k);
                for (int k = 0; k < N; k++) ctxs[k].write("\r");
                for (int k = 0; k < N; k++) ctxs[k].finish();
                for (int k = 0; k < N; k++) results[k] = describe(ctxs[k], false);
            } catch (Throwable e) { for (int k = 0; k < N; k++) if (results[k] == null) results[k] = "EXC " + exc(e) + " " + q(e.getMessage()); }
            for (int k = 0; k < N; k++) out.println("s19_parallel_" + k + " => " + results[k]);
            out.flush();
        }
        // flags
        scenario("s20_flags", true, "/bin/sh", CWD, sh("read x; exit 0"), ENV, null, null, c -> {
            StringBuilder sb = new StringBuilder();
            sb.append(c.s.preservesTranscriptScroll()).append(c.s.usesStableResize());
            c.s.setPreserveTranscriptScroll(true); c.s.setStableResizeEnabled(true);
            sb.append(c.s.preservesTranscriptScroll()).append(c.s.usesStableResize());
            c.start(40, 12);
            Thread.sleep(100);
            c.write("\r");
            c.finish();
            c.main(() -> c.s.flushFrameBeforeResize());
            out.println("  s20 " + sb + " handleLen=" + c.s.mHandle.length());
        });
        // output that arrives in the same burst as the exit: only the exit state is deterministic
        scenario("s21_exit_burst", true, "/bin/sh", CWD, sh("printf 'a'; printf 'b'; printf 'c\\n'; exit 9"), ENV, null, null, c -> { c.start(40, 12); c.finish(); });
        // cursor style from the client reaches the emulator
        scenario("s22_cursor_style", false, "/bin/sh", CWD, sh("printf '\\033[5 q'; printf 'X'; printf '\\033[?25l'; printf '\\033[?25h'; " + DONE), ENV, null, 1, c -> {
            c.start(40, 12);
            c.release("DONE");
            c.finish();
            int[] st = new int[2];
            c.main(() -> { st[0] = c.s.getEmulator().getCursorStyle(); st[1] = c.s.getEmulator().isCursorEnabled() ? 1 : 0; });
            out.println("  s22 cursorStyle=" + st[0] + " enabled=" + st[1]);
        });

        out.println("HANDLER-ERRORS " + Handler.ERRORS.size());
        for (Throwable t : Handler.ERRORS) out.println("  looper error: " + exc(t) + " " + q(firstLine(String.valueOf(t.getMessage()))));
        out.flush();
        System.exit(0);
    }
}
