package android.os;

import java.util.*;
import java.util.concurrent.*;

/** Functional stand-in for android.os.Handler: one process-wide "main looper" thread executes messages and delayed runnables in order. */
public class Handler {
    private static final ScheduledThreadPoolExecutor LOOPER = new ScheduledThreadPoolExecutor(1, r -> {
        Thread t = new Thread(r, "main-looper");
        t.setDaemon(true);
        return t;
    });
    /** Exceptions thrown on the looper thread (Android would crash the app). */
    public static final List<Throwable> ERRORS = new CopyOnWriteArrayList<>();
    private final Map<Runnable, List<ScheduledFuture<?>>> pending = new HashMap<>();

    /** Not an Android API: run on the looper thread and wait, so the harness acts like the UI thread. */
    public static void runOnMain(Runnable r) {
        if (Thread.currentThread().getName().equals("main-looper")) { r.run(); return; }
        FutureTask<Void> f = new FutureTask<>(r, null);
        LOOPER.execute(f);
        try { f.get(60, TimeUnit.SECONDS); }
        catch (ExecutionException e) { if (e.getCause() instanceof RuntimeException) throw (RuntimeException) e.getCause(); throw new RuntimeException(e.getCause()); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    public Handler() {}
    public void handleMessage(Message msg) {}
    public final boolean sendEmptyMessage(int what) { return sendMessage(obtainMessage(what, null)); }
    public final boolean sendMessage(Message msg) {
        LOOPER.execute(() -> { try { handleMessage(msg); } catch (Throwable t) { ERRORS.add(t); } });
        return true;
    }
    public final Message obtainMessage(int what, Object obj) { Message m = new Message(); m.what = what; m.obj = obj; return m; }
    public final void removeCallbacks(Runnable r) {
        synchronized (pending) {
            List<ScheduledFuture<?>> l = pending.remove(r);
            if (l != null) for (ScheduledFuture<?> f : l) f.cancel(false);
        }
    }
    public final boolean postAtTime(Runnable r, long uptimeMillis) {
        long delay = Math.max(0, uptimeMillis - SystemClock.uptimeMillis());
        ScheduledFuture<?> f = LOOPER.schedule(() -> { try { r.run(); } catch (Throwable t) { ERRORS.add(t); } }, delay, TimeUnit.MILLISECONDS);
        synchronized (pending) { pending.computeIfAbsent(r, k -> new ArrayList<>()).add(f); }
        return true;
    }
}
