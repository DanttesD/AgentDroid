package com.agentdroid.agent;

import com.agentdroid.exec.ExecBackend;
import com.agentdroid.exec.ExecRequest;
import com.agentdroid.exec.ExecResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Built-in tools; every one runs through the selected {@link ExecBackend}. */
public final class Tools {
    private Tools() {}

    public static List<Tool> standard(ExecBackend backend, String workDir) {
        List<Tool> l = new ArrayList<>();
        l.add(new Shell(backend, workDir));
        l.add(new ReadFile(backend, workDir));
        l.add(new WriteFile(backend, workDir));
        l.add(new ListDir(backend, workDir));
        return l;
    }

    static String str(Map<String, Object> a, String k) {
        Object v = a.get(k);
        if (!(v instanceof String) || ((String) v).isEmpty()) throw new IllegalArgumentException("missing argument: " + k);
        return (String) v;
    }

    static String resolve(String workDir, String p) {
        if (p.startsWith("/") || workDir == null) return p;
        return workDir + (workDir.endsWith("/") ? "" : "/") + p;
    }

    static Map<String, Object> schema(String[] names, String[] descs, String... required) {
        Map<String, Object> props = new java.util.LinkedHashMap<>();
        for (int i = 0; i < names.length; i++) props.put(names[i], Json.obj("type", "string", "description", descs[i]));
        List<Object> req = new ArrayList<>();
        for (String r : required) req.add(r);
        return Json.obj("type", "object", "properties", props, "required", req);
    }

    public static final class Shell implements Tool {
        private final ExecBackend b; private final String dir;
        public Shell(ExecBackend b, String dir) { this.b = b; this.dir = dir; }
        public ToolSpec spec() {
            return new ToolSpec("shell", "Run a shell command on the device and get exit code, stdout and stderr.",
                    schema(new String[]{"command"}, new String[]{"Shell command"}, "command"));
        }
        public boolean needsApproval(Map<String, Object> a) { return true; }
        public String describe(Map<String, Object> a) { return "Run: " + a.get("command"); }
        public String execute(Map<String, Object> a) throws Exception {
            ExecRequest.Builder rb = ExecRequest.builder(str(a, "command")).timeoutMs(60_000L);
            if (dir != null) rb.workingDir(dir);
            ExecResult r = b.exec(rb.build());
            StringBuilder sb = new StringBuilder();
            if (r.timedOut()) sb.append("[timed out]\n");
            sb.append("exit code: ").append(r.exitCode()).append('\n');
            if (!r.stdout().isEmpty()) sb.append("stdout:\n").append(r.stdout()).append('\n');
            if (!r.stderr().isEmpty()) sb.append("stderr:\n").append(r.stderr()).append('\n');
            return sb.toString();
        }
    }

    public static final class ReadFile implements Tool {
        private final ExecBackend b; private final String dir;
        public ReadFile(ExecBackend b, String dir) { this.b = b; this.dir = dir; }
        public ToolSpec spec() {
            return new ToolSpec("read_file", "Read a text file.", schema(new String[]{"path"}, new String[]{"File path"}, "path"));
        }
        public boolean needsApproval(Map<String, Object> a) { return false; }
        public String describe(Map<String, Object> a) { return "Read " + a.get("path"); }
        public String execute(Map<String, Object> a) throws Exception {
            return new String(b.readFile(resolve(dir, str(a, "path"))), StandardCharsets.UTF_8);
        }
    }

    public static final class WriteFile implements Tool {
        private final ExecBackend b; private final String dir;
        public WriteFile(ExecBackend b, String dir) { this.b = b; this.dir = dir; }
        public ToolSpec spec() {
            return new ToolSpec("write_file", "Create or overwrite a file with the given content.",
                    schema(new String[]{"path", "content"}, new String[]{"File path", "Full file content"}, "path", "content"));
        }
        public boolean needsApproval(Map<String, Object> a) { return true; }
        public String describe(Map<String, Object> a) {
            Object c = a.get("content");
            return "Write " + a.get("path") + " (" + (c instanceof String ? ((String) c).length() : 0) + " chars)";
        }
        public String execute(Map<String, Object> a) throws Exception {
            String content = a.get("content") instanceof String ? (String) a.get("content") : "";
            b.writeFile(resolve(dir, str(a, "path")), content.getBytes(StandardCharsets.UTF_8));
            return "written " + content.length() + " chars";
        }
    }

    public static final class ListDir implements Tool {
        private final ExecBackend b; private final String dir;
        public ListDir(ExecBackend b, String dir) { this.b = b; this.dir = dir; }
        public ToolSpec spec() {
            return new ToolSpec("list_dir", "List a directory (ls -la).", schema(new String[]{"path"}, new String[]{"Directory path"}, "path"));
        }
        public boolean needsApproval(Map<String, Object> a) { return false; }
        public String describe(Map<String, Object> a) { return "List " + a.get("path"); }
        public String execute(Map<String, Object> a) throws Exception {
            ExecResult r = b.exec(ExecRequest.builder("ls -la " + com.agentdroid.exec.ShellQuote.quote(resolve(dir, str(a, "path")))).build());
            return r.ok() ? r.stdout() : "error: " + r.stderr();
        }
    }
}
