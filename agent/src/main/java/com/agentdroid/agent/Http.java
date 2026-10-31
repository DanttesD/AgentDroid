package com.agentdroid.agent;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** POST JSON and read a server-sent-events stream. */
final class Http {
    private Http() {}

    interface EventSink { void onData(String data) throws IOException; }

    static void postSse(String url, Map<String, String> headers, String body, EventSink sink) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(30_000);
        c.setReadTimeout(120_000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "text/event-stream");
        for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = c.getOutputStream()) { os.write(payload); }
        int code = c.getResponseCode();
        if (code / 100 != 2) {
            InputStream es = c.getErrorStream();
            String err = es == null ? "" : readAll(es);
            throw new IOException("HTTP " + code + ": " + err);
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("data:")) {
                    String d = line.substring(5);
                    if (d.startsWith(" ")) d = d.substring(1);
                    sink.onData(d);
                }
            }
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
