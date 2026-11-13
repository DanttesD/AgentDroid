package jnih;

import com.termux.terminal.JNI;

import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Calls com.termux.terminal.JNI directly (the real JNI boundary) with ordinary and unusual arguments. */
public class JniH {
    static PrintStream out;
    static String TOOLS = System.getProperty("jnih.tools", ".");
    static final String CWD = System.getProperty("jnih.cwd", "/tmp");
    static final String[] ENV = {"PATH=/usr/bin:/bin:/data/data/com.termux/files/usr/bin", "TERM=xterm"};
    static final boolean RUST_ONLY_CASES = Boolean.getBoolean("jnih.rust");

    static FileDescriptor fdObject(int fd) throws Exception {
        FileDescriptor d = new FileDescriptor();
        Field f = FileDescriptor.class.getDeclaredField("fd");
        f.setAccessible(true);
        f.setInt(d, fd);
        return d;
    }

    static String q(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x20 && c < 0x7f && c != '"' && c != '\\') sb.append(c);
            else sb.append(String.format("\\x%02x", (int) c & 0xff));
        }
        return sb.append('"').toString();
    }

    static String mask(String s) {
        return s.replaceAll("/dev/pts/[0-9]+", "/dev/pts/N");
    }

    static final class Proc {
        int master, pid;
        String exc;
        int[] pidArray = new int[1];
        StringBuffer collected = new StringBuffer();
        Thread reader;
        volatile boolean eof;
    }

    static Proc start(String cmd, String cwd, String[] args, String[] env, int rows, int cols, int cw, int ch) {
        Proc p = new Proc();
        try {
            p.master = JNI.createSubprocess(cmd, cwd, args, env, p.pidArray, rows, cols, cw, ch);
        } catch (Throwable t) {
            p.exc = t.getClass().getName() + ": " + t.getMessage();
            p.master = -2;
        }
        p.pid = p.pidArray[0];
        if (p.exc == null && p.master >= 0) {
            try {
                final FileInputStream in = new FileInputStream(fdObject(p.master));
                p.reader = new Thread(() -> {
                    byte[] buf = new byte[8192];
                    try {
                        int n;
                        while ((n = in.read(buf)) > 0) p.collected.append(new String(buf, 0, n, StandardCharsets.ISO_8859_1));
                    } catch (IOException e) { /* EIO once the slave is closed */ }
                    p.eof = true;
                });
                p.reader.setDaemon(true);
                p.reader.start();
            } catch (Exception e) { throw new RuntimeException(e); }
        }
        return p;
    }

    static void write(Proc p, String s) throws Exception {
        FileOutputStream o = new FileOutputStream(fdObject(p.master));
        o.write(s.getBytes(StandardCharsets.ISO_8859_1));
        o.flush();
    }

    static boolean waitFor(Proc p, String marker, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (p.collected.indexOf(marker) >= 0) return true;
            Thread.sleep(5);
        }
        return false;
    }

    /** Waits for the process, then collects the remaining output until the reader sees EOF. */
    static String finish(Proc p, long ms, int[] statusOut) throws InterruptedException {
        int[] st = new int[1];
        Thread w = new Thread(() -> st[0] = JNI.waitFor(p.pid));
        w.setDaemon(true);
        w.start();
        w.join(ms);
        if (w.isAlive()) {
            try { Runtime.getRuntime().exec(new String[]{"kill", "-9", String.valueOf(p.pid)}).waitFor(); } catch (Exception ignored) { }
            w.join(5000);
            statusOut[0] = 9999; // timeout marker
        } else {
            statusOut[0] = st[0];
        }
        // the reader ends with EIO after the slave side has been closed
        long end = System.currentTimeMillis() + 3000;
        while (!p.eof && System.currentTimeMillis() < end) Thread.sleep(5);
        String o = p.collected.toString();
        JNI.close(p.master);
        return o;
    }

    static void report(String name, Proc p, String output, int status) {
        out.println(name + " => status=" + status + " pid>0=" + (p.pid > 0) + " master>=0=" + (p.master >= 0) + " out=" + q(mask(output)));
    }

    static void run(String name, String cmd, String cwd, String[] args, String[] env, int rows, int cols, int cw, int ch, String input) {
        try {
            Proc p = start(cmd, cwd, args, env, rows, cols, cw, ch);
            if (p.exc != null) { out.println(name + " => EXC " + p.exc + " master=" + p.master + " pidArray=" + p.pid); return; }
            if (input != null) write(p, input);
            int[] st = new int[1];
            String o = finish(p, 20000, st);
            report(name, p, o, st[0]);
        } catch (Throwable t) {
            out.println(name + " => HARNESS-EXC " + t);
        }
        out.flush();
    }

    static void sh(String name, String script) { run(name, "/bin/sh", CWD, new String[]{"sh", "-c", script}, ENV, 24, 80, 8, 16, null); }

    static String[] times(String s, int n) { String[] a = new String[n]; Arrays.fill(a, s); return a; }

    public static void main(String[] argv) throws Exception {
        out = new PrintStream(new BufferedOutputStream(System.out, 1 << 16), false, "UTF-8");
        String mode = argv.length > 0 ? argv[0] : "all";
        if (mode.equals("exhaust")) { exhaust(); return; }

        sh("j01_basic", "echo hi; exit 3");
        run("j02_args_null", "/bin/echo", CWD, null, ENV, 24, 80, 8, 16, null);
        run("j02b_args_empty", "/bin/echo", CWD, new String[0], ENV, 24, 80, 8, 16, null);
        // an interactive shell: type only after its prompt has appeared, so that the echo cannot overtake the prompt
        try {
            Proc p = start("/bin/sh", CWD, new String[]{"sh"}, ENV, 24, 80, 8, 16);
            long end = System.currentTimeMillis() + 10000;
            while (System.currentTimeMillis() < end && !(p.collected.toString().endsWith("# ") || p.collected.toString().endsWith("$ "))) Thread.sleep(5);
            write(p, "echo from-stdin; exit 5\n");
            int[] st = new int[1];
            report("j03_args_one_sh", p, finish(p, 20000, st).replaceAll("^[#$] ", "PROMPT "), st[0]);
        } catch (Throwable t) { out.println("j03 HARNESS-EXC " + t); }
        run("j04_env_null", "/usr/bin/env", CWD, new String[]{"env"}, null, 24, 80, 8, 16, null);
        run("j04b_env_empty", "/usr/bin/env", CWD, new String[]{"env"}, new String[0], 24, 80, 8, 16, null);
        run("j05_env_values", "/usr/bin/env", CWD, new String[]{"env"}, new String[]{"A=1", "B=two words", "C=", "=x", "D", "E=a=b", "A=override"}, 24, 80, 8, 16, null);
        run("j06_cwd_bad", "/bin/sh", "/nonexistent/dir", new String[]{"sh", "-c", "echo still-runs"}, ENV, 24, 80, 8, 16, null);
        run("j06b_cwd_file", "/bin/sh", "/etc/passwd", new String[]{"sh", "-c", "echo still-runs"}, ENV, 24, 80, 8, 16, null);
        run("j07_cmd_bad", "/nonexistent/prog", CWD, new String[]{"prog"}, ENV, 24, 80, 8, 16, null);
        run("j07b_cmd_dir", "/tmp", CWD, new String[]{"x"}, ENV, 24, 80, 8, 16, null);
        run("j07c_cmd_search_path", "sh", CWD, new String[]{"sh", "-c", "echo found-through-PATH?"}, ENV, 24, 80, 8, 16, null);
        sh("j08_fds_closed", "ls /proc/self/fd | tr '\\n' ' '");
        run("j09_winsize_flags", "/bin/sh", CWD, new String[]{"sh", "-c", TOOLS + "/winsz; stty size; stty -a | tr ' ' '\\n' | grep -E '^-?(iutf8|ixon|ixoff)$' | tr '\\n' ' '"}, ENV, 24, 80, 8, 16, null);
        run("j09b_winsize_wrap", "/bin/sh", CWD, new String[]{"sh", "-c", TOOLS + "/winsz"}, ENV, 70000, 65537, 3, 5, null);
        run("j09c_winsize_zero_neg", "/bin/sh", CWD, new String[]{"sh", "-c", TOOLS + "/winsz"}, ENV, 0, -1, 0, -1, null);
        // resize after start
        try {
            Proc p = start("/bin/sh", CWD, new String[]{"sh", "-c", "stty -echo; echo READY; read x; " + TOOLS + "/winsz; echo END"}, ENV, 24, 80, 8, 16);
            waitFor(p, "READY", 10000);
            JNI.setPtyWindowSize(p.master, 30, 100, 7, 9);
            write(p, "\n");
            int[] st = new int[1];
            report("j10_resize_after", p, finish(p, 20000, st), st[0]);
        } catch (Throwable t) { out.println("j10 HARNESS-EXC " + t); }
        // exit codes and signals
        for (int code : new int[]{0, 1, 2, 42, 126, 127, 255}) sh("j11_exit_" + code, "exit " + code);
        for (String sig : new String[]{"KILL", "TERM", "SEGV", "HUP", "ABRT", "INT"}) sh("j12_sig_" + sig, "kill -" + sig + " $$; sleep 5");
        // modified UTF-8: NUL and supplementary characters
        run("j13_mutf8", "/bin/sh", CWD, new String[]{"sh", "-c", "printf '%s' \"$1\" | od -An -tx1; printf '%s' \"$2\" | od -An -tx1; printf '%s' \"$3\" | od -An -tx1", "sh", "a\u0000b", "😀", "é€"}, ENV, 24, 80, 8, 16, null);
        run("j13b_mutf8_env", "/usr/bin/env", CWD, new String[]{"env"}, new String[]{"X=é€😀"}, 24, 80, 8, 16, null);
        // many and large arguments / environment entries (more than 512 local references)
        {
            String[] args = new String[3003];
            args[0] = "sh"; args[1] = "-c"; args[2] = "echo $#; echo ${#2}; echo \"$3\"";
            for (int i = 3; i < args.length; i++) args[i] = "a" + i;
            args[3] = "X";
            run("j14_many_args", "/bin/sh", CWD, args, ENV, 24, 80, 8, 16, null);
        }
        {
            StringBuilder big = new StringBuilder();
            for (int i = 0; i < 100000; i++) big.append((char) ('a' + i % 26));
            run("j14b_big_arg", "/bin/sh", CWD, new String[]{"sh", "-c", "echo ${#1}", "sh", big.toString()}, ENV, 24, 80, 8, 16, null);
        }
        {
            String[] args = new String[3000];
            args[0] = "sh"; args[1] = "-c"; args[2] = ":";
            String thousand = new String(new char[1000]).replace('\0', 'z');
            for (int i = 3; i < args.length; i++) args[i] = thousand;
            run("j14c_e2big", "/bin/sh", CWD, args, ENV, 24, 80, 8, 16, null);
        }
        {
            String[] env = new String[700];
            for (int i = 0; i < env.length; i++) env[i] = "V" + i + "=" + i;
            run("j15_many_env", "/bin/sh", CWD, new String[]{"sh", "-c", "env | wc -l; echo $V0 $V699"}, env, 24, 80, 8, 16, null);
        }
        // invalid descriptors
        try { JNI.close(-1); JNI.close(99999); JNI.setPtyWindowSize(-1, 1, 2, 3, 4); out.println("j16_invalid_fds => ok"); } catch (Throwable t) { out.println("j16 EXC " + t); }
        // many sessions in a row: no leaked descriptors
        {
            int before = new File("/proc/self/fd").list().length;
            int ok = 0;
            for (int i = 0; i < 300; i++) {
                int[] pid = new int[1];
                int m = JNI.createSubprocess("/bin/sh", CWD, new String[]{"sh", "-c", "exit 0"}, ENV, pid, 24, 80, 8, 16);
                if (m >= 0 && JNI.waitFor(pid[0]) == 0) ok++;
                JNI.close(m);
            }
            int after = new File("/proc/self/fd").list().length;
            out.println("j17_fd_leak => sessions_ok=" + ok + " fd_delta=" + (after - before));
        }
        // parallel sessions
        {
            final int T = 8, N = 40;
            ExecutorService ex = Executors.newFixedThreadPool(T);
            List<Future<String>> fs = new ArrayList<>();
            for (int t = 0; t < T; t++) {
                final int tt = t;
                fs.add(ex.submit(() -> {
                    StringBuilder bad = new StringBuilder();
                    for (int i = 0; i < N; i++) {
                        Proc p = start("/bin/sh", CWD, new String[]{"sh", "-c", "echo T" + tt + "N" + i + "; exit " + ((tt + i) % 7)}, ENV, 24, 80, 8, 16);
                        int[] st = new int[1];
                        String o = finish(p, 20000, st);
                        if (!o.equals("T" + tt + "N" + i + "\r\n") || st[0] != (tt + i) % 7) bad.append(tt).append(':').append(i).append(' ');
                    }
                    return bad.toString();
                }));
            }
            StringBuilder all = new StringBuilder();
            for (Future<String> f : fs) all.append(f.get());
            ex.shutdown();
            out.println("j18_parallel => mismatches=[" + all + "]");
        }
        // process id array problems (undefined in the C code, defined in the Rust code)
        if (RUST_ONLY_CASES) {
            run("r01_cwd_null", "/bin/sh", null, new String[]{"sh", "-c", "echo ok"}, ENV, 24, 80, 8, 16, null);
            run("r02_cmd_null", null, CWD, new String[]{"x"}, ENV, 24, 80, 8, 16, null);
            try {
                int m = JNI.createSubprocess("/bin/sh", CWD, new String[]{"sh", "-c", "exit 0"}, ENV, new int[0], 24, 80, 8, 16);
                out.println("r03_pid_array_empty => master=" + m);
            } catch (Throwable t) { out.println("r03_pid_array_empty => EXC " + t.getClass().getName() + ": " + t.getMessage()); }
            try {
                int m = JNI.createSubprocess("/bin/sh", CWD, new String[]{"sh", "-c", "exit 0"}, ENV, null, 24, 80, 8, 16);
                out.println("r04_pid_array_null => master=" + m);
            } catch (Throwable t) { out.println("r04_pid_array_null => EXC " + t.getClass().getName() + ": " + t.getMessage()); }
            try {
                out.println("r05_waitfor_nonchild => " + JNI.waitFor(999999) + " " + JNI.waitFor(1));
            } catch (Throwable t) { out.println("r05 EXC " + t); }
            try {
                int m = JNI.createSubprocess("/bin/sh", CWD, new String[]{"sh", null}, ENV, new int[1], 24, 80, 8, 16);
                out.println("r06_null_arg_element => master=" + m);
            } catch (Throwable t) { out.println("r06_null_arg_element => EXC " + t.getClass().getName() + ": " + t.getMessage()); }
            try {
                int m = JNI.createSubprocess("/bin/sh", CWD, new String[]{"sh"}, new String[]{"A=1", null}, new int[1], 24, 80, 8, 16);
                out.println("r07_null_env_element => master=" + m);
            } catch (Throwable t) { out.println("r07_null_env_element => EXC " + t.getClass().getName() + ": " + t.getMessage()); }
        }
        out.flush();
        System.exit(0);
    }

    /** Runs out of file descriptors on purpose (started with a low `ulimit -n`). */
    static void exhaust() throws Exception {
        JNI.close(-1); // load the class and the native library while descriptors are still available
        try { new IllegalStateException("warm-up").toString(); Runtime.getRuntime().exec(new String[]{"true"}).waitFor(); } catch (Throwable ignored) { }
        List<FileInputStream> hoard = new ArrayList<>();
        try { while (true) hoard.add(new FileInputStream("/dev/null")); } catch (IOException e) { /* limit reached */ }
        int[] pid = new int[1];
        try {
            int m = JNI.createSubprocess("/bin/sh", CWD, new String[]{"sh", "-c", "exit 0"}, ENV, pid, 24, 80, 8, 16);
            out.println("x01_no_fds => master=" + m + " pid=" + pid[0]);
        } catch (Throwable t) {
            out.println("x01_no_fds => EXC " + t.getClass().getName() + ": " + t.getMessage() + " pid=" + pid[0]);
        }
        // free exactly one descriptor: /dev/ptmx can be opened, but a pty needs more
        for (int i = 0; i < 1; i++) hoard.remove(hoard.size() - 1).close();
        try {
            int m = JNI.createSubprocess("/bin/sh", CWD, new String[]{"sh", "-c", "exit 0"}, ENV, pid, 24, 80, 8, 16);
            out.println("x02_one_fd => master=" + (m >= 0) + " pid>0=" + (pid[0] > 0));
            if (m >= 0) { JNI.waitFor(pid[0]); JNI.close(m); }
        } catch (Throwable t) {
            out.println("x02_one_fd => EXC " + t.getClass().getName() + ": " + t.getMessage());
        }
        for (FileInputStream f : hoard) f.close();
        out.flush();
        System.exit(0);
    }
}
