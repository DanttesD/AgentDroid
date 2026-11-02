package com.agentdroid.agent;

import com.agentdroid.exec.ExecBackend;
import com.agentdroid.exec.ShellQuote;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Developer tools matching the PC version: Edit, Glob, Grep. */
public final class DevTools {
    private DevTools() {}

    public static List<Tool> create(ExecBackend b, String workDir) {
        List<Tool> l = new ArrayList<>();
        l.add(new Edit(b, workDir));
        l.add(new Glob(b, workDir));
        l.add(new Grep(b, workDir));
        return l;
    }

    private static String opt(Map<String, Object> a, String k) {
        Object v = a.get(k);
        return v instanceof String && !((String) v).isEmpty() ? (String) v : null;
    }

    static final class Edit implements Tool {
        private final ExecBackend b; private final String dir;
        Edit(ExecBackend b, String dir) { this.b = b; this.dir = dir; }
        public ToolSpec spec() {
            return new ToolSpec("edit", "Replace an exact string in a file. old must occur exactly once unless replace_all is true.",
                    UiTools.schema("*path", "string", "File path", "*old", "string", "Exact text to replace", "*new", "string", "Replacement",
                            "replace_all", "boolean", "Replace every occurrence"));
        }
        public boolean needsApproval(Map<String, Object> a) { return true; }
        public String describe(Map<String, Object> a) { return "Edit " + a.get("path"); }
        public String execute(Map<String, Object> a) throws Exception {
            String path = Tools.resolve(dir, Tools.str(a, "path"));
            String old = Tools.str(a, "old");
            Object nv = a.get("new");
            String neu = nv == null ? "" : nv.toString();
            String text = new String(b.readFile(path), StandardCharsets.UTF_8);
            int first = text.indexOf(old);
            if (first < 0) throw new IllegalArgumentException("old text not found");
            boolean all = Boolean.TRUE.equals(a.get("replace_all")) || "true".equals(String.valueOf(a.get("replace_all")));
            int count = 0;
            for (int i = first; i >= 0; i = text.indexOf(old, i + old.length())) count++;
            if (count > 1 && !all) throw new IllegalArgumentException("old text occurs " + count + " times; add context or set replace_all");
            String out = all ? text.replace(old, neu) : text.substring(0, first) + neu + text.substring(first + old.length());
            b.writeFile(path, out.getBytes(StandardCharsets.UTF_8));
            return "replaced " + (all ? count : 1) + " occurrence(s)";
        }
    }

    static final class Glob implements Tool {
        private final ExecBackend b; private final String dir;
        Glob(ExecBackend b, String dir) { this.b = b; this.dir = dir; }
        public ToolSpec spec() {
            return new ToolSpec("glob", "Find files by name pattern (e.g. *.java) under a directory, newest first. Max 200 results.",
                    UiTools.schema("*pattern", "string", "File name pattern, * and ? allowed", "path", "string", "Directory (default home)"));
        }
        public boolean needsApproval(Map<String, Object> a) { return false; }
        public String describe(Map<String, Object> a) { return "Glob " + a.get("pattern"); }
        public String execute(Map<String, Object> a) throws Exception {
            String root = Tools.resolve(dir, opt(a, "path") == null ? "." : opt(a, "path"));
            String pat = Tools.str(a, "pattern");
            String name = pat.contains("/") ? pat.substring(pat.lastIndexOf('/') + 1) : pat;
            return UiTools.sx(b, "find " + ShellQuote.quote(root) + " -type f -name " + ShellQuote.quote(name)
                    + " 2>/dev/null | head -n 200");
        }
    }

    static final class Grep implements Tool {
        private final ExecBackend b; private final String dir;
        Grep(ExecBackend b, String dir) { this.b = b; this.dir = dir; }
        public ToolSpec spec() {
            return new ToolSpec("grep", "Search file contents with a regular expression (grep -E). Returns file:line:text, max 200 lines.",
                    UiTools.schema("*pattern", "string", "Regex", "path", "string", "File or directory (default home)",
                            "include", "string", "File name pattern, e.g. *.java", "ignore_case", "boolean", "Case-insensitive"));
        }
        public boolean needsApproval(Map<String, Object> a) { return false; }
        public String describe(Map<String, Object> a) { return "Grep " + a.get("pattern"); }
        public String execute(Map<String, Object> a) throws Exception {
            String root = Tools.resolve(dir, opt(a, "path") == null ? "." : opt(a, "path"));
            StringBuilder c = new StringBuilder("grep -rnIE");
            if ("true".equals(String.valueOf(a.get("ignore_case"))) || Boolean.TRUE.equals(a.get("ignore_case"))) c.append('i');
            if (opt(a, "include") != null) c.append(" --include=").append(ShellQuote.quote(opt(a, "include")));
            c.append(" -e ").append(ShellQuote.quote(Tools.str(a, "pattern"))).append(' ').append(ShellQuote.quote(root));
            c.append(" 2>/dev/null | head -n 200");
            String out = UiTools.sx(b, c.toString());
            return out.isEmpty() ? "no matches" : out;
        }
    }
}
