package com.agentdroid.exec;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Random;
import org.junit.Test;

public class ExecTest {
    private final ProcessBackend sh = new ProcessBackend("sandbox", "local sh", "sh", "-c");

    /** Transport that runs scripts in a local shell: stands in for the ADB shell. */
    private static ShellTransport localTransport(boolean reachable) {
        final ProcessBackend sh = new ProcessBackend("t", "t", "sh", "-c");
        return new ShellTransport() {
            @Override public Output run(String script, long timeoutMs) throws IOException {
                try {
                    ExecResult r = sh.exec(ExecRequest.builder(script).timeoutMs(timeoutMs).build());
                    return new Output(r.exitCode(), r.stdout() + r.stderr());
                } catch (InterruptedException e) {
                    throw new IOException(e);
                }
            }
            @Override public boolean isReachable() { return reachable; }
        };
    }

    @Test public void quoteHandlesSingleQuotesAndShellMetacharacters() throws Exception {
        String nasty = "a'b $(echo pwned) `id` ; && \"x\"";
        ExecResult r = sh.exec(ExecRequest.of("printf %s " + ShellQuote.quote(nasty)));
        assertEquals(nasty, r.stdout());
    }

    @Test public void exitCodeStdoutAndStderrAreSeparated() throws Exception {
        ExecResult r = sh.exec(ExecRequest.of("echo out; echo err 1>&2; exit 3"));
        assertEquals(3, r.exitCode());
        assertFalse(r.ok());
        assertEquals("out\n", r.stdout());
        assertEquals("err\n", r.stderr());
    }

    @Test public void workingDirAndEnvAreApplied() throws Exception {
        File dir = Files.createTempDirectory("exec-test").toFile();
        ExecResult r = sh.exec(ExecRequest.builder("printf '%s|%s' \"$PWD\" \"$GREETING\"")
                .workingDir(dir.getCanonicalPath()).env("GREETING", "it's ok").build());
        assertEquals(dir.getCanonicalPath() + "|it's ok", r.stdout());
    }

    @Test public void missingWorkingDirFailsInsteadOfRunningElsewhere() throws Exception {
        ExecResult r = sh.exec(ExecRequest.builder("echo should-not-run").workingDir("/no/such/dir/xyz").build());
        assertEquals(1, r.exitCode());
        assertFalse(r.stdout().contains("should-not-run"));
    }

    @Test(expected = IllegalArgumentException.class) public void badEnvNameIsRejected() {
        ExecRequest.builder("true").env("A;rm -rf /", "x");
    }

    @Test public void stdinIsDelivered() throws Exception {
        ExecResult r = sh.exec(ExecRequest.builder("cat").stdin("hello stdin".getBytes(StandardCharsets.UTF_8)).build());
        assertEquals("hello stdin", r.stdout());
    }

    @Test public void timeoutKillsTheProcess() throws Exception {
        long t0 = System.nanoTime();
        ExecResult r = sh.exec(ExecRequest.builder("sleep 30").timeoutMs(500).build());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(r.timedOut());
        assertFalse(r.ok());
        assertTrue("took " + ms + " ms", ms < 6000);
    }

    @Test public void fileRoundTripIncludingMultiChunkBinary() throws Exception {
        File f = File.createTempFile("exec-file", ".bin");
        f.deleteOnExit();
        byte[] data = new byte[ShellFiles.CHUNK * 2 + 123];
        new Random(42).nextBytes(data);
        sh.writeFile(f.getPath(), data);
        assertArrayEquals(data, Files.readAllBytes(f.toPath()));
        assertArrayEquals(data, sh.readFile(f.getPath()));
    }

    @Test public void filePathWithSpacesAndQuotes() throws Exception {
        File dir = Files.createTempDirectory("exec dir").toFile();
        File f = new File(dir, "it's a file.txt");
        sh.writeFile(f.getPath(), "x".getBytes(StandardCharsets.UTF_8));
        assertEquals("x", new String(sh.readFile(f.getPath()), StandardCharsets.UTF_8));
    }

    @Test(expected = IOException.class) public void readingMissingFileThrows() throws Exception {
        sh.readFile("/no/such/file/xyz");
    }

    @Test public void adbBackendRunsScriptsAndFiles() throws Exception {
        AdbBackend adb = new AdbBackend("adb-local", "ADB localhost", localTransport(true));
        assertTrue(adb.isAvailable());
        ExecResult r = adb.exec(ExecRequest.builder("echo $X").env("X", "v1").build());
        assertEquals("v1\n", r.stdout());
        File f = File.createTempFile("adb-file", ".txt");
        f.deleteOnExit();
        adb.writeFile(f.getPath(), "über".getBytes(StandardCharsets.UTF_8));
        assertEquals("über", new String(adb.readFile(f.getPath()), StandardCharsets.UTF_8));
    }

    @Test public void adbBackendStdinGoesThroughBase64Pipe() throws Exception {
        AdbBackend adb = new AdbBackend("adb-local", "ADB", localTransport(true));
        ExecResult r = adb.exec(ExecRequest.builder("tr a-z A-Z").stdin("abc 'q'".getBytes(StandardCharsets.UTF_8)).build());
        assertEquals("ABC 'Q'", r.stdout());
    }

    @Test public void unreachableAdbIsNotAvailable() {
        assertFalse(new AdbBackend("adb-wifi", "ADB Wi-Fi", localTransport(false)).isAvailable());
    }

    @Test public void rootBackendUnavailableWithoutSu() {
        assertFalse(new RootBackend("/no/such/su").isAvailable());
    }

    @Test public void selectorUsesPriorityAndSkipsUnavailable() {
        ExecBackend root = new RootBackend("/no/such/su");
        ExecBackend local = new AdbBackend("adb-local", "l", localTransport(false));
        ExecBackend wifi = new AdbBackend("adb-wifi", "w", localTransport(true));
        BackendSelector s = new BackendSelector(Arrays.asList(root, local, wifi, sh));
        assertSame(wifi, s.select());
        assertEquals(Arrays.asList("root: unavailable", "adb-local: unavailable", "adb-wifi: available", "sandbox: available"), s.report());
    }

    @Test public void pinnedUnavailableBackendIsNotReplacedSilently() {
        ExecBackend root = new RootBackend("/no/such/su");
        BackendSelector s = new BackendSelector(Arrays.asList(root, sh));
        s.pin("root");
        assertNull(s.select());
        s.pin("sandbox");
        assertSame(sh, s.select());
        s.pin(null);
        assertSame(sh, s.select());
    }

    @Test(expected = IllegalArgumentException.class) public void pinningUnknownIdFails() {
        new BackendSelector(Arrays.asList(sh)).pin("nope");
    }
}
