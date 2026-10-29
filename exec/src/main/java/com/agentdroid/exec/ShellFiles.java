package com.agentdroid.exec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * File access built on plain shell commands, so it works on every backend
 * (root, adb, termux) without a separate file channel. Content travels as base64.
 */
public final class ShellFiles {
    /** Raw bytes per write command; keeps each command line short enough for adb and su. */
    static final int CHUNK = 24 * 1024;

    private ShellFiles() {}

    public static byte[] read(ExecBackend backend, String path) throws IOException, InterruptedException {
        ExecResult r = backend.exec(ExecRequest.of("base64 < " + ShellQuote.quote(path)));
        if (!r.ok()) throw new IOException("read failed (" + r + "): " + r.stderr().trim());
        String b64 = r.stdout().replaceAll("\\s", "");
        try {
            return Base64.getDecoder().decode(b64);
        } catch (IllegalArgumentException e) {
            throw new IOException("unexpected output while reading " + path + ": " + e.getMessage());
        }
    }

    public static void write(ExecBackend backend, String path, byte[] data) throws IOException, InterruptedException {
        String q = ShellQuote.quote(path);
        run(backend, ": > " + q);
        for (int off = 0; off < data.length; off += CHUNK) {
            int len = Math.min(CHUNK, data.length - off);
            byte[] part = new byte[len];
            System.arraycopy(data, off, part, 0, len);
            String b64 = new String(Base64.getEncoder().encode(part), StandardCharsets.US_ASCII);
            run(backend, "printf %s " + ShellQuote.quote(b64) + " | base64 -d >> " + q);
        }
    }

    private static void run(ExecBackend backend, String script) throws IOException, InterruptedException {
        ExecResult r = backend.exec(ExecRequest.of(script));
        if (!r.ok()) throw new IOException("write failed (" + r + "): " + r.stderr().trim());
    }

    /** Reads at most {@code max} bytes from a stream. Helper for process backends. */
    static byte[] drain(java.io.InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            if (out.size() < max) out.write(buf, 0, Math.min(n, max - out.size()));
        }
        return out.toByteArray();
    }
}
