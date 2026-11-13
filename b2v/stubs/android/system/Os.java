package android.system;

import java.io.IOException;

public final class Os {
    /** kill(2) through /bin/kill; ESRCH when the signal could not be delivered. */
    public static void kill(int pid, int signal) throws ErrnoException {
        try {
            Process p = new ProcessBuilder("/bin/kill", "-" + signal, String.valueOf(pid)).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            if (p.waitFor() != 0) throw new ErrnoException("kill", 3);
        } catch (IOException | InterruptedException e) {
            throw new ErrnoException("kill", 1);
        }
    }
}
