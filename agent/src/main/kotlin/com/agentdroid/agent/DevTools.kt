package com.agentdroid.agent

import com.agentdroid.exec.ExecBackend
import com.agentdroid.exec.ShellQuote

/** Developer tools matching the PC version: Edit, Glob, Grep. */
object DevTools {
    @JvmStatic
    fun create(b: ExecBackend, workDir: String?): List<Tool> {
        val l = ArrayList<Tool>()
        l.add(Edit(b, workDir))
        l.add(Glob(b, workDir))
        l.add(Grep(b, workDir))
        return l
    }

    private fun opt(a: Map<String, Any?>, k: String): String? {
        val v = a[k]
        return if (v is String && v.isNotEmpty()) v else null
    }

    internal class Edit(private val b: ExecBackend, private val dir: String?) : Tool {
        override fun spec(): ToolSpec = ToolSpec(
            "edit", "Replace an exact string in a file. old must occur exactly once unless replace_all is true.",
            UiTools.schema(
                "*path", "string", "File path", "*old", "string", "Exact text to replace", "*new", "string", "Replacement",
                "replace_all", "boolean", "Replace every occurrence",
            ),
        )

        override fun needsApproval(args: Map<String, Any?>): Boolean = true

        override fun describe(args: Map<String, Any?>): String = "Edit " + args["path"]

        @Throws(Exception::class)
        override fun execute(args: Map<String, Any?>): String {
            val path = Tools.resolve(dir, Tools.str(args, "path"))
            val old = Tools.str(args, "old")
            val nv = args["new"]
            val neu = nv?.toString() ?: ""
            val text = String(b.readFile(path), Charsets.UTF_8)
            val first = text.indexOf(old)
            if (first < 0) throw IllegalArgumentException("old text not found")
            val all = flag(args, "replace_all")
            var count = 0
            var i = first
            while (i >= 0) {
                count++
                i = text.indexOf(old, i + old.length)
            }
            if (count > 1 && !all) throw IllegalArgumentException("old text occurs $count times; add context or set replace_all")
            val out = if (all) text.replace(old, neu) else text.substring(0, first) + neu + text.substring(first + old.length)
            b.writeFile(path, out.toByteArray(Charsets.UTF_8))
            return "replaced " + (if (all) count else 1) + " occurrence(s)"
        }
    }

    internal class Glob(private val b: ExecBackend, private val dir: String?) : Tool {
        override fun spec(): ToolSpec = ToolSpec(
            "glob", "Find files by name pattern (e.g. *.java) under a directory, newest first. Max 200 results.",
            UiTools.schema("*pattern", "string", "File name pattern, * and ? allowed", "path", "string", "Directory (default home)"),
        )

        override fun needsApproval(args: Map<String, Any?>): Boolean = false

        override fun describe(args: Map<String, Any?>): String = "Glob " + args["pattern"]

        @Throws(Exception::class)
        override fun execute(args: Map<String, Any?>): String {
            val root = Tools.resolve(dir, opt(args, "path") ?: ".")
            val pat = Tools.str(args, "pattern")
            val name = if (pat.contains("/")) pat.substring(pat.lastIndexOf('/') + 1) else pat
            return UiTools.sx(
                b,
                "find " + ShellQuote.quote(root) + " -type f -name " + ShellQuote.quote(name) + " 2>/dev/null | head -n 200",
            )
        }
    }

    internal class Grep(private val b: ExecBackend, private val dir: String?) : Tool {
        override fun spec(): ToolSpec = ToolSpec(
            "grep", "Search file contents with a regular expression (grep -E). Returns file:line:text, max 200 lines.",
            UiTools.schema(
                "*pattern", "string", "Regex", "path", "string", "File or directory (default home)",
                "include", "string", "File name pattern, e.g. *.java", "ignore_case", "boolean", "Case-insensitive",
            ),
        )

        override fun needsApproval(args: Map<String, Any?>): Boolean = false

        override fun describe(args: Map<String, Any?>): String = "Grep " + args["pattern"]

        @Throws(Exception::class)
        override fun execute(args: Map<String, Any?>): String {
            val root = Tools.resolve(dir, opt(args, "path") ?: ".")
            val c = StringBuilder("grep -rnIE")
            if (flag(args, "ignore_case")) c.append('i')
            val include = opt(args, "include")
            if (include != null) c.append(" --include=").append(ShellQuote.quote(include))
            c.append(" -e ").append(ShellQuote.quote(Tools.str(args, "pattern"))).append(' ').append(ShellQuote.quote(root))
            c.append(" 2>/dev/null | head -n 200")
            val out = UiTools.sx(b, c.toString())
            return if (out.isEmpty()) "no matches" else out
        }
    }
}
